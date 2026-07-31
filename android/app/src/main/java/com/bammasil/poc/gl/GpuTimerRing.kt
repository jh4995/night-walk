package com.bammasil.poc.gl

import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import com.bammasil.poc.log.FrameLogRecorder

/**
 * 3패스 arm의 **패스별 GPU 시간**을 재는 timer query 링. `FRAME_BUDGET.md`의 B·D 칸에
 * 숫자를 넣는 유일한 수단이다.
 *
 * ### 왜 `GL_TIME_ELAPSED`인가 (타임스탬프 방식이 아니라)
 * `android.opengl.GLES30`에는 `glQueryCounterEXT` / `glGetQueryObjectui64vEXT` **진입점이
 * 없다.** JNI 없이 쓸 수 있는 것은 코어 `glBeginQuery`/`glEndQuery`에 EXT 타깃
 * ([TIME_ELAPSED_EXT])을 넘기는 경로뿐이다. 확장이 **존재하는 것**과 코어 진입점이 그
 * 타깃을 **받아 주는 것**은 다른 문제이므로, 첫 사용 전에 [initialize]가 실제로 한 번
 * 걸어 보고 `glGetError()`로 확인한다. 실패하면 계측을 끄고 그 사실을 `session.json`에
 * 남긴다 — 0이나 쓰레기값을 흘리지 않는다.
 *
 * ### 제약 세 가지가 이 클래스의 모양을 정했다
 * 1. **중첩 불가.** `GL_TIME_ELAPSED`는 타깃당 동시에 하나만 활성이다 → "프레임 전체
 *    query + 패스별 query"를 같이 걸 수 없다. **패스별로 순차로만** 건다. 총합은 앱이
 *    계산하지 않는다(스키마 §2 "유도값은 저장하지 않는다"). 하네스가 `gpu_sum_ms`를
 *    행별로 낸다.
 * 2. **즉시 읽으면 안 된다.** `glEndQuery` 직후 `GL_QUERY_RESULT`를 부르면 GPU 동기화로
 *    파이프라인이 멈추고 **측정 대상 자체가 오염된다** → query 객체를 링으로 미리 잡고
 *    ([RING_DEPTH] 프레임분), `GL_QUERY_RESULT_AVAILABLE`을 폴링해 몇 프레임 뒤에 회수한다.
 * 3. **disjoint.** 모바일 GPU는 DVFS로 GPU 클럭이 바뀌면 그 구간 결과가 무의미해진다.
 *    매 프레임 [GPU_DISJOINT_EXT]를 읽고(**읽으면 리셋된다**) 세워져 있으면 그 시점의
 *    in-flight query를 **전량 폐기하고 개수를 센다.**
 *
 * ### 값 회수 → 행 back-fill 정렬
 * 회수된 값은 **그 query를 건 프레임의 행**에 들어가야 한다. 회수 시점의 최신 행에 쓰면
 * 정렬이 어긋난 채로 그럴듯해 보인다 → [FrameLogRecorder.record]가 돌려준 **슬롯 인덱스**를
 * 링 엔트리에 함께 들고 있다가([commitFrame]) 해소될 때 그 슬롯에 쓴다.
 *
 * ### 패스 인덱스 → 열
 * `session.json`의 `render.passes[].gpu_column`과 같은 매핑이다. [PASS_COUNT]를 바꾸면
 * [consume]의 인자 개수도 함께 고쳐야 한다.
 * ```
 * 0  패스1 OES → FBO_A   stage_b_ms      (버짓 B칸)
 * 1  패스2 ② 자리        stage_d_ms      (버짓 D칸)
 * 2  패스3 → 화면        gpu_present_ms  (칸 아님)
 * ```
 *
 * **스레드 규약: 전부 GL 스레드에서만 부른다.** 컨텍스트가 현재가 아니면 전부 무의미하다.
 */
class GpuTimerRing(private val recorder: FrameLogRecorder) {

    private enum class State { UNINITIALIZED, READY, DISABLED }

    private var state = State.UNINITIALIZED

    /** 왜 껐는가. 켜져 있으면 null. `session.json`에 그대로 나간다. */
    private var disabledReason: String? = null

