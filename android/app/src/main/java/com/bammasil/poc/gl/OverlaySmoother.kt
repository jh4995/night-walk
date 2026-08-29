package com.bammasil.poc.gl

import com.bammasil.poc.detect.DetectOverlaySnapshot

/**
 * ④ **H칸 — 좌표 평활·hold.** 게시된 탐지 결과 하나를 표시 프레임마다 **연속적인** 그릴 목록으로
 * 바꾼다. 버짓 H칸이며 비용은 `stage_h_ms`(CPU 벽시계)로 나간다.
 *
 * ## 왜 필요한가
 *
 * 탐지 주기는 실측 약 3.4Hz이고 표시는 30FPS다 — 같은 게시가 약 9프레임에 걸쳐 쓰이므로,
 * 아무것도 하지 않으면 박스가 9프레임 고정돼 있다가 **한 프레임에 급변한다.** 상류가 그것을
 * *"박스가 튀어 깜빡임처럼 보인다"*고 적었고, 대상 사용자가 광과민이라 깜빡임은 **안전 문제**다
 * ([RenderArm.HIGHLIGHT_NO_BLINK_REASON]).
 *
 * ## 🔴 상태 기계 — 무엇을 그릴지는 **게시 단위**로 정한다
 *
 * 트랙은 두 상태를 가진다: **`PENDING`**(확인 중 · **그리지 않는다**) / **`ACTIVE`**(그린다).
 *
 * | 전이 | 조건 | 적용 대상 |
 * |---|---|---|
 * | 승격 | 최근 [RenderArm.OVERLAY_ENTRY_WINDOW_PUBLISHES_MEASUREMENT_VALUE]게시 중 [RenderArm.OVERLAY_ENTRY_HITS_REQUIRED_MEASUREMENT_VALUE]회 지지 | 🔴 `PENDING`에만 |
 * | 폐기 | 그 창(= 탄생 게시 포함 [RenderArm.OVERLAY_ENTRY_WINDOW_PUBLISHES_MEASUREMENT_VALUE]게시)이 다 지나도 지지가 모자람 | 🔴 `PENDING`에만 |
 * | 해제 | 연속 [RenderArm.OVERLAY_HOLD_PUBLISHES_MEASUREMENT_VALUE]회 미지지 | 🔴 `ACTIVE`에만 |
 *
 * 🔴 **적용 범위가 갈려 있는 것이 설계의 핵심이다.** 해제(k=1)를 `PENDING`에도 걸면 `PENDING`이
 * 1회 미지지에 죽어 "창 안에서 [RenderArm.OVERLAY_ENTRY_HITS_REQUIRED_MEASUREMENT_VALUE]회"가 "연속 [RenderArm.OVERLAY_ENTRY_HITS_REQUIRED_MEASUREMENT_VALUE]회"와
 * 같아진다 — 진입 규칙이 사문화된다.
 *
 * 🔴 **TTL의 단위는 표시 프레임이 아니라 게시다.** 그래서 이 정책은 표시 FPS와 탐지 주기 N에
 * **무관**하다(옛 프레임 단위 hold는 둘 다에 딸려 있었고, 게시 하나가 hold보다 오래 머물면
 * **아직 그 게시를 쓰는 중에도** 박스가 사라졌다 — `excess<0` 35프레임으로 실측됐다:
 * run_ts=20260828_185222 = 08-24 런 20260824_212554 재분석).
 * ⚠ 대신 **벽시계 잔상 길이는 여전히 `detect_cadence_ms`에 딸린다.**
 *
 * ## 🔴 깜빡임을 만들지 않는다 — 그것이 이 파일의 제약이다
 *
 * 전문은 [RenderArm.OVERLAY_NO_FLICKER_DESIGN]에 있고 같은 문장이 `session.json`으로 나간다.
 * 요지 넷: **점멸·펄스·알파 변조가 없다** · **한 게시를 쓰는 동안(≈9프레임) 박스를 0으로
 * 떨어뜨리지 않는다** · **확인되지 않은 트랙을 그리지 않으므로 1회짜리 오탐이 번쩍이지
 * 않는다** · **크기·위치는 IIR로만 움직인다.**
 *
 * ⚠ **이 설계는 값을 지불한다 — 맞바꿈을 숨기지 않는다.** 진입이 `PENDING`만큼 늦고(최대
 * 진입창−1게시), 해제가 k=1이라 **탐지가 1게시 끊기면 박스가 사라졌다가 다시 뜨는 데 2게시가
 * 걸린다.** 옛 hold(18프레임)가 그 끊김을 메우고 있었다.
 * 🔴 **실측 예고(run_ts=20260828_185222 = 08-24 런 20260824_212554 재분석)**:
 * 고립 끊김 **4.349회/분** · 0인 게시 비율 **0.4345** ·
 * **탐지 양성 버스트 길이 `positive_run_publishes` p50=2.0게시(n=28, min 1)** — 버스트의
 * 절반이 2게시 이하인데 진입이 2회 지지를 요구하므로 **1게시짜리 버스트는 한 번도 그려지지
 * 않고 2게시짜리는 최대 1게시만 보인다.** 판정은 하네스의
 * `overlay_ghost.pending`·`overlay.flicker`가 사후에 한다.
 *
 * 🔴 **게시가 그 박스를 담지 않으면 사라진다 — 그것은 깜빡임이 아니다.** 낡은 위험물 위치를
 * 현재인 것처럼 계속 보여 주는 것이 야간 보행에서 더 위험하고, 사라짐은 **탐지가 끊긴 사실의
 * 정직한 표시**다(빠른 on/off가 아니다). 하네스가 그것을 `drew_then_stopped`로 지목하면
 * **그 지목이 맞는 것이다.**
 *
 * ⚠ 이 파일은 "깜빡이지 않았다"를 **주장하지 않는다.** 그 주장의 유일한 기계 근거는 하네스의
 * `overlay.flicker` 블록이다(`overlay_boxes` 열의 `>0→0→>0` 전이 + 0 비율 + 뒷자락).
 *
 * ## 좌표 공간 — **NDC에서 돈다**
 *
 * 스냅샷의 박스는 ① 센서 좌표이고, 들어오는 자리에서 [OverlayCoordMap]으로 **처리 해상도 FBO의
 * NDC**로 한 번 바꾼 뒤 그 공간에서 연결·평활한다. 두 공간은 축별 스케일 + 이동이라
 * **IoU가 보존되고**(모든 면적이 같은 배수로 늘어난다) 선형 보간도 보존된다 — 그래서 뜻이 바뀌지
 * 않으면서 하류(정점 버퍼)가 변환 없이 쓸 수 있다.
 *
 * 🔴 [OverlayCoordMap.canMap]이 false면 **값을 지어내지 않는다** — 그 프레임은 박스 0개이며
 * ([lastMapFailures]로 세어) `session.json`에 남는다.
 *
 * ## 정책값은 전부 임의 측정값이다
 *
 * 진입창·진입 히트 수 · hold 게시 수 · 연결 조건(IoU 임계) · IIR 계수 · 상한.
 * 🔴 **INTERFACES.md에 이 항목 자체가
 * 없다** — 계약의 `☐`가 아니라 **항목 부재**다. 전문은
 * [RenderArm.OVERLAY_SMOOTHING_PROVENANCE]이며 같은 문장이 `session.json`으로 나간다.
 *
 * ## 할당
 *
 * 🔴 **프레임당 할당이 0이다.** 트랙 배열과 출력 목록을 상한 크기로 **생성 시 한 번** 잡고
 * in-place로 재기록한다. GL 스레드에서 GC가 돌면 그것이 곧 프레임타임 꼬리다.
 *
 * **스레드 규약: 전부 GL 스레드에서만 부른다.**
 */
