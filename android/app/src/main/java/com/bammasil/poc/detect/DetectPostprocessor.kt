package com.bammasil.poc.detect

import java.nio.FloatBuffer

/**
 * G(후처리): `output0` `[1, 4+nc, N]` → conf 필터 → `cxcywh`→`xyxy` → **클래스별** NMS →
 * letterbox 역변환(원본 프레임 좌표).
 *
 * ## 상류가 못 박은 4단계 (`models/det_c4b_loli0_640/README.md`)
 *
 * 1. 채널 `4..`의 최대값이 `conf` 미만인 앵커를 버린다
 * 2. `cx,cy,w,h` → `x1,y1,x2,y2`
 * 3. **클래스별** NMS (IoU)
 * 4. letterbox 역변환 — 🔴 *"좌표는 letterbox 좌표계다. 원본 프레임에 그대로 그리면 박스가
 *    어긋난다. 후처리 4번을 반드시 구현할 것"*
 *
 * ⚠ 채널 `4..`는 **이미 sigmoid가 적용돼 있다.** 다시 씌우지 않는다 — 씌우면 점수가
 * 0.5 쪽으로 뭉쳐 conf 임계가 다른 뜻이 된다.
 *
 * ## 무엇을 어디까지 재는가 (G의 정의)
 *
 * [run]이 도는 구간 전체가 G다. **출력 텐서를 네이티브에서 읽어 오는 복사도 여기 들어간다** —
 * F는 `session.run()` **호출 하나**이고, 그 반환값을 읽는 비용은 후처리의 비용이다.
 *
 * ## 스레드
 *
 * 탐지 워커 스레드 전용. 내부 배열을 재사용한다(프레임당 객체 0개).
 */