    /** `glBeginQuery` 직후 `glGetError()`가 낸 것. 성공이면 null. */
    private var beginQueryError: String? = null

    /** `GlCapabilitiesProbe`의 3-상태 문자열. 프로브 전에는 unknown. */
    private var extensionPresent: String = GlCapabilitiesProbe.UNKNOWN

    /** [PASS_COUNT] × [RING_DEPTH]개를 **한 번에** 잡는다. 프레임당 할당 0. */
    private val queryIds = IntArray(PASS_COUNT * RING_DEPTH)

    /** 엔트리 i가 채워야 할 CSV 행 슬롯. [SLOT_NONE]이면 채울 행이 없다. */
    private val entrySlot = IntArray(RING_DEPTH) { SLOT_NONE }

    /** 엔트리 i를 건 런의 세대. [generation]과 다르면 결과를 버린다. */
    private val entryGeneration = IntArray(RING_DEPTH) { STALE_GENERATION }

    /** FIFO 링. query는 건 순서대로 해소되므로 head부터 보면 된다. */
    private var head = 0
    private var inFlight = 0

    /** 이번 프레임에 쓰는 엔트리. [NO_ENTRY]면 이번 프레임은 계측하지 않는다. */
    private var activeEntry = NO_ENTRY

    /** 이번 프레임에서 지금까지 끝낸 패스 수. */
    private var passCursor = 0

    /** `glGetQueryObjectuiv` / `glGetIntegerv` 공용. 프레임당 할당을 만들지 않기 위해서다. */
    private val scratch = IntArray(1)

    /** 한 엔트리에서 읽어 낸 패스별 ns. 재사용한다. */
    private val values = LongArray(PASS_COUNT)

    /**
     * 런 세대. [beginRun]에서 올린다. 이전 런의 in-flight query가 다음 런의 슬롯에
     * 잘못 들어가는 것을 막는 유일한 장치다.
     */
    private var generation = 0

    /**
     * 이 런에서 disjoint 플래그를 아직 한 번도 읽지 않았다.
     *
     * `GL_GPU_DISJOINT_EXT`는 **지난 읽기 이후 누적**이고 읽으면 리셋된다. 그래서 계측하지
     * 않는 기간(프리뷰 중·arm 전환 후·앱 시작 직후)에 선 플래그가 다음 런의 **첫 계측
     * 프레임**에 그대로 청구된다 — 걸지도 않은 query가 깨진 것처럼 보인다. 첫 읽기는
     * **기준선으로만 쓰고 세지 않는다.**
     */
    private var needDisjointBaseline = true

    // ── 런별 계수 (전부 [beginRun]에서 0으로) ─────────────────────────────

    private var instrumentedFrames = 0
    private var resolvedFrames = 0
    private var resolvedQueries = 0
    private var positiveSamples = 0
    private var zeroResultQueries = 0
    private var unresolvedQueries = 0
    private var disjointFrames = 0
    private var discardedDisjointQueries = 0
    private var skippedRingFullFrames = 0
    private var malformedFrames = 0

    // ── 컨텍스트 수명 ─────────────────────────────────────────────────────

    /** `onSurfaceCreated`에서 GL 능력 프로브 직후에 부른다. */
    fun onContextCreated(extensionPresent: String) {
        this.extensionPresent = extensionPresent
    }

    /**
     * 컨텍스트가 죽었거나 재생성됐다. query 객체를 지우고 처음 상태로 되돌린다 —
     * 이걸 빠뜨리면 컨텍스트가 재생성될 때마다 query 객체가 샌다.
     */
    fun releaseGl() {
        if (state == State.READY) {
            GLES30.glDeleteQueries(queryIds.size, queryIds, 0)
        }
        queryIds.fill(0)
        entrySlot.fill(SLOT_NONE)
        entryGeneration.fill(STALE_GENERATION)
        head = 0
        inFlight = 0
        activeEntry = NO_ENTRY
        passCursor = 0
        state = State.UNINITIALIZED
        disabledReason = null
        beginQueryError = null
        // 새 컨텍스트의 disjoint 이력은 우리 것이 아니다. 다시 기준선부터.
        needDisjointBaseline = true
    }

