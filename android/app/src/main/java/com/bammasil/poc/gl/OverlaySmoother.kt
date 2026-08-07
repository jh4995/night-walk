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
 * ## 🔴 깜빡임을 만들지 않는다 — 그것이 이 파일의 제약이다
 *
 * 전문은 [RenderArm.OVERLAY_NO_FLICKER_DESIGN]에 있고 같은 문장이 `session.json`으로 나간다.
 * 요지 넷: **점멸·펄스·알파 변조가 없다** · **hold 안에서 박스를 0으로 떨어뜨리지 않는다** ·
 * **새 게시가 그 박스를 다시 담으면 TTL이 다시 찬다**(게시 주기 ≈9프레임 <
 * hold [RenderArm.OVERLAY_HOLD_FRAMES_MEASUREMENT_VALUE]프레임이라 탐지가 정상인 동안은
 * 만료되지 않는다) · **크기·위치는 IIR로만 움직인다.**
 *
 * 🔴 **게시가 끊기면 hold 뒤에 사라진다 — 그것은 깜빡임이 아니다.** 낡은 위험물 위치를
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
 * hold 프레임 수 · 폐기 조건(IoU 임계) · IIR 계수 · 상한. 🔴 **INTERFACES.md에 이 항목 자체가
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

    /** 남은 hold 프레임 수. 0 이하가 되면 폐기한다. */
    private val trackTtl = IntArray(cap)

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

    /** 이 런에서 태어난 트랙 수. */
    var tracksCreated = 0L
        private set

    /** hold가 만료돼 폐기된 트랙 수. 🔴 **0이 아닌 것은 정상이다**(장면에서 사라진 것이다). */
    var tracksExpired = 0L
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
     * 🟢 **"지지가 살아 있는 동안은 만료되지 않는다"는 그대로 남는다**: 게시 주기가 실측 약
     * 3.4Hz(30FPS에서 ≈9프레임)이고 hold가
     * [RenderArm.OVERLAY_HOLD_FRAMES_MEASUREMENT_VALUE]프레임이므로, 탐지가 정상인 동안은
     * 만료 전에 다음 게시가 온다. 사라지는 것은 **게시가 끊긴 경우**뿐이다.
     */
    private var lastConsumedPublishedNs = NO_PUBLISH

    /** 런을 시작한다. **런 단위 상태를 전부 내린다**(남기면 남의 런 박스가 첫 프레임에 뜬다). */
    fun reset() {
        count = 0
        aliveCount = 0
        lastConsumedPublishedNs = NO_PUBLISH
        tracksCreated = 0L
        tracksExpired = 0L
        droppedOverCap = 0L
        mapFailedFrames = 0L
    }

    /**
     * 표시 프레임 하나의 그릴 목록을 만든다. 🔴 **`stage_h_ms`가 감싸는 구간의 본체다**
     * ([RenderArm.OVERLAY_STAGE_H_SCOPE]).
     *
     * 🔴 **연결·TTL 재충전은 새 게시가 왔을 때만 한다**([lastConsumedPublishedNs]). 같은
     * 스냅샷을 다시 본 프레임은 **TTL을 깎고 좌표는 IIR로 계속 수렴시킨다** — hold 동작
     * 자체는 그대로이고, 게시가 끊기면 hold 뒤에 사라진다.
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
        val isNewPublish = snapshot != null && snapshot.publishedNs != lastConsumedPublishedNs
        if (snapshot != null && isNewPublish) {
            if (OverlayCoordMap.canMap(snapshot.srcW, snapshot.srcH, processW, processH)) {
                lastConsumedPublishedNs = snapshot.publishedNs
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

        // 2) 연결. **새 게시가 왔을 때만 돈다**(measCount가 0이면 루프가 아무 일도 하지
        //    않는다) — 같은 스냅샷에 다시 붙이면 TTL이 영원히 재충전된다(위 1번).
        //    새 게시가 그 박스를 다시 담으면 TTL이 다시 차고, 게시 주기(실측 3.4Hz ≈ 9프레임)가
        //    hold보다 짧으므로 **탐지가 정상인 동안은 만료되지 않는다.**
        //    탐욕적 최선 IoU다: 트랙마다 아직 안 붙은 측정 중 IoU가 가장 큰 것을 고른다.
        //    ⚠ 최적 이분 매칭이 아니다. cap이 작고(박스 수가 한 자릿수) 최적화가 여기서
        //      사는 값보다 코드의 불투명함이 더 비싸다.
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
                trackTtl[t] = RenderArm.OVERLAY_HOLD_FRAMES_MEASUREMENT_VALUE
            }
            t++
        }

        // 3) 붙지 못한 측정은 **새 트랙**으로 태어난다. 🔴 태어나는 프레임은 IIR을 걸지 않고
        //    측정 좌표를 그대로 쓴다 — 0이나 화면 중앙에서 자라게 하면 그게 곧 튐이다.
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
                    trackTtl[n] = RenderArm.OVERLAY_HOLD_FRAMES_MEASUREMENT_VALUE
                    trackMatched[n] = true
                    aliveCount = n + 1
                    tracksCreated++
                }
            }
            m++
        }

        // 4) 평활 + hold/TTL + 그릴 목록. 🔴 **지지가 없으면 좌표를 끊지 않고 TTL만 깎는다** —
        //    마지막 좌표를 계속 그리는 것이 hold의 실체다. IIR은 **매 프레임** 돌아 같은
        //    게시를 쓰는 약 9프레임 동안 목표점으로 계속 수렴한다(그것이 튐을 없애는 부분이고,
        //    TTL 재충전과는 별개다).
        var out = 0
        t = 0
        while (t < aliveCount) {
            if (!trackMatched[t]) {
                trackTtl[t] = trackTtl[t] - 1
            }
            if (trackTtl[t] <= 0) {
                // 폐기. swap-remove로 압축한다(순서는 뜻이 없다).
                tracksExpired++
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
                }
                aliveCount = last
                // 같은 인덱스를 다시 본다(방금 옮겨 온 트랙이다).
                continue
            }
            val a = RenderArm.OVERLAY_IIR_ALPHA_MEASUREMENT_VALUE
            curX1[t] += a * (tgtX1[t] - curX1[t])
            curY1[t] += a * (tgtY1[t] - curY1[t])
            curX2[t] += a * (tgtX2[t] - curX2[t])
            curY2[t] += a * (tgtY2[t] - curY2[t])
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
    val tracksCreated: Long,
    /** hold가 만료돼 폐기된 트랙 수. **0이 아닌 것은 정상일 수 있다.** */
    val tracksExpired: Long,
    /** 상한 초과로 **세고 버린** 박스 수(GL 스레드 쪽). 조용히 버리지 않는다. */
    val droppedOverCap: Long,
    /** 🔴 좌표를 만들 치수가 없어 **한 박스도 매핑하지 못한** 프레임 수. */
    val mapFailedFrames: Long,
)