class OverlaySmoother(
    /** 동시에 들고 있을 박스 수의 상한. 초과분은 **세고 버린다**. */
    private val cap: Int,
) {

    /** 그릴 목록. `x1,y1,x2,y2` × [count]이며 **NDC**다. 🔴 재사용 배열 — 보관하지 않는다. */
    val ndc = FloatArray(4 * cap)

    /** `r,g,b` × [count]. 색은 게시 시점에 **이름으로** 정해진 것을 그대로 옮긴다. */
    val colors = FloatArray(3 * cap)

    /** 이번 프레임에 그릴 박스 수. **0은 정상값이다.** */
    var count = 0
        private set

    // ── 트랙 (전부 고정 크기, 프레임당 할당 0) ────────────────────────────

    /** 살아 있는 트랙 수. 앞에서부터 [aliveCount]개가 유효하다(swap-remove로 압축한다). */
    private var aliveCount = 0

    /** 평활된 현재 좌표(NDC). 화면에 나가는 값이다. */
    private val curX1 = FloatArray(cap)
    private val curY1 = FloatArray(cap)
    private val curX2 = FloatArray(cap)
    private val curY2 = FloatArray(cap)

    /** 마지막으로 지지한 측정의 좌표(NDC) = IIR의 목표점. */
    private val tgtX1 = FloatArray(cap)
    private val tgtY1 = FloatArray(cap)
    private val tgtX2 = FloatArray(cap)
    private val tgtY2 = FloatArray(cap)

    private val trackR = FloatArray(cap)
    private val trackG = FloatArray(cap)
    private val trackB = FloatArray(cap)

    /**
     * 그 트랙의 클래스 인덱스. 🔴 **연결은 같은 클래스끼리만 한다** — 사람과 계단이 겹쳤을 때
     * 클래스를 무시하고 이으면 다음 프레임에 **색이 서로 뒤바뀐다.**
     */
    private val trackCls = IntArray(cap)

    /**
     * 남은 hold **게시** 수. 🔴 **단위가 표시 프레임이 아니라 게시다** — 새 게시를 소비한
     * 프레임에서만 깎이고, 지지를 받으면 [RenderArm.OVERLAY_HOLD_PUBLISHES_MEASUREMENT_VALUE]로
     * 다시 찬다. 0 이하가 되면 해제한다.
     *
     * 🔴 **`ACTIVE`에만 적용한다.** `PENDING`의 수명은 진입창이 정한다([trackBornAtPublish]).
     */
    private val trackTtl = IntArray(cap)

    /**
     * 트랙의 상태 — [STATE_PENDING](확인 중 · **안 그림**) 또는 [STATE_ACTIVE](그림).
     * 🔴 **출력 목록에 들어가는 것은 [STATE_ACTIVE]뿐이다** — `overlay_boxes`도 그 수다.
     */
    private val trackState = IntArray(cap)

    /**
     * 최근 게시들의 지지 여부를 담은 비트열(폭 = [HISTORY_MASK], 최신이 LSB).
     * 새 게시를 소비할 때마다 `((h shl 1) or matched) and HISTORY_MASK`로 굴린다.
     *
     * 🟢 마스크 폭은 진입창([RenderArm.OVERLAY_ENTRY_WINDOW_PUBLISHES_MEASUREMENT_VALUE])에서
     * **파생된다** — 창만 고치면 따라 넓어진다.
     */
    private val trackHistory = IntArray(cap)

    /**
     * 그 트랙이 태어난 시점의 [publishesSeen]. `PENDING` 폐기 시한을 재는 유일한 기준이며,
     * 🔴 **수명은 탄생 게시를 포함해 정확히 진입창 게시 수**다(탄생 게시가 창의 1회째다).
     */
    private val trackBornAtPublish = LongArray(cap)

    // ── 측정 쪽 임시 버퍼 (고정 크기) ─────────────────────────────────────

    private val measX1 = FloatArray(cap)
    private val measY1 = FloatArray(cap)
    private val measX2 = FloatArray(cap)
    private val measY2 = FloatArray(cap)
    private val measCls = IntArray(cap)
    private val measR = FloatArray(cap)
    private val measG = FloatArray(cap)
    private val measB = FloatArray(cap)

    /** 이 측정이 이미 어떤 트랙에 붙었는가. 프레임마다 `false`로 되돌린다. */
    private val measTaken = BooleanArray(cap)

    /** 이 트랙이 이번 프레임에 지지를 받았는가. */
    private val trackMatched = BooleanArray(cap)

    // ── 런 통계 (정지 뒤 읽는다) ──────────────────────────────────────────

    /** 이 런에서 태어난 트랙 수. 🔴 **전부 `PENDING`으로 태어난다**(태어난 즉시 그리지 않는다). */
    var tracksCreated = 0L
        private set

    /**
     * 🔴 **뜻이 바뀌었다** — 예전에는 "hold(표시 프레임)가 만료돼 폐기된 트랙 수"였고, 지금은
     * **`ACTIVE`가 연속 [RenderArm.OVERLAY_HOLD_PUBLISHES_MEASUREMENT_VALUE]회 미지지로
     * 해제된 수**다. 즉 **그리던 박스가 사라진 횟수**이며, `PENDING`이 진입창을 못 채우고
     * 버려진 것은 여기가 아니라 [pendingDiscarded]로 간다.
     *
     * 🔴 **0이 아닌 것은 정상이다**(장면에서 사라진 것이다).
     */
    var tracksExpired = 0L
        private set

    /** `PENDING`이 진입 조건을 채워 `ACTIVE`로 승격된 수 = **실제로 그리기 시작한 트랙 수**. */
    var pendingPromoted = 0L
        private set

    /**
     * `PENDING`이 진입창 안에 지지를 못 채워 버려진 수 = **한 번도 그리지 않고 사라진 트랙**.
     * 🔴 0이 아닌 것은 정상이다 — 1회짜리 오탐을 화면에 올리지 않았다는 뜻이다.
     */
    var pendingDiscarded = 0L
        private set

    /** 상한을 넘어 **세고 버린** 박스 수(GL 스레드 쪽). 조용히 버리지 않는다. */
    var droppedOverCap = 0L
        private set

    /**
     * 🔴 [OverlayCoordMap.canMap]이 false여서 **한 박스도 매핑하지 못한** 프레임 수.
     * 0이 아니면 그 런의 오버레이는 좌표를 못 만든 것이고, 값을 지어내지 않았다는 뜻이다.
     *
     * ⚠ 그 프레임은 게시를 **소비하지 않는다**(다음 프레임에 다시 시도한다) — 그래서 이 수는
     * 게시 수가 아니라 **프레임 수**이며 이름 그대로다.
     */
    var mapFailedFrames = 0L
        private set

    /**
     * 🔴 **마지막으로 소비한 게시의 시각.** 연결·TTL 재충전을 **새 게시가 왔을 때만** 하기
     * 위한 유일한 상태다([NO_PUBLISH]면 아직 아무것도 소비하지 않았다).
     *
     * 왜 필요한가: `slot`은 새 게시가 올 때까지 직전 스냅샷을 **보존한다**(정상 동작이다).
     * 그래서 매 프레임 그 스냅샷에 다시 연결하면 TTL이 영원히 재충전되고, **탐지 워커가
     * 멈추거나 매 프레임 예외로 실패해도 낡은 좌표의 박스가 무한히 그려진다.** 야간 보행
     * 보조에서 **낡은 위험물 위치를 현재인 것처럼 계속 보여 주는 것이 더 위험하다.**
     * 게다가 그 동작은 [RenderArm.OVERLAY_NO_FLICKER_DESIGN]이 `session.json`에 이미 선언한
     * *"hold가 만료돼 박스가 사라지는 동작은 남아 있다"*와 **어긋난다** — 이 필드는 코드를
     * 그 선언에 맞추는 것이다.
     *
     * 🟢 **게시 단위가 된 지금은 더 단순하다**: 지지가 살아 있으면 TTL이 매 게시 다시 차고,
     * 소비 중인 게시가 아무리 오래 머물러도(표시 프레임이 몇이든) **만료되지 않는다.**
     * 사라지는 것은 **새 게시가 그 박스를 담지 않았을 때**뿐이다.
     */
    private var lastConsumedPublishedNs = NO_PUBLISH

    /**
     * 🔴 **이 런에서 소비한 게시의 수** = 상태 기계가 도는 시계다. 표시 프레임 수가 아니다.
     * 새 게시를 소비한 프레임에서만 1 오른다.
     */
    private var publishesSeen = 0L

    /** 런을 시작한다. **런 단위 상태를 전부 내린다**(남기면 남의 런 박스가 첫 프레임에 뜬다). */
    fun reset() {
        count = 0
        aliveCount = 0
        lastConsumedPublishedNs = NO_PUBLISH
        publishesSeen = 0L
        tracksCreated = 0L
        tracksExpired = 0L
        pendingPromoted = 0L
        pendingDiscarded = 0L
        droppedOverCap = 0L
        mapFailedFrames = 0L
        // ⚠ trackState·trackHistory·trackBornAtPublish는 지우지 않는다 — aliveCount=0이라
        //   전부 도달 불가이고, 태어나는 자리에서 셋 다 **무조건 다시 쓴다**(update 5단계).
        //   여기서 배열을 순회하면 런 시작마다 cap만큼의 일이 늘 뿐 얻는 것이 없다.
    }

    /**
     * 표시 프레임 하나의 그릴 목록을 만든다. 🔴 **`stage_h_ms`가 감싸는 구간의 본체다**
     * ([RenderArm.OVERLAY_STAGE_H_SCOPE]).
     *
     * 🔴 **상태 기계는 새 게시를 소비한 프레임에서만 돈다**([lastConsumedPublishedNs]).
     * 같은 스냅샷을 다시 본 프레임은 **좌표만 IIR로 계속 수렴시키고 상태를 건드리지 않는다** —
     * 그래서 TTL이 표시 프레임 수에 딸리지 않는다.
     *
     * 매 프레임: IIR 평활 + 출력 목록 재기록(🔴 **[STATE_ACTIVE]만 나간다**).
     * 게시를 소비한 프레임에서만: 매핑 → 연결 → 히스토리 갱신 → 전이 → 탄생 → [publishesSeen]++.
     *
     * @param snapshot 지금 게시돼 있는 결과. null이면 아직 어떤 추론도 끝나지 않았다.
     * @param processW / [processH] FBO(처리 해상도) 치수. 0이면 매핑하지 않는다.
     * @return 그릴 박스 수(= [count]). **0은 정상값이다.**
     */
    fun update(snapshot: DetectOverlaySnapshot?, processW: Int, processH: Int): Int {
        // 1) 측정을 NDC로 옮긴다. 🔴 **새 게시일 때만** 한다 — 같은 스냅샷을 다시 소비하면
        //    TTL이 영원히 재충전되고 게시가 끊긴 뒤에도 낡은 박스가 무한히 남는다.
        //    🔴 canMap이 false면 값을 지어내지 않고, **소비 표시도 하지 않는다**(FBO가 아직
        //    없을 수 있으므로 다음 프레임에 다시 시도한다).
        var measCount = 0
        // 🔴 **박스가 0개인 게시도 소비다.** 그 게시는 모든 트랙에게 "미지지 1회"이고,
        //    상태 기계가 그것을 세지 않으면 해제가 영원히 오지 않는다. 그래서 measCount가
        //    아니라 **이 플래그**가 게시 소비 여부를 말한다
        //    (run_ts=20260828_185222: 0인 게시 비율 0.4345).
        var consumedPublish = false
        val isNewPublish = snapshot != null && snapshot.publishedNs != lastConsumedPublishedNs
        if (snapshot != null && isNewPublish) {
            if (OverlayCoordMap.canMap(snapshot.srcW, snapshot.srcH, processW, processH)) {
                lastConsumedPublishedNs = snapshot.publishedNs
                consumedPublish = true
                var i = 0
                while (i < snapshot.count) {
                    if (measCount >= cap) {
                        droppedOverCap++
                        i++
                        continue
                    }
                    val b = i * 4
                    val c = i * 3
                    measX1[measCount] =
                        OverlayCoordMap.ndcX(snapshot.boxes[b], snapshot.srcW, processW)
                    measY1[measCount] =
                        OverlayCoordMap.ndcY(snapshot.boxes[b + 1], snapshot.srcH, processH)
                    measX2[measCount] =
                        OverlayCoordMap.ndcX(snapshot.boxes[b + 2], snapshot.srcW, processW)
                    measY2[measCount] =
                        OverlayCoordMap.ndcY(snapshot.boxes[b + 3], snapshot.srcH, processH)
                    measR[measCount] = snapshot.colors[c]
                    measG[measCount] = snapshot.colors[c + 1]
                    measB[measCount] = snapshot.colors[c + 2]
                    measCls[measCount] = snapshot.classIds[i]
                    measCount++
                    i++
                }
            } else {
                // 🔴 값을 지어내지 않고 사실만 센다. 그 프레임은 박스 0개다.
                mapFailedFrames++
            }
        }

        var m = 0
        while (m < measCount) {
            measTaken[m] = false
            m++
        }
        var t = 0
        while (t < aliveCount) {
            trackMatched[t] = false
            t++
        }

        // ── 여기부터 6)까지는 **새 게시를 소비한 프레임에서만** 돈다 ─────────────
        // 🔴 상태 기계의 시계는 표시 프레임이 아니라 **게시**다. 같은 스냅샷을 다시 보는
        //    프레임에서 전이를 돌리면 TTL이 다시 표시 FPS에 딸리고, 게시 하나가 오래
        //    머무는 꼬리에서 **아직 그 게시를 쓰는 중에 박스가 사라진다**(08-24 런에서
        //    excess<0 35프레임으로 실측된 결함이다 — run_ts=20260828_185222).
        if (consumedPublish) {
            // 2) 연결. 탐욕적 최선 IoU다: 트랙마다 아직 안 붙은 측정 중 IoU가 가장 큰 것을
            //    고른다. ⚠ 최적 이분 매칭이 아니다. cap이 작고(박스 수가 한 자릿수) 최적화가
            //    여기서 사는 값보다 코드의 불투명함이 더 비싸다.
            t = 0
            while (t < aliveCount) {
                var best = -1
                var bestIou = OVERLAY_NO_MATCH
                m = 0
                while (m < measCount) {
                    // 🔴 클래스가 다르면 잇지 않는다(색이 뒤바뀌는 것을 막는다).
                    if (!measTaken[m] && measCls[m] == trackCls[t]) {
                        val iou = iou(
                            curX1[t], curY1[t], curX2[t], curY2[t],
                            measX1[m], measY1[m], measX2[m], measY2[m],
                        )
                        if (iou > bestIou) {
                            bestIou = iou
                            best = m
                        }
                    }
                    m++
                }
                if (best >= 0 && bestIou >= RenderArm.OVERLAY_MATCH_IOU_MEASUREMENT_VALUE) {
                    measTaken[best] = true
                    trackMatched[t] = true
                    // 목표점과 색을 그 측정으로 갱신한다(색은 이름으로 정해진 값의 **복사**다 —
                    // OverlayClassColors가 돌려주는 배열은 공유 상수이므로 값만 옮긴다).
                    tgtX1[t] = measX1[best]
                    tgtY1[t] = measY1[best]
                    tgtX2[t] = measX2[best]
                    tgtY2[t] = measY2[best]
                    trackR[t] = measR[best]
                    trackG[t] = measG[best]
                    trackB[t] = measB[best]
                    trackTtl[t] = RenderArm.OVERLAY_HOLD_PUBLISHES_MEASUREMENT_VALUE
                }
                t++
            }

            // 3) 히스토리 갱신 + 4) 전이. 🔴 **적용 범위가 상태마다 다르다**:
            //    해제(연속 미지지)는 ACTIVE에만, 진입창은 PENDING에만 건다. 해제를 PENDING에도
            //    걸면 PENDING이 1회 미지지에 죽어 "창 안에서 N회"가 "연속 N회"와 같아지고,
            //    고른 진입 규칙이 사문화된다.
            t = 0
            while (t < aliveCount) {
                val matched = trackMatched[t]
                val h = ((trackHistory[t] shl 1) or (if (matched) 1 else 0)) and HISTORY_MASK
                trackHistory[t] = h
                var remove = false
                if (trackState[t] == STATE_ACTIVE) {
                    // 해제. TTL 단위가 게시라 hold_publishes=1이면 곧 "연속 1회 미지지"다.
                    if (!matched) {
                        trackTtl[t] = trackTtl[t] - 1
                        if (trackTtl[t] <= 0) {
                            tracksExpired++
                            remove = true
                        }
                    }
                } else if (
                    Integer.bitCount(h) >= RenderArm.OVERLAY_ENTRY_HITS_REQUIRED_MEASUREMENT_VALUE
                ) {
                    // 승격. 🔴 여기서부터 그린다.
                    trackState[t] = STATE_ACTIVE
                    pendingPromoted++
                } else if (
                    publishesSeen - trackBornAtPublish[t] >=
                    RenderArm.OVERLAY_ENTRY_WINDOW_PUBLISHES_MEASUREMENT_VALUE - 1
                ) {
                    // 진입창을 다 쓰고도 지지가 모자랐다 — 한 번도 그리지 않고 버린다.
                    // 🔴 **`- 1`이 맞다: 수명은 정확히 진입창 W게시다.** 탄생 게시 B가 창의
                    //   1회째이므로 트랙이 사는 것은 B‥B+(W−1)이고, 나이가 (W−1)이 되는
                    //   B+(W−1)이 마지막 판정 자리다. `- 1` 없이 W+1게시를 살리면 B+W에서
                    //   히스토리 W비트가 {B+1‥B+W}만 덮어 **탄생 비트가 이미 밀려
                    //   나갔고**, 거기까지 승격 못 한 트랙은 남은 비트가 전부 0이라 bitCount가
                    //   요구치에 못 미친다 — **승격이 산술적으로 불가능한데도 살아서 2)연결이
                    //   측정을 삼킨다**(measTaken). 그러면 그 게시에 새 트랙도 못 태어나 위험물이
                    //   더 늦게, 지지가 거기서 끝나면 **영영** 안 그려진다.
                    //   ⚠ W를 바꿔도 이 논증은 그대로다 — 그래서 숫자를 박지 않는다.
                    // 🟢 그래서 **지지받은 게시에서 폐기되는 일은 없다** — 탄생 비트가 아직
                    //   창 안에 있으므로 이번 게시가 지지했다면 bitCount ≥ 2가 되어 위
                    //   가지에서 먼저 승격한다. 버려지는 것은 탄생 뒤 두 게시가 **모두**
                    //   침묵한 트랙뿐이고, 그 게시의 측정을 삼키지도 않는다.
                    pendingDiscarded++
                    remove = true
                }
                if (remove) {
                    // swap-remove로 압축한다(순서는 뜻이 없다).
                    val last = aliveCount - 1
                    if (t != last) {
                        curX1[t] = curX1[last]; curY1[t] = curY1[last]
                        curX2[t] = curX2[last]; curY2[t] = curY2[last]
                        tgtX1[t] = tgtX1[last]; tgtY1[t] = tgtY1[last]
                        tgtX2[t] = tgtX2[last]; tgtY2[t] = tgtY2[last]
                        trackR[t] = trackR[last]
                        trackG[t] = trackG[last]
                        trackB[t] = trackB[last]
                        trackCls[t] = trackCls[last]
                        trackTtl[t] = trackTtl[last]
                        trackMatched[t] = trackMatched[last]
                        trackState[t] = trackState[last]
                        trackHistory[t] = trackHistory[last]
                        trackBornAtPublish[t] = trackBornAtPublish[last]
                    }
                    aliveCount = last
                    // 같은 인덱스를 다시 본다(방금 옮겨 온 트랙이다).
                    continue
                }
                t++
            }

            // 5) 붙지 못한 측정은 **PENDING 트랙**으로 태어난다. 🔴 태어난 게시에는 그리지
            //    않는다 — 1회짜리 오탐을 화면에 올리지 않는 것이 PENDING의 전부다.
            //    좌표는 IIR을 걸지 않고 측정 그대로 둔다(0이나 화면 중앙에서 자라게 하면
            //    그게 곧 튐이다). ⚠ 전이(4)보다 **뒤**라 이번 게시에 태어난 트랙은 다음
            //    게시부터 전이를 받는다.
            m = 0
            while (m < measCount) {
                if (!measTaken[m]) {
                    if (aliveCount >= cap) {
                        droppedOverCap++
                    } else {
                        val n = aliveCount
                        curX1[n] = measX1[m]; tgtX1[n] = measX1[m]
                        curY1[n] = measY1[m]; tgtY1[n] = measY1[m]
                        curX2[n] = measX2[m]; tgtX2[n] = measX2[m]
                        curY2[n] = measY2[m]; tgtY2[n] = measY2[m]
                        trackR[n] = measR[m]
                        trackG[n] = measG[m]
                        trackB[n] = measB[m]
                        trackCls[n] = measCls[m]
                        trackTtl[n] = RenderArm.OVERLAY_HOLD_PUBLISHES_MEASUREMENT_VALUE
                        trackMatched[n] = true
                        trackState[n] = STATE_PENDING
                        trackHistory[n] = HISTORY_BORN
                        trackBornAtPublish[n] = publishesSeen
                        aliveCount = n + 1
                        tracksCreated++
                    }
                }
                m++
            }

            // 6) 게시 시계를 돌린다. 🔴 **탄생(5)보다 뒤다** — bornAt이 방금 소비한 게시를
            //    가리켜야 진입창이 그 게시를 1회째로 센다.
            publishesSeen++
        }

        // 7) 평활 + 그릴 목록. 🔴 **매 프레임 돈다.** IIR은 PENDING에도 걸어 둔다(승격되는
        //    순간에 이미 목표점에 수렴해 있어야 튀지 않는다). 한 게시를 쓰는 약 9프레임 동안
        //    계속 수렴하는 것이 튐을 없애는 부분이고, 전이(4)와는 별개다.
        //    🔴 **출력에 들어가는 것은 ACTIVE뿐이다 — 이 단계의 유일한 필터다.**
        var out = 0
        t = 0
        while (t < aliveCount) {
            val a = RenderArm.OVERLAY_IIR_ALPHA_MEASUREMENT_VALUE
            curX1[t] += a * (tgtX1[t] - curX1[t])
            curY1[t] += a * (tgtY1[t] - curY1[t])
            curX2[t] += a * (tgtX2[t] - curX2[t])
            curY2[t] += a * (tgtY2[t] - curY2[t])
            if (trackState[t] == STATE_ACTIVE) {
                // 🔴 **클램프하지 않는다.** 프레임 밖 좌표는 그대로 넘기고 GL 뷰포트가 자른다 —
                //    후처리에서 클램프를 없앤 이유가 "면적 0 박스가 가장자리에 얇은 선으로 남는
                //    것"이었고, 여기서 되살리면 같은 결함이다(규약 §5-3).
                val o4 = out * 4
                val o3 = out * 3
                ndc[o4] = curX1[t]
                ndc[o4 + 1] = curY1[t]
                ndc[o4 + 2] = curX2[t]
                ndc[o4 + 3] = curY2[t]
                colors[o3] = trackR[t]
                colors[o3 + 1] = trackG[t]
                colors[o3 + 2] = trackB[t]
                out++
            }
            t++
        }
        count = out
        return out
    }

    /** 축 정렬 IoU. 겹치지 않으면 0이다(합집합이 0인 축퇴 박스도 0을 낸다). */
    private fun iou(
        ax1: Float, ay1: Float, ax2: Float, ay2: Float,
        bx1: Float, by1: Float, bx2: Float, by2: Float,
    ): Float {
        val ix1 = if (ax1 > bx1) ax1 else bx1
        val iy1 = if (ay1 > by1) ay1 else by1
        val ix2 = if (ax2 < bx2) ax2 else bx2
        val iy2 = if (ay2 < by2) ay2 else by2
        val iw = ix2 - ix1
        val ih = iy2 - iy1
        if (iw <= 0f || ih <= 0f) return 0f
        val inter = iw * ih
        val aw = ax2 - ax1
        val ah = ay2 - ay1
        val bw = bx2 - bx1
        val bh = by2 - by1
        // 역전 박스는 게시자가 이미 걸렀지만(세고 그리지 않는다) 여기서도 음수 면적을
        // 만들지 않는다 — 음수 합집합은 IoU를 1보다 크게 만들어 엉뚱한 연결을 낳는다.
        if (aw <= 0f || ah <= 0f || bw <= 0f || bh <= 0f) return 0f
        val union = aw * ah + bw * bh - inter
        if (union <= 0f) return 0f
        return inter / union
    }

    private companion object {
        /** [update]의 최선 IoU 초기값. 0보다 작아야 "겹침 0인 후보"도 best로 잡힌다. */
        const val OVERLAY_NO_MATCH = -1f

        /**
         * [lastConsumedPublishedNs]의 "아직 아무 게시도 소비하지 않았다".
         * 🔴 `0`이나 `-1`을 쓰지 않는다 — `elapsedRealtimeNanos`는 부팅 직후 작은 값일 수 있고,
         * 그 값과 겹치면 첫 게시가 "이미 소비했다"로 오인돼 **첫 박스가 그려지지 않는다.**
         */
        const val NO_PUBLISH = Long.MIN_VALUE

        /** [trackState]: 확인 중 — 🔴 **그리지 않는다.** 태어난 트랙은 전부 여기서 시작한다. */
        const val STATE_PENDING = 0

        /** [trackState]: 진입 조건을 채웠다 — **그린다.** `overlay_boxes`는 이 상태의 수다. */
        const val STATE_ACTIVE = 1

        /**
         * [trackHistory]의 폭 — 진입창
         * [RenderArm.OVERLAY_ENTRY_WINDOW_PUBLISHES_MEASUREMENT_VALUE]에서 **파생한다.**
         * 🟢 상수(`0b111`)로 박아 두면 창을 넓혔을 때 오래된 지지가 조용히 잘려 나가
         * "창 안에서 N회"가 실제로는 더 좁은 창이 되므로, 파생시켜 그 함정을 없앤다.
         */
        const val HISTORY_MASK =
            (1 shl RenderArm.OVERLAY_ENTRY_WINDOW_PUBLISHES_MEASUREMENT_VALUE) - 1

        /** 태어난 게시에서의 [trackHistory] — "이번 게시가 지지했다" 1회. */
        const val HISTORY_BORN = 0b001
    }
}