    /**
     * 프로브와 query 객체 생성을 **측정 시작 전에** 끝내 둔다. 3패스 arm을 고를 때
     * (GL 스레드) 부른다. 안 불러도 [beginFrame]이 지연 초기화하지만, 그러면 일회성
     * 프로브 비용이 측정 첫 프레임에 얹힌다.
     */
    fun prepare() {
        if (state == State.UNINITIALIZED) initialize()
    }

    // ── 런 경계 ──────────────────────────────────────────────────────────

    /** 측정 시작 직전(GL 스레드). 계수를 런별로 되돌리고 이전 런의 결과를 무효화한다. */
    fun beginRun() {
        generation++
        // ⚠ 아직 떠 있는 query 객체를 여기서 지우거나 재사용하지 않는다. 해소되지 않은
        //   query의 이름을 다시 begin하면 GL_INVALID_OPERATION이다. 세대만 무효화하고,
        //   실제 회수는 다음 [drainResolved]가 한다(값은 버려진다).
        for (i in 0 until inFlight) {
            entryGeneration[(head + i) % RING_DEPTH] = STALE_GENERATION
        }
        // 런 사이에 쌓인 disjoint를 이 런에 청구하지 않는다([needDisjointBaseline] 참고).
        needDisjointBaseline = true
        instrumentedFrames = 0
        resolvedFrames = 0
        resolvedQueries = 0
        positiveSamples = 0
        zeroResultQueries = 0
        unresolvedQueries = 0
        disjointFrames = 0
        discardedDisjointQueries = 0
        skippedRingFullFrames = 0
        malformedFrames = 0
    }

    /**
     * 측정 정지 후 `frames.csv`를 쓰기 **직전에** GL 스레드에서 부른다.
     *
     * 남은 in-flight를 억지로 회수하지 않는다 — `GL_QUERY_RESULT`를 기다리면 GPU 동기화가
     * 걸리고, 그건 이 런의 끝을 오염시킨다. 대신 **비차단 폴링을 한 번 더** 돌려 회수
     * 가능한 것만 챙기고, 남은 것은 `-1`로 두고 [GpuTimerReport.unresolvedQueries]에 센다.
     * 이 저장소는 tail 표본이 조용히 사라지는 결함을 실제로 겪은 적이 있다.
     */
    fun finishRun(): GpuTimerReport {
        // ⚠ `instrumentedFrames == 0`이면 아무것도 읽지 않는다. disjoint 플래그는 **읽으면
        //   리셋되는 누적 플래그**라, 계측하지 않은 런에서 읽으면 이전 런 이후 쌓인 것이
        //   이 런의 disjoint_frames=1로 잡힌다 — 걸지도 않은 query가 깨진 것처럼 보인다.
        if (state == State.READY && instrumentedFrames > 0) {
            checkDisjoint()
            drainResolved()
        }
        var unresolved = 0
        for (i in 0 until inFlight) {
            if (entryGeneration[(head + i) % RING_DEPTH] == generation) {
                unresolved += PASS_COUNT
            }
        }
        unresolvedQueries = unresolved
        return GpuTimerReport(
            extensionPresent = extensionPresent,
            instrumented = instrumentedFrames > 0,
            // "이 런이 GPU 패스 시간 표본을 낼 수 있었는가". 하네스의 모순 검사와 맞물리게
            // **0보다 큰 값을 실제로 한 개 이상 남겼는가**로 정의한다. 하네스의 _collect는
            // 하한이 `> 0`이라 0ms 값을 폐기하므로, 여기서 0을 표본으로 세면 선언만 true인
            // 상태가 되어 gpu_timer_contradicted가 뜬다.
            supported = positiveSamples > 0,
            instrumentedFrames = instrumentedFrames,
            resolvedFrames = resolvedFrames,
            resolvedQueries = resolvedQueries,
            positiveSamples = positiveSamples,
            zeroResultQueries = zeroResultQueries,
            unresolvedQueries = unresolved,
            disjointFrames = disjointFrames,
            discardedDisjointQueries = discardedDisjointQueries,
            skippedRingFullFrames = skippedRingFullFrames,
            malformedFrames = malformedFrames,
            beginQueryError = beginQueryError,
            disabledReason = disabledReason,
        )
    }

