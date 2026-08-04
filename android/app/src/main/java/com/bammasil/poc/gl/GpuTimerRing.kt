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
 * **매핑의 유일한 출처는 [RenderArm.gpuColumns]다**(`session.json`의
 * `render.passes[].gpu_column`도 같은 목록에서 나온다). arm마다 패스 수가 다르므로
 * ([RenderArm.DRAGO]는 ② 자리가 3단이라 5패스다) 이 클래스는 개수를 상수로 갖지 않고
 * [setPassPlan]으로 받는다. query는 항상 [MAX_PASS_COUNT]칸을 잡아 두고 앞에서부터 쓴다 —
 * arm을 바꿔도 재할당하지 않기 위해서다.
 * ```
 * 3패스 arm : stage_b_ms, stage_d_ms, gpu_present_ms
 * drago     : stage_b_ms, stage_d_analyze_ms, stage_d_build_ms, stage_d_apply_ms, gpu_present_ms
 * ```
 *
 * ### 계측 모드 두 가지 (arm이 고른다)
 * 위 제약 1(중첩 불가) 때문에 "패스별 query"와 "프레임 전체 query"를 같이 걸 수 없다.
 * 그래서 **arm으로 가르고**([RenderArm.usesSingleFrameQuery]) 이 클래스가 모드를 갖는다.
 *
 *  - [INSTRUMENTATION_PER_PASS] — 지금까지의 방식. 패스마다 query 하나.
 *  - [INSTRUMENTATION_SINGLE_FRAME_QUERY] — **프레임 전체를 query 하나로** 감싼다.
 *    [beginPass]는 **커서가 0일 때만** `glBeginQuery`를 걸고, [endPass]는 **마지막 패스일
 *    때만** `glEndQuery`를 건다. 나머지 호출은 커서만 올린다. 그래서 **draw 함수가 지금처럼
 *    패스마다 begin/end를 불러도** 결과적으로 프레임을 감싼 query 하나가 된다 —
 *    **draw 함수를 한 줄도 고치지 않는 것이 이 설계의 목적이다.**
 *    회수하는 값도 **1개**이고 `gpu_frame_ms` 한 열에 들어간다.
 *
 * 🔴 두 모드 모두 **"draw가 부른 begin/end 횟수"와 "arm이 선언한 렌더 패스 수"가 다르면
 * 그 프레임을 통째로 버린다**([commitFrame]의 방어선). 단일 query 모드에서는 열이 1개라
 * 어긋나도 CSV 모양이 멀쩡해 보이므로, 오히려 여기서 조용히 통과시키면 안 된다.
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

    /**
     * 이번 arm이 프레임당 거는 query 수 = [RenderArm.gpuColumns]의 개수.
     * 단일 query 모드에서는 **항상 1**이다(렌더 패스 수가 아니다 — [renderPassCount]).
     * [setPassPlan]으로만 바뀌며, **이미 떠 있는 엔트리에는 소급되지 않는다**
     * ([entryQueryCount] 참고).
     */
    private var queryCount = DEFAULT_PASS_COUNT

    /**
     * 이번 arm의 draw 함수가 프레임당 부르는 [beginPass]/[endPass] 횟수
     * = [RenderArm.renderPassCount]. 패스별 모드에서는 [queryCount]와 같고, 단일 query
     * 모드에서는 **다르다**(열 1 · 렌더 패스 3~9).
     */
    private var renderPassCount = DEFAULT_PASS_COUNT

    /** 이번 arm이 프레임 전체를 query 하나로 감싸는가. [setPassPlan]이 정한다. */
    private var singleFrameQuery = false

    /**
     * 단일 query 모드에서 **지금 query가 열려 있는 엔트리**. [NO_ENTRY]면 열린 query가 없다.
     *
     * 왜 boolean이 아닌가: 도달 불가 경로(begin만 불리고 end가 안 불린 채 프레임이 끝나는
     * 경우)에서 **어느 엔트리의 query를 닫고 버려야 하는지**를 알아야 하기 때문이다.
     * 열린 채로 두면 다음 `glBeginQuery`가 GL_INVALID_OPERATION이고 링이 통째로 막힌다.
     */
    private var openFrameQueryEntry = NO_ENTRY

    /**
     * [setPassPlan]이 요청을 **거부한 이유**. null이 아니면 이
     * 링은 계측을 하지 않는다([beginFrame]이 곧바로 false를 준다).
     *
     * ⚠ 예전에는 초과 요청을 경고 로그만 남기고 무시했는데, 그러면 **직전 arm의 패스 수로
     * 계속 재면서** CSV 열은 새 arm의 개수로 붙어 열과 query가 한 칸씩 어긋난 채 그럴듯한
     * 숫자가 나온다. 그래서 '조용히 이전 값 유지'가 아니라 **이 arm에서는 아무 숫자도 내지
     * 않는다**로 바꿨다: 계측 프레임이 0이면 `GpuTimerReport.instrumented`가 false이고,
     * `writeCsv(includeGpuColumns=false)`라 CSV에 GPU 열 자체가 붙지 않는다(열이 없는 것과
     * 전부 -1인 것은 하네스에서 다른 뜻이다 — `FrameLogRecorder.CSV_HEADER` 주석).
     * 이유는 `session.json`의 `gpu_timer.disabled_reason`으로 그대로 나간다.
     *
     * 유효한 개수를 다시 받으면 해제된다 — 정상 arm으로 돌아온 런까지 벌줄 이유가 없다.
     */
    private var passCountRejection: String? = null

    /** [MAX_PASS_COUNT] × [RING_DEPTH]개를 **한 번에** 잡는다. 프레임당 할당 0. */
    private val queryIds = IntArray(MAX_PASS_COUNT * RING_DEPTH)

    /** 엔트리 i가 채워야 할 CSV 행 슬롯. [SLOT_NONE]이면 채울 행이 없다. */
    private val entrySlot = IntArray(RING_DEPTH) { SLOT_NONE }

    /** 엔트리 i를 건 런의 세대. [generation]과 다르면 결과를 버린다. */
    private val entryGeneration = IntArray(RING_DEPTH) { STALE_GENERATION }

    /**
     * 엔트리 i를 걸 때의 **query 수**. arm이 바뀌면 [queryCount]가 달라지는데, 아직 떠 있는
     * 엔트리를 새 개수로 회수하면 **걸지도 않은 query 이름을 읽어**(GL_INVALID_OPERATION)
     * 링이 통째로 막힌다. 그래서 회수는 엔트리가 기억하는 개수로 한다.
     */
    private val entryQueryCount = IntArray(RING_DEPTH)

    /**
     * 엔트리 i를 걸 때의 **렌더 패스 수**([renderPassCount]). [entryQueryCount]와 같은 이유로
     * 엔트리가 따로 기억한다 — [commitFrame]의 방어선이 이 값과 커서를 대조한다.
     */
    private val entryRenderPassCount = IntArray(RING_DEPTH)

    /** 엔트리 i를 걸 때의 모드. 같은 이유로 엔트리가 기억한다(arm 전환에 소급되지 않는다). */
    private val entrySingleFrameQuery = BooleanArray(RING_DEPTH)

    /** FIFO 링. query는 건 순서대로 해소되므로 head부터 보면 된다. */
    private var head = 0
    private var inFlight = 0

    /** 이번 프레임에 쓰는 엔트리. [NO_ENTRY]면 이번 프레임은 계측하지 않는다. */
    private var activeEntry = NO_ENTRY

    /** 이번 프레임에서 지금까지 끝낸 패스 수. */
    private var passCursor = 0

    /** `glGetQueryObjectuiv` / `glGetIntegerv` 공용. 프레임당 할당을 만들지 않기 위해서다. */
    private val scratch = IntArray(1)

    /** 한 엔트리에서 읽어 낸 패스별 ns. 재사용한다(프레임당 할당 0). */
    private val values = LongArray(MAX_PASS_COUNT)

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
     * arm이 바뀌었을 때(GL 스레드) **프레임당 계획**을 알려 준다. **측정 중에는 부르지
     * 않는다** — arm 전환 자체가 측정 중에 금지돼 있다(UI에서 스피너를 잠근다).
     *
     * 아직 떠 있는 엔트리는 [entryQueryCount]·[entryRenderPassCount]에 자기 값을 들고 있으므로
     * 여기서 건드리지 않는다. 값 자체는 [RenderArm]에서 오며 이 클래스가 정하지 않는다.
     *
     * 🔴 **두 수를 따로 받아 대조한다.** 패스별 모드에서는 렌더 패스 하나가 열 하나이지만
     * 단일 query 모드에서는 **렌더 패스 3~9개에 열 1개**다. 한 값으로 뭉치면 링이 첫 패스만
     * 감싸고도 그럴듯한 숫자를 낸다.
     *
     * ⚠ 어긋나거나 범위 밖이면 **무시하지 않고 이 링을 계측 불가로 만든다**
     * ([passCountRejection]). 무시하면 이전 arm의 값으로 계속 재고 열은 새 arm 것이 붙어
     * 조용히 어긋난다.
     *
     * @param renderPasses draw 함수가 프레임당 부르는 [beginPass] 횟수([RenderArm.renderPassCount])
     * @param queryColumns 이 arm이 CSV에 싣는 GPU 열 수(= 프레임당 거는 query 수)
     * @param singleFrameQuery 프레임 전체를 query 하나로 감싸는가([RenderArm.usesSingleFrameQuery])
     */
    fun setPassPlan(renderPasses: Int, queryColumns: Int, singleFrameQuery: Boolean) {
        val rejection = when {
            renderPasses <= 0 || queryColumns <= 0 ->
                "이 arm이 렌더 패스 $renderPasses · GPU 열 $queryColumns 를 선언했다 — " +
                    "둘 다 1 이상이어야 한다. 이 arm에서는 GPU 시간을 아예 내지 않는다"
            singleFrameQuery && queryColumns != 1 ->
                "프레임 단일 query arm인데 GPU 열이 ${queryColumns}개다(1이어야 한다) — " +
                    "query는 프레임당 하나뿐이므로 나머지 열은 영원히 채워지지 않는다. " +
                    "RenderArm.gpuColumns를 고치고 다시 측정할 것"
            !singleFrameQuery && queryColumns != renderPasses ->
                "패스별 계측 arm인데 렌더 패스 $renderPasses 와 GPU 열 $queryColumns 가 " +
                    "다르다 — 패스 하나가 열 하나라는 전제가 깨지면 열과 query가 한 칸씩 " +
                    "어긋난 채 그럴듯한 숫자가 나온다. 이 arm에서는 GPU 시간을 내지 않는다"
            queryColumns > MAX_PASS_COUNT ->
                "이 arm이 프레임당 $queryColumns 패스를 요구하는데 링은 최대 " +
                    "$MAX_PASS_COUNT 패스분(GpuTimerRing.MAX_PASS_COUNT)만 잡아 둔다 — " +
                    "이전 arm의 패스 수로 계속 재면 열과 query가 어긋난 채 그럴듯한 숫자가 " +
                    "나오므로 이 arm에서는 GPU 패스 시간을 아예 내지 않는다. " +
                    "MAX_PASS_COUNT를 올리고 다시 측정할 것"
            else -> null
        }
        if (rejection != null) {
            passCountRejection = rejection
            Log.e(
                TAG,
                "패스 계획 거부 (렌더 패스=$renderPasses, 열=$queryColumns, " +
                    "단일 query=$singleFrameQuery): $rejection"
            )
            return
        }
        this.renderPassCount = renderPasses
        this.queryCount = queryColumns
        this.singleFrameQuery = singleFrameQuery
        // 유효한 arm으로 돌아왔다. 이전 거부를 이 런까지 끌고 가지 않는다.
        passCountRejection = null
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
        entryQueryCount.fill(0)
        entryRenderPassCount.fill(0)
        entrySingleFrameQuery.fill(false)
        head = 0
        inFlight = 0
        activeEntry = NO_ENTRY
        passCursor = 0
        // 컨텍스트가 죽었으므로 열린 query도 함께 사라졌다. GL 호출 없이 상태만 되돌린다.
        openFrameQueryEntry = NO_ENTRY
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
        // 거부된 arm이면 query 객체도 만들지 않는다 — 쓰지 않을 자원이고, 만들어 두면
        // 로그의 "이번 arm의 패스 수"가 실제와 다른 값으로 남는다.
        if (passCountRejection != null) return
        if (state == State.UNINITIALIZED) initialize()
    }

    // ── 런 경계 ──────────────────────────────────────────────────────────

    /**
     * 측정 시작 직전(GL 스레드). 계수를 런별로 되돌리고 이전 런의 결과를 무효화하며,
     * **열린 채 넘어온 query가 있으면 닫는다**(런 경계의 불변조건).
     */
    fun beginRun() {
        // 열린 채 넘어온 query를 런 경계에서 닫는다. [beginFrame]의 선행 정리와 중복이지만
        // **런 경계의 불변조건("이 런은 열린 query 없이 시작한다")을 이 함수 안에서 닫는다** —
        // [releaseGl]·[initialize]는 이미 그렇게 하는데 여기만 빠져 있었고, 그러면 이 함수의
        // 정합성이 다른 함수의 구현에 의존한다.
        // ⚠ **필드만 NO_ENTRY로 지우면 안 된다.** 지우면 실제로 활성인 query를 잊어버려
        //    [drainResolved]의 glGetQueryObjectuiv와 다음 glBeginQuery가 둘 다
        //    GL_INVALID_OPERATION이고 링이 통째로 막힌다. 컨텍스트가 살아 있는 GL 스레드이므로
        //    (호출부는 glView.queueEvent) 여기서 닫는 것이 맞다.
        // ⚠ 이 프레임은 **이전 런의 것**이라 malformedFrames에 세지 않는다(바로 아래에서 0으로
        //    되돌리고, 이전 런의 보고는 이미 [finishRun]이 냈다). 현재 도달 불가 경로다.
        if (openFrameQueryEntry != NO_ENTRY) {
            GLES30.glEndQuery(TIME_ELAPSED_EXT)
            entryGeneration[openFrameQueryEntry] = STALE_GENERATION
            entrySlot[openFrameQueryEntry] = SLOT_NONE
            openFrameQueryEntry = NO_ENTRY
            Log.w(TAG, "런 시작 시 열린 query가 남아 있었다 — 닫고 그 엔트리를 버렸다")
        }
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
            val entry = (head + i) % RING_DEPTH
            if (entryGeneration[entry] == generation) {
                unresolved += entryQueryCount[entry]
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
            passesPerFrame = queryCount,
            renderPassesPerFrame = renderPassCount,
            instrumentation = if (singleFrameQuery) {
                INSTRUMENTATION_SINGLE_FRAME_QUERY
            } else {
                INSTRUMENTATION_PER_PASS
            },
            method = if (singleFrameQuery) METHOD_SINGLE_FRAME_QUERY else METHOD,
            disjointFrames = disjointFrames,
            discardedDisjointQueries = discardedDisjointQueries,
            skippedRingFullFrames = skippedRingFullFrames,
            malformedFrames = malformedFrames,
            beginQueryError = beginQueryError,
            // 패스 수 거부는 GL 실패보다 **먼저** 보고한다. 거부된 arm은 initialize조차
            // 돌지 않아 disabledReason이 비어 있고, 그러면 "왜 GPU 열이 없는가"의 답이
            // session.json에서 사라진다.
            disabledReason = passCountRejection ?: disabledReason,
        )
    }

    // ── 프레임 경로 (렌더 스레드 hot path) ────────────────────────────────

    /**
     * 3패스를 그리기 직전에 부른다. `true`면 이번 프레임에 query를 건다.
     * **패스스루 arm에서는 절대 불리지 않는다** — 호출부가 arm으로 먼저 갈린다.
     */
    fun beginFrame(): Boolean {
        if (state == State.DISABLED) return false
        // 패스 수를 거부한 arm이다. 여기서 걸면 열과 query가 어긋난다([passCountRejection]).
        if (passCountRejection != null) return false
        if (state == State.UNINITIALIZED) {
            initialize()
            if (state != State.READY) return false
        }
        // 🔴 열린 채 남은 query가 있으면 **가장 먼저** 닫는다. 활성 query가 있는 상태로
        //    glGetQueryObjectuiv를 부르면 GL_INVALID_OPERATION이고, 다음 glBeginQuery도
        //    실패해 링이 통째로 막힌다. 현재 draw 함수들은 begin/end가 짝을 이루므로 이
        //    경로는 도달 불가지만, 도달했다면 그 프레임은 잘못 잰 것이므로 버리고 센다.
        if (openFrameQueryEntry != NO_ENTRY) {
            GLES30.glEndQuery(TIME_ELAPSED_EXT)
            entryGeneration[openFrameQueryEntry] = STALE_GENERATION
            entrySlot[openFrameQueryEntry] = SLOT_NONE
            openFrameQueryEntry = NO_ENTRY
            malformedFrames++
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
        entryQueryCount[activeEntry] = queryCount
        entryRenderPassCount[activeEntry] = renderPassCount
        entrySingleFrameQuery[activeEntry] = singleFrameQuery
        passCursor = 0
        instrumentedFrames++
        return true
    }

    /**
     * 패스 하나의 GL 명령을 감싼다. [beginFrame]이 true를 준 프레임에서만 부른다.
     *
     * **단일 query 모드에서는 커서가 0일 때만 실제로 건다.** 나머지 호출은 아무 GL 명령도
     * 내지 않고 돌아간다 — 그래서 draw 함수가 패스마다 불러도 query는 **정확히 하나**다.
     * 쓰는 query 이름도 항상 엔트리의 **0번 칸 하나**라, 커서가 어긋나도 다른 칸을 건드릴
     * 수 없다.
     *
     * ⚠ 커서를 올리는 쪽은 [endPass]다(두 모드 공통). 여기서 올리면 한 패스가 두 칸을 쓴다.
     */
    fun beginPass() {
        val entry = activeEntry
        if (entry == NO_ENTRY || passCursor >= entryRenderPassCount[entry]) return
        if (entrySingleFrameQuery[entry]) {
            if (passCursor == 0 && openFrameQueryEntry == NO_ENTRY) {
                GLES30.glBeginQuery(TIME_ELAPSED_EXT, queryIds[entry * MAX_PASS_COUNT])
                openFrameQueryEntry = entry
            }
            return
        }
        GLES30.glBeginQuery(TIME_ELAPSED_EXT, queryIds[entry * MAX_PASS_COUNT + passCursor])
    }

    /** **단일 query 모드에서는 마지막 패스일 때만** 닫는다. 나머지는 커서만 올린다. */
    fun endPass() {
        val entry = activeEntry
        if (entry == NO_ENTRY || passCursor >= entryRenderPassCount[entry]) return
        if (entrySingleFrameQuery[entry]) {
            if (openFrameQueryEntry == entry && passCursor == entryRenderPassCount[entry] - 1) {
                GLES30.glEndQuery(TIME_ELAPSED_EXT)
                openFrameQueryEntry = NO_ENTRY
            }
            passCursor++
            return
        }
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
        // 🔴 대조 대상은 **렌더 패스 수**다(열 수가 아니다). 단일 query 모드는 열이 1개라
        //    열 수로 대조하면 첫 패스만 감싸고도 통과해 버린다.
        val expected = entryRenderPassCount[entry]
        // 🔴 단일 query 모드는 **커서만으로 판정할 수 없다.** 커서는 [endPass]가 무조건
        //    올리지만 query는 [beginPass]가 커서 0에서만 걸고, 그 시점에 다른 엔트리의
        //    query가 열려 있으면 **걸지 않고 돌아간다.** 그러면 아무 query도 걸지 않은 채
        //    커서가 expected까지 도달하고, [drainResolved]가 **begin된 적 없는 query 이름**을
        //    읽어 GL_INVALID_OPERATION이 난다 — hot path에 glGetError가 없으므로(의도된
        //    설계다) 그 뒤로 링이 **조용히 통째로 막힌다.** 그래서 정상 경로에서도
        //    "query가 열렸다 닫혔는가"를 대조한다.
        //    판정: 닫히기까지 정상이면 [openFrameQueryEntry]가 NO_ENTRY다. 이 엔트리면
        //    열렸는데 안 닫힌 것이고, 다른 엔트리면 우리 query가 아예 열리지 않은 것이다.
        //    ⚠ **현재는 도달 불가다** — [beginFrame]이 프레임 시작에서 열린 query를 먼저
        //    닫아 NO_ENTRY로 만들고, 현재 draw 함수들은 begin/end가 짝을 이룬다. 도달 불가한
        //    상태를 조용히 통과시키지 않기 위한 대조이며, 처리는 아래 방어선과 **같다**.
        val queryNotClosed = entrySingleFrameQuery[entry] && openFrameQueryEntry != NO_ENTRY
        if (passCursor < expected || queryNotClosed) {
            // 방어선. 패스 수가 모자란 채로 두면 그 query는 영원히 미해소로 남아 **링이
            // 막힌다**(그 뒤로 계측이 통째로 죽는다). 남은 것을 빈 query로 소진시키고
            // 이 프레임은 통째로 버린다 — 반쪽 값을 행에 쓰지 않는다.
            // (위 queryNotClosed로 들어온 경우도 결말이 같다: 걸리지 않은/닫히지 않은 query를
            //  그대로 두면 다음 회수가 GL_INVALID_OPERATION이고 링이 막힌다.)
            malformedFrames++
            if (entrySingleFrameQuery[entry]) {
                // 단일 query 모드도 **같은 방식으로 버린다.** 다만 소진시킬 것이 하나뿐이다:
                // 열려 있으면 닫고, 한 번도 열리지 않았으면 빈 query로 열었다 닫는다
                // (begin된 적 없는 query 이름을 뒤에서 읽으면 GL_INVALID_OPERATION이라
                // 링이 통째로 막힌다 — 위와 같은 이유다).
                if (openFrameQueryEntry != entry) {
                    GLES30.glBeginQuery(TIME_ELAPSED_EXT, queryIds[entry * MAX_PASS_COUNT])
                }
                GLES30.glEndQuery(TIME_ELAPSED_EXT)
                openFrameQueryEntry = NO_ENTRY
            } else {
                while (passCursor < expected) {
                    GLES30.glBeginQuery(
                        TIME_ELAPSED_EXT, queryIds[entry * MAX_PASS_COUNT + passCursor]
                    )
                    GLES30.glEndQuery(TIME_ELAPSED_EXT)
                    passCursor++
                }
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
                discardedDisjointQueries += entryQueryCount[entry]
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
            val base = entry * MAX_PASS_COUNT
            // ⚠ 이 엔트리를 **걸 때의** 개수로 읽는다. 현재 passCount로 읽으면 arm 전환
            //   직후 걸지도 않은 query 이름을 읽게 된다.
            val count = entryQueryCount[entry]
            var ready = true
            for (p in 0 until count) {
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
            for (p in 0 until count) {
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
            consume(entry, count)
        }
    }

    /** [values]를 엔트리가 가리키는 행에 back-fill한다. 세대가 다르면 버린다. */
    private fun consume(entry: Int, count: Int) {
        val slot = entrySlot[entry]
        val sameRun = entryGeneration[entry] == generation
        entrySlot[entry] = SLOT_NONE
        entryGeneration[entry] = STALE_GENERATION
        if (!sameRun) return
        resolvedFrames++
        resolvedQueries += count
        for (p in 0 until count) {
            if (values[p] > 0L) positiveSamples++ else zeroResultQueries++
        }
        if (slot == SLOT_NONE) return
        // 열 순서는 RenderArm.gpuColumns 그대로다 — 개수가 arm마다 다르므로 배열로 넘긴다
        // (재사용 배열이라 프레임당 할당은 여전히 0이다).
        recorder.setGpuTiming(slot, values, count)
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
        openFrameQueryEntry = NO_ENTRY
        state = State.READY
        disabledReason = null
        Log.i(
            TAG,
            "GPU timer 준비 완료 (query ${queryIds.size}개 = 최대 $MAX_PASS_COUNT 패스 × " +
                "링 $RING_DEPTH, 이번 arm의 query 수 $queryCount / 렌더 패스 " +
                "$renderPassCount, 모드=" +
                "${if (singleFrameQuery) INSTRUMENTATION_SINGLE_FRAME_QUERY else INSTRUMENTATION_PER_PASS})"
        )
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

        /**
         * query를 미리 잡아 두는 최대 패스 수.
         *
         * ⚠ **현재 가장 긴 arm에 딱 맞추지 않는다.** 컴퓨트 arm 3종이 정확히 5패스라
         * 여유가 0이었는데, ② 후보에 붙을 수 있는 전처리 한 단(예: bilateral)이 6패스가
         * 되고 그러면 [setPassPlan]이 거부해 그 arm은 GPU 열을 통째로 잃는다. 링의 비용은
         * query 객체 몇 개(= 이 값 × [RING_DEPTH])뿐이므로 **여유를 두는 쪽이 싸다.**
         *
         * 🔴 **8이던 값을 12로 올렸다.** 8을 정할 때 적어 둔 *"8은 5패스 + 분리형 필터
         * 2패스 + ④ 오버레이 1패스까지 담는다"*는 **사실이 아니었다** — 조합 arm
         * ([RenderArm.DRAGO_CLAHE_CHAIN])이 혼자 8패스를 다 써서 여유가 0이 됐고, 그래서
         * `bf` 한 패스를 얹을 수 없는 상태였다(위 규칙을 적어 두고도 규칙대로 두지 못한
         * 것이다). 12는 [RenderArm.DRAGO_CLAHE_CHAIN_BF]의 9패스에 ④ 오버레이 한 패스와
         * `+ts`(`INTERFACES.md` §B-4가 ☐다) 여유까지 담는다. 비용은 query 객체
         * 12 × [RING_DEPTH] = 48개를 컨텍스트당 **1회** 만드는 것뿐이다.
         *
         * 그래도 넘는 arm이 생기면 [setPassPlan]이 **거부하고 계측을 끈다**(무시하지
         * 않는다). 그때 할 일은 여기를 올리고 다시 재는 것이다.
         */
        const val MAX_PASS_COUNT = 12

        /** arm을 고르기 전의 기본값. 3패스 골격 arm의 패스 수다. */
        const val DEFAULT_PASS_COUNT = 3

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

        /** `session.json`의 `gpu_timer.instrumentation` 값. 패스마다 query 하나. */
        const val INSTRUMENTATION_PER_PASS = "per_pass"

        /** `session.json`의 `gpu_timer.instrumentation` 값. 프레임 전체에 query 하나. */
        const val INSTRUMENTATION_SINGLE_FRAME_QUERY = "single_frame_query"

        /** 두 모드가 공유하는 부분(query를 거는 수단과 그 한계). */
        private const val METHOD_TRANSPORT =
            "GL_TIME_ELAPSED_EXT(0x88BF) via core glBeginQuery/glEndQuery + " +
                "glGetQueryObjectuiv(GL_QUERY_RESULT_AVAILABLE) 비차단 폴링. " +
                "android.opengl에 glQueryCounterEXT/glGetQueryObjectui64vEXT 진입점이 없어 " +
                "타임스탬프 방식은 쓸 수 없다(JNI 필요). 결과는 32비트로만 받으므로 약 " +
                "4.29초까지 담기고, 그보다 큰 결과가 어떻게 되는지는 **구현 정의**다"

        /**
         * 패스별 계측의 `gpu_timer.method`.
         * ⚠ 단일 query 모드에서는 이 문장을 쓰지 않는다 — 사실과 다르다
         * ([METHOD_SINGLE_FRAME_QUERY]). 어느 문장이 나갈지는 `GpuTimerReport.method`가 정한다.
         */
        const val METHOD =
            "패스별 순차 query — 렌더 패스 하나를 query 하나로 감싼다(프레임당 query = " +
                "RenderArm.gpuColumns의 개수). GL_TIME_ELAPSED는 중첩되지 않으므로 프레임 " +
                "전체 query와 같이 걸 수 없다. " + METHOD_TRANSPORT

        /** 프레임 단일 query 계측의 `gpu_timer.method`. */
        const val METHOD_SINGLE_FRAME_QUERY =
            "프레임 단일 query — **프레임 전체를 query 하나로** 감싼다(프레임당 query = 1, " +
                "열은 gpu_frame_ms 하나). 링이 첫 패스의 beginPass에서만 glBeginQuery를, " +
                "마지막 패스의 endPass에서만 glEndQuery를 걸고 나머지 호출은 커서만 올린다 — " +
                "**draw 함수는 패스별 계측 arm과 글자 그대로 같은 코드**다. 패스별 분해는 " +
                "이 모드에서 나오지 않는다(GL_TIME_ELAPSED가 중첩되지 않아 둘을 같이 걸 수 " +
                "없고, 그래서 arm으로 갈랐다). " + METHOD_TRANSPORT

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
    /**
     * 이 런의 arm이 프레임당 건 query 수 = `RenderArm.gpuColumns`의 개수.
     * 단일 query 모드에서는 **1**이다(렌더 패스 수가 아니다 — [renderPassesPerFrame]).
     */
    val passesPerFrame: Int,
    /**
     * 이 런의 draw 함수가 프레임당 부른 `beginPass` 횟수 = `RenderArm.renderPassCount`.
     * 패스별 모드에서는 [passesPerFrame]과 같고, 단일 query 모드에서는 다르다.
     */
    val renderPassesPerFrame: Int,
    /** `per_pass` / `single_frame_query`. 이 런이 **어느 계측 방식이었는가**. */
    val instrumentation: String,
    /** 그 방식을 서술한 문장. 모드에 맞는 것이 나가야 한다(`GpuTimerRing.METHOD*`). */
    val method: String,
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