/**
 * H칸의 **런 사실**. `session.json`의 `overlay.smoothing` 블록으로 나간다.
 *
 * 🔴 여기 있는 수는 전부 **관측값이지 판정이 아니다.** [tracksExpired]가 0이 아닌 것은 결함이
 * 아니라 "장면에서 위험물이 사라졌다"일 수 있고, 그것을 가르려면 정답 라벨이 필요하다
 * (하네스의 `safety_regression`이 `evaluated: false`인 이유와 같다).
 */
class OverlaySmootherFacts(
    /** 태어난 트랙 수. 🔴 **전부 `PENDING`으로 태어난다** — 태어난 수 ≠ 그린 수다. */
    val tracksCreated: Long,
    /**
     * 🔴 **뜻이 바뀌었다** — 옛 뜻은 "hold(표시 프레임) 만료"였고 지금은
     * **`ACTIVE`가 연속 미지지로 해제된 수**(= 그리던 박스가 사라진 횟수)다.
     * **0이 아닌 것은 정상일 수 있다.**
     */
    val tracksExpired: Long,
    /** `PENDING` → `ACTIVE` 승격 수 = **실제로 그리기 시작한 트랙 수**. */
    val pendingPromoted: Long,
    /** `PENDING`이 진입창을 못 채우고 버려진 수 = **한 번도 그리지 않은 트랙**. */
    val pendingDiscarded: Long,
    /** 상한 초과로 **세고 버린** 박스 수(GL 스레드 쪽). 조용히 버리지 않는다. */
    val droppedOverCap: Long,
    /** 🔴 좌표를 만들 치수가 없어 **한 박스도 매핑하지 못한** 프레임 수. */
    val mapFailedFrames: Long,
)