    // ── 프레임 경로 (렌더 스레드 hot path) ────────────────────────────────

    /**
     * 3패스를 그리기 직전에 부른다. `true`면 이번 프레임에 query를 건다.
     * **패스스루 arm에서는 절대 불리지 않는다** — 호출부가 arm으로 먼저 갈린다.
     */
    fun beginFrame(): Boolean {
        if (state == State.DISABLED) return false
        if (state == State.UNINITIALIZED) {
            initialize()
            if (state != State.READY) return false
        }
        // 순서가 중요하다. disjoint를 먼저 처리하지 않으면, 무효인 구간의 결과를 먼저
        // 회수해 행에 써 버린다.
        checkDisjoint()
        drainResolved()
        if (inFlight >= RING_DEPTH) {
            // 회수가 밀렸다. 값을 지어내지 않고 이번 프레임은 계측을 건너뛴다.
            skippedRingFullFrames++
            activeEntry = NO_ENTRY
            return false
        }
        activeEntry = (head + inFlight) % RING_DEPTH
        inFlight++
        entrySlot[activeEntry] = SLOT_NONE
        entryGeneration[activeEntry] = generation
        passCursor = 0
        instrumentedFrames++
        return true
    }

    /** 패스 하나의 GL 명령을 감싼다. [beginFrame]이 true를 준 프레임에서만 부른다. */
    fun beginPass() {
        val entry = activeEntry
        if (entry == NO_ENTRY || passCursor >= PASS_COUNT) return
        GLES30.glBeginQuery(TIME_ELAPSED_EXT, queryIds[entry * PASS_COUNT + passCursor])
    }

    fun endPass() {
        if (activeEntry == NO_ENTRY || passCursor >= PASS_COUNT) return
        GLES30.glEndQuery(TIME_ELAPSED_EXT)
        passCursor++
    }

    /**
     * 이 프레임이 어느 CSV 행에 해당하는지 알려 준다. `record()`가 돌려준 슬롯을 그대로
     * 넘긴다. 기록 중이 아니어서 행이 없으면 [SLOT_NONE]을 넘기면 되고, 그때는 결과를
     * 회수해 버리기만 한다.
     *
     * 계측하지 않은 프레임(패스스루·링 포화·비활성)에서 불러도 안전하다.
     */
    fun commitFrame(slot: Int) {
        val entry = activeEntry
        if (entry == NO_ENTRY) return
        activeEntry = NO_ENTRY
        if (passCursor < PASS_COUNT) {
            // 방어선. 패스 수가 모자란 채로 두면 그 query는 영원히 미해소로 남아 **링이
            // 막힌다**(그 뒤로 계측이 통째로 죽는다). 남은 것을 빈 query로 소진시키고
            // 이 프레임은 통째로 버린다 — 반쪽 값을 행에 쓰지 않는다.
            malformedFrames++
            while (passCursor < PASS_COUNT) {
                GLES30.glBeginQuery(TIME_ELAPSED_EXT, queryIds[entry * PASS_COUNT + passCursor])
                GLES30.glEndQuery(TIME_ELAPSED_EXT)
                passCursor++
            }
            entryGeneration[entry] = STALE_GENERATION
            entrySlot[entry] = SLOT_NONE
            return
        }
        entrySlot[entry] = slot
    }

    // ── 내부 ─────────────────────────────────────────────────────────────