class DetectPostprocessor(
    private val numChannels: Int,
    private val numAnchors: Int,
    private val confThreshold: Float,
    private val iouThreshold: Float,
) {

    /** `4 + nc`에서 온 클래스 수. 상수가 아니라 **그래프 shape의 함수**다. */
    val classCount: Int = numChannels - 4

    /** 출력 텐서를 네이티브에서 옮겨 담을 자리. 재사용한다. */
    private val raw = FloatArray(numChannels * numAnchors)

    // 후보 박스(원본 프레임 좌표로 되돌린 값). 앵커 수만큼 최악을 잡아 둔다 —
    // 런 도중에 늘리면 그 프레임만 GC를 끌어와 G의 꼬리가 그 할당 때문에 튄다.
    private val boxX1 = FloatArray(numAnchors)
    private val boxY1 = FloatArray(numAnchors)
    private val boxX2 = FloatArray(numAnchors)
    private val boxY2 = FloatArray(numAnchors)
    private val boxScore = FloatArray(numAnchors)
    private val boxClass = IntArray(numAnchors)
    private val order = IntArray(numAnchors)
    private val suppressed = BooleanArray(numAnchors)

    /** NMS를 통과한 후보의 인덱스([boxX1] 등에 대한). [Result.boxesOut]개가 유효하다. */
    val keptIndices = IntArray(numAnchors)

    class Result(
        /** conf 임계를 통과했고 **NMS 전**인 박스 수. G 비용의 설명 변수다. */
        val boxesPreNms: Int,
        /** 최종 박스 수. */
        val boxesOut: Int,
        /**
         * 그 추론의 **최대 점수**. 임계를 통과한 것이 하나도 없어도 값이 있다 —
         * "박스 0개"와 "장면이 완전히 비었다"를 구분하는 유일한 단서다.
         */
        val maxConf: Float,
    )

    /**
     * @param output `session.run()`이 낸 출력 텐서의 [FloatBuffer]. `[1, numChannels,
     *   numAnchors]`를 **채널 우선**으로 읽는다(YOLO v8/v11 헤드의 레이아웃).
     * @param box 전처리가 쓴 letterbox 기하. 🔴 **전처리와 같은 객체여야 한다** — 여기서
     *   다시 계산하면 반올림이 어긋나는 날 박스가 몇 px씩 조용히 밀린다.
     */
    fun run(output: FloatBuffer, box: DetectContract.Letterbox): Result {
        output.rewind()
        output.get(raw, 0, raw.size)

        var maxConf = 0f
        var count = 0
        val invScale = 1f / box.scale
        var a = 0
        while (a < numAnchors) {
            // 1) 클래스별 점수의 최대값. (이미 sigmoid가 적용돼 있다 — 다시 씌우지 않는다.)
            var best = raw[4 * numAnchors + a]
            var bestClass = 0
            var c = 1
            while (c < classCount) {
                val s = raw[(4 + c) * numAnchors + a]
                if (s > best) {
                    best = s
                    bestClass = c
                }
                c++
            }
            if (best > maxConf) maxConf = best
            if (best >= confThreshold) {
                // 2) cxcywh → xyxy (letterbox 640 좌표계, 픽셀 단위)
                val cx = raw[a]
                val cy = raw[numAnchors + a]
                val hw = raw[2 * numAnchors + a] * 0.5f
                val hh = raw[3 * numAnchors + a] * 0.5f
                // 4) letterbox 역변환 — 패딩 offset을 빼고 scale로 나눈다.
                //    ⚠ NMS 앞에서 해도 IoU 순서는 바뀌지 않는다(같은 아핀 변환이다).
                //      여기서 하는 이유는 클램프를 **원본 프레임 경계**로 걸기 위해서다.
                var x1 = (cx - hw - box.padX) * invScale
                var y1 = (cy - hh - box.padY) * invScale
                var x2 = (cx + hw - box.padX) * invScale
                var y2 = (cy + hh - box.padY) * invScale
                if (x1 < 0f) x1 = 0f
                if (y1 < 0f) y1 = 0f
                if (x2 > box.srcW.toFloat()) x2 = box.srcW.toFloat()
                if (y2 > box.srcH.toFloat()) y2 = box.srcH.toFloat()
                boxX1[count] = x1
                boxY1[count] = y1
                boxX2[count] = x2
                boxY2[count] = y2
                boxScore[count] = best
                boxClass[count] = bestClass
                count++
            }
            a++
        }

        // 3) 클래스별 NMS. 점수 내림차순 greedy이며, **같은 클래스끼리만** 누른다 —
        //    클래스를 섞어 누르면 사람 앞의 계단이 사라진다.
        var kept = 0
        if (count > 0) {
            var i = 0
            while (i < count) {
                order[i] = i
                suppressed[i] = false
                i++
            }
            sortByScoreDesc(order, boxScore, count)
            var oi = 0
            while (oi < count) {
                val idx = order[oi]
                if (!suppressed[idx]) {
                    keptIndices[kept] = idx
                    kept++
                    var oj = oi + 1
                    while (oj < count) {
                        val jdx = order[oj]
                        if (!suppressed[jdx] && boxClass[jdx] == boxClass[idx] &&
                            iou(idx, jdx) > iouThreshold
                        ) {
                            suppressed[jdx] = true
                        }
                        oj++
                    }
                }
                oi++
            }
        }
        return Result(boxesPreNms = count, boxesOut = kept, maxConf = maxConf)
    }

    private fun iou(i: Int, j: Int): Float {
        val ix1 = if (boxX1[i] > boxX1[j]) boxX1[i] else boxX1[j]
        val iy1 = if (boxY1[i] > boxY1[j]) boxY1[i] else boxY1[j]
        val ix2 = if (boxX2[i] < boxX2[j]) boxX2[i] else boxX2[j]
        val iy2 = if (boxY2[i] < boxY2[j]) boxY2[i] else boxY2[j]
        val iw = ix2 - ix1
        val ih = iy2 - iy1
        if (iw <= 0f || ih <= 0f) return 0f
        val inter = iw * ih
        val ai = (boxX2[i] - boxX1[i]) * (boxY2[i] - boxY1[i])
        val aj = (boxX2[j] - boxX1[j]) * (boxY2[j] - boxY1[j])
        val union = ai + aj - inter
        return if (union <= 0f) 0f else inter / union
    }

    /**
     * 점수 내림차순 삽입 정렬. **객체를 만들지 않는다** — `sortedByDescending` 류를 쓰면
     * 프레임마다 박싱된 리스트가 생겨 GC가 G의 꼬리를 만든다.
     *
     * ⚠ 삽입 정렬을 쓰는 이유: conf 0.35를 통과하는 앵커는 야간 보행에서 보통 한 자릿수다.
     * 임계가 낮아져 후보가 수백 개가 되면 이 자리가 G의 지배항이 되므로, 그때는
     * `boxes_pre_nms`가 그 사실을 먼저 말해 준다(그래서 그 열을 낸다).
     */
    private fun sortByScoreDesc(idx: IntArray, score: FloatArray, n: Int) {
        var i = 1
        while (i < n) {
            val v = idx[i]
            val s = score[v]
            var j = i - 1
            while (j >= 0 && score[idx[j]] < s) {
                idx[j + 1] = idx[j]
                j--
            }
            idx[j + 1] = v
            i++
        }
    }
}