    /**
     * `GL_GPU_DISJOINT_EXT`는 **읽으면 리셋된다.** 세워져 있으면 마지막으로 읽은 뒤부터
     * 지금까지의 모든 GPU 타이밍이 신뢰할 수 없다는 뜻이므로, in-flight query를 전량
     * 폐기한다(값을 버리되 **개수를 센다** — 조용히 사라지면 안 된다).
     */
    private fun checkDisjoint() {
        scratch[0] = 0
        GLES20.glGetIntegerv(GPU_DISJOINT_EXT, scratch, 0)
        if (needDisjointBaseline) {
            // 이 런의 첫 읽기다. 여기 서 있는 플래그는 **계측하지 않던 기간**의 것이므로
            // 이 런의 disjoint로 세지 않는다(읽었으니 리셋됐고, 다음 읽기부터가 이 런의
            // 구간이다). 이 시점에 in-flight가 있어도 전부 이전 세대라 데이터는 무관하다.
            needDisjointBaseline = false
            return
        }
        if (scratch[0] == 0) return
        // ⚠ 런 도중의 플래그는 전부 센다. 그 구간에는 in-flight query가 실제로 걸쳐 있어
        //   결과가 오염되기 때문이다 — 계측 공백이 아니라 진짜 disjoint 사건이다.
        disjointFrames++
        for (i in 0 until inFlight) {
            val entry = (head + i) % RING_DEPTH
            if (entryGeneration[entry] == generation) {
                discardedDisjointQueries += PASS_COUNT
            }
            entryGeneration[entry] = STALE_GENERATION
            entrySlot[entry] = SLOT_NONE
        }
    }

    /**
     * 해소된 엔트리를 앞에서부터 회수한다. **`GL_QUERY_RESULT_AVAILABLE`이 1인 것만**
     * 읽으므로 GPU를 기다리지 않는다.
     */
    private fun drainResolved() {
        while (inFlight > 0) {
            val entry = head
            val base = entry * PASS_COUNT
            var ready = true
            for (p in 0 until PASS_COUNT) {
                scratch[0] = 0
                GLES30.glGetQueryObjectuiv(
                    queryIds[base + p], GLES30.GL_QUERY_RESULT_AVAILABLE, scratch, 0
                )
                if (scratch[0] == 0) {
                    ready = false
                    break
                }
            }
            if (!ready) return
            for (p in 0 until PASS_COUNT) {
                scratch[0] = 0
                GLES30.glGetQueryObjectuiv(queryIds[base + p], GLES30.GL_QUERY_RESULT, scratch, 0)
                // ⚠ 결과는 **32비트**로만 받는다(`glGetQueryObjectuiv`. 64비트 진입점이
                //   없다). 32비트 ns는 약 4.29초까지라 프레임 단위 계측에는 충분하지만,
                //   **32비트에 안 담기는 결과가 어떻게 되는지는 GL 명세상 구현 정의다** —
                //   깔끔하게 감긴다는 보장이 없고 무엇이 나올지 우리는 모른다. 그 상황은
                //   이 앱의 다른 지표(프레임타임)에서 먼저 드러날 것이다.
                values[p] = scratch[0].toLong() and UINT32_MASK
            }
            head = (head + 1) % RING_DEPTH
            inFlight--
            consume(entry)
        }
    }

    /** [values]를 엔트리가 가리키는 행에 back-fill한다. 세대가 다르면 버린다. */
    private fun consume(entry: Int) {
        val slot = entrySlot[entry]
        val sameRun = entryGeneration[entry] == generation
        entrySlot[entry] = SLOT_NONE
        entryGeneration[entry] = STALE_GENERATION
        if (!sameRun) return
        resolvedFrames++
        resolvedQueries += PASS_COUNT
        for (p in 0 until PASS_COUNT) {
            if (values[p] > 0L) positiveSamples++ else zeroResultQueries++
        }
        if (slot == SLOT_NONE) return
        // PASS_COUNT=3 전제. 패스가 늘면 여기 인자도 함께 늘려야 한다.
        recorder.setGpuTiming(slot, values[0], values[1], values[2])
    }

    /**
     * **딱 한 번** 도는 실사용 프로브. 확장 문자열이 있어도 코어 진입점이 EXT 타깃을
     * 거부할 수 있으므로, 실제로 begin/end를 걸어 보고 `glGetError()`로 확인한다.
     * 여기서 통과하면 이후 프레임 경로에서는 `glGetError()`를 **한 번도 부르지 않는다**
     * (glGetError는 드라이버 왕복이라 hot path에 두면 그 자체가 측정을 오염시킨다).
     */
    private fun initialize() {
        if (extensionPresent != GlCapabilitiesProbe.TRUE) {
            disable(
                "GL_EXT_disjoint_timer_query가 없다(GL 능력 프로브: $extensionPresent) — " +
                    "GPU 패스 시간을 재지 않는다"
            )
            return
        }
        drainGlErrors()

        val probe = IntArray(1)
        GLES30.glGenQueries(1, probe, 0)
        var err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR) {
            disable("glGenQueries가 실패했다 (glGetError=${errString(err)})")
            return
        }

        GLES30.glBeginQuery(TIME_ELAPSED_EXT, probe[0])
        err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR) {
            // ⚠ query가 활성이 아니므로 glEndQuery를 부르지 않는다 — 부르면 에러가 하나 더
            //   나고, 원인 에러가 그 뒤에 묻힌다.
            beginQueryError = errString(err)
            GLES30.glDeleteQueries(1, probe, 0)
            drainGlErrors()
            disable(
                "코어 glBeginQuery가 GL_TIME_ELAPSED_EXT(0x88BF)를 거부했다 " +
                    "(glGetError=${errString(err)}). 확장 문자열은 있지만 " +
                    "android.opengl 진입점으로는 걸 수 없다 — JNI가 필요하다"
            )
            return
        }
        GLES30.glEndQuery(TIME_ELAPSED_EXT)
        err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR) {
            GLES30.glDeleteQueries(1, probe, 0)
            drainGlErrors()
            disable("glEndQuery(GL_TIME_ELAPSED_EXT)가 실패했다 (glGetError=${errString(err)})")
            return
        }

        // disjoint를 못 읽으면 값을 검증할 수단이 없다 → 값을 내지 않는다.
        // "검증 못 한 숫자"보다 "숫자 없음"이 낫다.
        scratch[0] = 0
        GLES20.glGetIntegerv(GPU_DISJOINT_EXT, scratch, 0)
        err = GLES20.glGetError()
        GLES30.glDeleteQueries(1, probe, 0)
        if (err != GLES20.GL_NO_ERROR) {
            drainGlErrors()
            disable(
                "glGetIntegerv(GL_GPU_DISJOINT_EXT)가 실패했다 " +
                    "(glGetError=${errString(err)}) — disjoint를 못 읽으면 결과의 유효성을 " +
                    "확인할 수 없어 계측하지 않는다"
            )
            return
        }

        GLES30.glGenQueries(queryIds.size, queryIds, 0)
        err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR) {
            queryIds.fill(0)
            disable("링용 glGenQueries(${queryIds.size})가 실패했다 (glGetError=${errString(err)})")
            return
        }
        head = 0
        inFlight = 0
        activeEntry = NO_ENTRY
        passCursor = 0
        state = State.READY
        disabledReason = null
        Log.i(TAG, "GPU timer 준비 완료 (query ${queryIds.size}개, 패스 $PASS_COUNT × 링 $RING_DEPTH)")
    }

    private fun disable(reason: String) {
        state = State.DISABLED
        disabledReason = reason
        Log.w(TAG, "GPU timer 비활성: $reason")
    }

    /** 이전 GL 호출이 남긴 에러를 비운다. 안 비우면 우리 에러인지 남의 에러인지 모른다. */
    private fun drainGlErrors() {
        var guard = 0
        while (GLES20.glGetError() != GLES20.GL_NO_ERROR && guard < ERROR_DRAIN_LIMIT) {
            guard++
        }
    }

    private fun errString(err: Int): String {
        val name = when (err) {
            GLES20.GL_INVALID_ENUM -> "GL_INVALID_ENUM"
            GLES20.GL_INVALID_VALUE -> "GL_INVALID_VALUE"
            GLES20.GL_INVALID_OPERATION -> "GL_INVALID_OPERATION"
            GLES20.GL_OUT_OF_MEMORY -> "GL_OUT_OF_MEMORY"
            GLES20.GL_INVALID_FRAMEBUFFER_OPERATION -> "GL_INVALID_FRAMEBUFFER_OPERATION"
            else -> "unknown"
        }
        return "$name(0x${Integer.toHexString(err)})"
    }

    companion object {
        const val TAG = "GpuTimerRing"

        /** 패스1·패스2·패스3. 늘리면 [consume]의 `setGpuTiming` 인자도 함께 늘린다. */
        const val PASS_COUNT = 3

        /**
         * 몇 프레임분의 query를 동시에 띄워 둘 것인가. GPU가 CPU보다 1~2프레임 뒤에
         * 있으므로 4면 회수가 밀리지 않는다. 늘리면 tail 미해소가 늘고, 줄이면
         * `skipped_ring_full_frames`가 는다 — 둘 다 계수로 드러난다.
         */
        const val RING_DEPTH = 4

        /** `GL_TIME_ELAPSED_EXT`. `android.opengl`에 상수가 없어 직접 쓴다. */
        const val TIME_ELAPSED_EXT = 0x88BF

        /** `GL_GPU_DISJOINT_EXT`. 마찬가지로 상수가 없다. */
        const val GPU_DISJOINT_EXT = 0x8FBB

        /** 계측 방식. `session.json`의 `gpu_timer.method`로 그대로 나간다. */
        const val METHOD =
            "GL_TIME_ELAPSED_EXT(0x88BF) via core glBeginQuery/glEndQuery + " +
                "glGetQueryObjectuiv(GL_QUERY_RESULT_AVAILABLE) 비차단 폴링. " +
                "android.opengl에 glQueryCounterEXT/glGetQueryObjectui64vEXT 진입점이 없어 " +
                "타임스탬프 방식은 쓸 수 없다(JNI 필요). 결과는 32비트로만 받으므로 약 " +
                "4.29초까지 담기고, 그보다 큰 결과가 어떻게 되는지는 **구현 정의**다"

        /** 채울 행이 없다. 스키마의 "없는 값 = -1"과 같은 뜻이다. */
        const val SLOT_NONE = -1

        private const val NO_ENTRY = -1
        private const val STALE_GENERATION = -1
        private const val UINT32_MASK = 0xFFFFFFFFL
        private const val ERROR_DRAIN_LIMIT = 16
    }
}

/**
 * 한 런의 GPU timer 실적. [GpuTimerRing.finishRun]이 만든 **불변 스냅샷**이며
 * `session.json`의 `gpu_timer` 블록이 여기서 나온다.
 */
class GpuTimerReport(
    /** GL 능력 프로브가 본 확장 유무. `"true"` / `"false"` / `"unknown"`. */
    val extensionPresent: String,
    /** 이 런에서 실제로 query를 걸었는가(arm과 프로브 결과에 따라 다르다). */
    val instrumented: Boolean,
    /** 이 런이 GPU 패스 시간 **표본을 냈는가**(0보다 큰 값이 하나라도 있었는가). */
    val supported: Boolean,
    val instrumentedFrames: Int,
    val resolvedFrames: Int,
    val resolvedQueries: Int,
    val positiveSamples: Int,
    /** 해소는 됐는데 값이 0ns였던 query 수. 0ms는 측정이 아니라 대개 드라이버의 침묵이다. */
    val zeroResultQueries: Int,
    /** 정지 시점에 아직 안 끝난 query 수(tail). 해당 행은 `-1`로 남는다. */
    val unresolvedQueries: Int,
    /** disjoint가 세워진 채로 읽힌 프레임 수. */
    val disjointFrames: Int,
    /** 그 때문에 버린 query 수. */
    val discardedDisjointQueries: Int,
    /** 링이 꽉 차 계측을 건너뛴 프레임 수. */
    val skippedRingFullFrames: Int,
    /** 패스 수가 모자란 채 끝난 프레임 수(방어선이 걸린 횟수). 0이어야 정상이다. */
    val malformedFrames: Int,
    /** `glBeginQuery` 직후 `glGetError()`가 낸 것. 성공이면 null. */
    val beginQueryError: String?,
    /** 계측을 끈 이유. 켜져 있으면 null. */
    val disabledReason: String?,
)
