package com.bammasil.poc.detect

import java.nio.FloatBuffer

/**
 * G(후처리): `output0` `[1, 4+nc, N]` → conf 필터 → `cxcywh`→`xyxy` → **클래스별** NMS →
 * letterbox 역변환 → **회전 역변환**(센서 좌표).
 *
 * ## 상류가 못 박은 4단계 (`models/det_c4b_loli0_640/README.md`)
 *
 * 1. 채널 `4..`의 최대값이 `conf` 미만인 앵커를 버린다
 * 2. `cx,cy,w,h` → `x1,y1,x2,y2`
 * 3. **클래스별** NMS (IoU)
 * 4. letterbox 역변환 — 🔴 *"좌표는 letterbox 좌표계다. 원본 프레임에 그대로 그리면 박스가
 *    어긋난다. 후처리 4번을 반드시 구현할 것"*
 *
 * ## 5번째 칸 — 회전 역변환 (규약 v1.2 §5-1)
 *
 * 상류는 이미 정립된 이미지를 받으므로 이 칸이 상류에 없다. 우리는 카메라 원본을 먹고
 * 전처리에서 회전을 합성했으므로 **되돌려야** 하고, 결과는 **① 센서 좌표계**다(§5-2).
 *
 * 🔴 **NMS 뒤에 둔다.** 회전 역변환은 아핀이고 90° 배수라 순수 좌표 치환이지만, 그래도
 * 앞으로 옮기지 않는다 — *"아핀이니 앞에 둬도 된다"*는 논거가 **바로 클램프 사고를 낳았다**
 * (아래 4단계 주석의 역사). 순서를 상류와 같게 두는 것이 논거 없이도 성립하는 유일한 방식이다.
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

    /** 회전 역변환의 결과를 받는 자리. **재사용한다**(프레임당 객체 0개 규약). */
    private val corner = FloatArray(4)

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
        /**
         * 이 추론에서 나온 **역전 박스**(`x2 < x1` 또는 `y2 < y1`) 수.
         * 🔴 **거른 개수가 아니라 센 개수다** — 해당 박스는 [boxesOut] 안에 그대로 있다.
         */
        val boxesInverted: Int,
    )

    /**
     * 역전 박스 하나의 **실제 좌표**. 개수만으로는 못 고친다 — 알려진 이슈 34가 잡힌 것은
     * `x1=0.0, x2=-148.75`라는 구체 좌표 덕이었다.
     *
     * 🔴 **두 좌표계를 같이 싣는다.** 회전 **전**(② 회전 후 좌표계)에 이미 뒤집혀 있었으면
     * 원인은 모델의 `w<0`이고, 회전 전에는 멀쩡했는데 회전 **후**에 뒤집혔으면 원인은
     * 회전 역변환이다. 한쪽만 있으면 이 둘을 못 가른다.
     */
    class InvertedBox(
        val cls: Int,
        val conf: Float,
        /** letterbox 역변환 직후 = ② 회전 후 좌표계. */
        val rotX1: Float,
        val rotY1: Float,
        val rotX2: Float,
        val rotY2: Float,
        /** 회전 역변환까지 끝난 값 = ① 센서 좌표계. */
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
    )

    /**
     * 이 런에서 관측한 역전 박스 **총계**. 🔴 **거른 개수가 아니라 센 개수다.**
     *
     * ⚠ 탐지 워커 스레드가 올리고 정지 시점(A12 (2) quiesce **뒤**)에 GL 스레드가 읽는다 —
     * quiesce가 `busy` AtomicBoolean을 통해 happens-before를 세워 준다.
     * 🔴 **그래도 `@Volatile`이다** — quiesce가 **타임아웃하면**(`quiesced=false`) 그
     * happens-before가 서지 않고, 그때도 읽기가 성립해야 한다.
     */
    @Volatile
    var invertedBoxesTotal: Long = 0L
        private set

    // 🔴 **`ArrayList`가 아니라 고정 배열 + volatile 개수다.** quiesce가 타임아웃하면
    //    GL 스레드가 아래 목록을 훑는 동안 워커가 아직 append 할 수 있고, 살아 있는
    //    ArrayList였다면 그 순간 ConcurrentModificationException으로 **session.json이
    //    통째로 날아간다**(런 하나를 잃는다). 배열은 그 실패 모드가 없다 — 최악이라도
    //    개수가 한 박자 옛것일 뿐이고, 그건 총계와의 차이로 드러난다.
    private val invertedSampleSlots = arrayOfNulls<InvertedBox>(INVERTED_SAMPLE_CAP)

    /** 🔴 **원소를 먼저 쓰고 이 값을 마지막에 올린다** — volatile 쓰기가 발행 순서를 정한다. */
    @Volatile
    private var invertedSampleCount = 0

    /**
     * 처음 [INVERTED_SAMPLE_CAP]개의 역전 박스 좌표. 총계와 함께 `session.json`으로 나간다.
     * **호출할 때마다 스냅샷을 뜬다**(런당 한 번 불린다).
     */
    val invertedSamples: List<InvertedBox>
        get() {
            val n = invertedSampleCount
            if (n == 0) return emptyList()
            val out = ArrayList<InvertedBox>(n)
            for (i in 0 until n) invertedSampleSlots[i]?.let { out.add(it) }
            return out
        }

    /**
     * @param output `session.run()`이 낸 출력 텐서의 [FloatBuffer]. `[1, numChannels,
     *   numAnchors]`를 **채널 우선**으로 읽는다(YOLO v8/v11 헤드의 레이아웃).
     * @param box 전처리가 쓴 letterbox 기하. 🔴 **전처리와 같은 객체여야 한다** — 여기서
     *   다시 계산하면 반올림이 어긋나는 날 박스가 몇 px씩 조용히 밀린다.
     * @param rotation 전처리가 쓴 회전 기하. 🔴 **같은 이유로 같은 객체여야 한다.**
     *   회전을 적용하지 않는 대조군 arm에서는 항등(0°)이 들어오고, 그때 5단계는 항등이다.
     */
    fun run(
        output: FloatBuffer,
        box: DetectContract.Letterbox,
        rotation: DetectContract.Rotation,
    ): Result {
        output.rewind()
        output.get(raw, 0, raw.size)

        var maxConf = 0f
        var count = 0
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
                // 🔴 **여기서는 letterbox 640 좌표계 그대로 둔다.** 역변환은 NMS **뒤**다
                //    (아래 4단계) — 상류 README의 후처리 순서와 같다.
                //    ⚠ **역사**: 예전에는 여기서 역변환 + 원본 경계 클램프를 함께 하고
                //      "같은 아핀 변환이라 IoU 순서가 안 바뀐다"고 정당화했는데, 그때 같이
                //      하던 **클램프가 아핀이 아니었다** — 화면 밖으로 걸친 박스의 면적이
                //      잘려 IoU가 달라지고 억제 결과가 상류와 갈릴 수 있었다(독립 검증 지적).
                //      그 클램프는 2026-08-07에 **아예 없앴다**(아래 4단계). 남은 것은
                //      "역변환을 NMS 뒤로 둔다"는 순서 규칙 하나뿐이고, 그건 상류와 같다.
                boxX1[count] = cx - hw
                boxY1[count] = cy - hh
                boxX2[count] = cx + hw
                boxY2[count] = cy + hh
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

        // 4) letterbox 역변환. **NMS 뒤이고, 살아남은 박스만** 한다.
        //    상류 README 후처리 절의 **네 단계 그대로**다(필터 → xyxy → 클래스별 NMS → 역변환).
        //    부수 효과로 변환 횟수가 count에서 kept로 줄어든다(보통 한 자릿수다).
        //
        // 🔴 **원본 프레임 경계 클램프를 없앴다(2026-08-07).** 상류 명세에 없는 5번째 단계였고,
        //    이탈로 기록된 적도 없다. 게다가 **비대칭**이었다 — `x1`/`y1`은 아래만, `x2`/`y2`는
        //    위만 잘라서, 탐지가 letterbox 패딩 **안에만** 있으면 `x2 < x1`인 **역전 박스**가
        //    나왔다(독립 검증이 `pad_x=159`에서 재현: `x1=0.0, x2=-148.75`).
        //    ⚠ 대칭으로 고치는 길도 있었지만 그러면 **면적 0인 박스가 화면 가장자리에** 남고,
        //      ④ 오버레이가 그걸 그리면 테두리에 얇은 선이 생긴다 — 사용자에게 보이는 쓰레기다.
        //      클램프를 빼면 패딩·경계 때문에 생기던 역전이 사라지고 상류와도 같아진다.
        //
        // 🔴 **부등호 `x1 < x2`를 보장하는 것은 `scale`이 아니라 모델이다.**
        //    `x2 - x1 = w · invScale`이므로 부호는 **`w`가 정한다**(`scale`은 크기만 바꾼다).
        //    ⚠ 이 자리에 "scale > 0이라 항상 성립한다"고 적었다가 독립 검증에 잡혔다 —
        //    `w < 0`이면 클램프가 없어도 역전이다. 근거는 이렇다:
        //      · 실측 — 덤프 출력 텐서 25개(앵커 201,600개)에서 `min(w)=4.599 min(h)=6.470`,
        //        음수·0 관측 **0건**. 적대적 입력 8종(0/1/회색114/난수/±1e3 등)에서도 0건.
        //      · 구조 — YOLO11 DFL 헤드는 `w = (lt + rb) · stride`이고 `lt,rb ≥ 0`이다.
        //    🔴 **그러므로 이 불변식은 "이 모델이 그렇다"이지 산술적 보장이 아니다.**
        //    계약 A는 모델 교체를 허용하므로 **모델이 바뀌면 조용히 깨질 수 있다.**
        //    🔴 **2026-08-07 정정: 이제 음수 좌표가 GL에 간다.** 예전 문장("지금은
        //      HighlightOverlay.draw가 개수만 받아 음수 좌표가 GL에 간 적이 없다" ·
        //      "이 값을 쓰는 소비자는 대조 덤프 하나뿐이다")은 **더 이상 사실이 아니다** —
        //      ③→④가 연결돼(`DetectOverlayPublisher` → `OverlaySmoother` → 오버레이)
        //      프레임 밖 좌표가 실제로 GL 뷰포트까지 간다. **그것이 의도다**: 뷰포트가
        //      자르므로 클램프가 필요 없고, 클램프를 되살리면 위 ⚠의 면적 0 박스가 돌아온다.
        //      ⚠ 새로 생기는 눈에 보이는 결과 하나: 박스가 프레임 가장자리에 붙으면
        //        **검정 밑선이 한쪽만 잘려 보인다**(RenderArm.HIGHLIGHT_DEVIATION (4)).
        //        그건 스트로크를 경계선 가운데에 맞춘 기하의 결과이고 결함이 아니다.
        //      🔴 역전 박스는 소비자 쪽(게시자)이 **세고 그리지 않는다**(규약 §5-3) —
        //        여기서 거르지 않는 이유가 그대로 유지된다.
        //    🔴 **PC 레퍼런스(`scripts/detect_parity.py`)도 같이 고쳤다** — 한쪽만 고치면
        //      이식 대조가 그 자리에서 어긋난다.
        //
        // 🔴 **회전이 그 불변식에 새 경로를 만든다(v1.2).** 위 문단은 "부등호를 보장하는
        //    것은 모델이다"까지였는데, 이제 그 뒤에 좌표를 만지는 단계가 하나 더 붙었다 —
        //    5단계의 회전 역변환이다. 반사가 붙는 축(90/180/270°)에서 순서를 되돌리지 않으면
        //    **잘 생긴 박스까지 전부 뒤집힌다.** 되돌리는 일은 Rotation.inverseBox가 하고
        //    (min/max가 아니라 **축 대응 + 순서 동반 이동**이라 모델이 낸 역전은 그대로
        //    남는다), 그 뒤에 남은 역전을 여기서 **센다 — 거르지 않는다**(규약 §5-3).
        //    ⚠ inverseBox는 **모서리(연속) 좌표 규약**(`N − v`)이다. 전처리 샘플 맵의
        //      픽셀 인덱스 규약(`(N−1) − v`)과 다르며, 섞으면 정확히 1px 밀린다.
        //    ⚠ 거르면 면적 0인 박스가 화면 가장자리에 남아 ④가 얇은 선을 그린다.
        //      **나오면 그 자체가 결함**이고 조용히 지우면 결함이 숨는다.
        var k = 0
        var inverted = 0
        while (k < kept) {
            val idx = keptIndices[k]
            // 4) letterbox 역변환 → ② 회전 후 좌표계
            val rx1 = box.toSrcX(boxX1[idx])
            val ry1 = box.toSrcY(boxY1[idx])
            val rx2 = box.toSrcX(boxX2[idx])
            val ry2 = box.toSrcY(boxY2[idx])
            // 5) 회전 역변환 → ① 센서 좌표계 (규약 §5-2: 소비자가 보는 공간은 센서다)
            rotation.inverseBox(rx1, ry1, rx2, ry2, corner)
            val x1 = corner[0]
            val y1 = corner[1]
            val x2 = corner[2]
            val y2 = corner[3]
            boxX1[idx] = x1
            boxY1[idx] = y1
            boxX2[idx] = x2
            boxY2[idx] = y2
            if (x2 < x1 || y2 < y1) {
                inverted++
                invertedBoxesTotal++
                val n = invertedSampleCount
                if (n < INVERTED_SAMPLE_CAP) {
                    // 🔴 원소를 먼저 쓰고 개수를 나중에 올린다(위 필드 주석).
                    invertedSampleSlots[n] = InvertedBox(
                        cls = boxClass[idx],
                        conf = boxScore[idx],
                        rotX1 = rx1, rotY1 = ry1, rotX2 = rx2, rotY2 = ry2,
                        x1 = x1, y1 = y1, x2 = x2, y2 = y2,
                    )
                    invertedSampleCount = n + 1
                }
            }
            k++
        }
        return Result(
            boxesPreNms = count,
            boxesOut = kept,
            maxConf = maxConf,
            boxesInverted = inverted,
        )
    }

    /**
     * 최종 박스 하나. 🔴 **① 센서 좌표계**다(letterbox 역변환 + 회전 역변환까지 거친 값,
     * 규약 §5-2). `src.width`/`src.height`와 **같은 공간**이며, 회전 후("바로 선") 공간이
     * 아니다 — 두 공간의 좌표를 동시에 싣지 않는다.
     * 🔴 **화면 밖으로 나갈 수 있다** — 경계 클램프를 하지 않는다(위 4단계 주석).
     * 음수이거나 프레임 폭·높이를 넘을 수 있으므로 **소비자가 in-frame을 가정하면 안 된다.**
     * ⚠ **역전(`x2<x1`/`y2<y1`)일 수도 있다** — 거르지 않고 세기만 한다(규약 §5-3).
     * 소비자는 둘이다: ③ 이식 정확성 덤프(`DetectParityDumper`)의 매니페스트와,
     * ④ 게시자(`DetectOverlayPublisher`) — 후자가 **역전을 세고 그리지 않는다.**
     */
    class Box(
        val cls: Int,
        val conf: Float,
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
    )

    /**
     * NMS를 통과한 박스 [count]개를 복사한다. `count`는 [Result.boxesOut]이다.
     *
     * 🔴 **[run] 직후 같은 스레드에서만 부른다** — 내부 배열은 재사용이라 다음 프레임이
     * 덮어쓴다. ⚠ 이 함수는 객체를 만든다(위 "프레임당 객체 0개" 규약의 예외다). 부르는 곳
     * 둘: **덤프 arm의 샘플 K개**와 **④ 오버레이 arm의 추론마다**(게시당 1회, 실측 약 3.4Hz)다.
     * 🔴 둘 다 **E·F·G 구간 밖**이라 그 할당이 단계 시간에 섞이지 않는다 —
     * 그 밖의 arm은 이 경로를 타지 않는다.
     */
    fun copyBoxes(count: Int): List<Box> {
        val out = ArrayList<Box>(count)
        var k = 0
        while (k < count) {
            val i = keptIndices[k]
            out.add(Box(boxClass[i], boxScore[i], boxX1[i], boxY1[i], boxX2[i], boxY2[i]))
            k++
        }
        return out
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

    private companion object {
        /**
         * 좌표까지 남기는 역전 박스의 개수 상한. **개수만으로는 못 고친다** — 이슈 34가
         * 잡힌 것은 `x1=0.0, x2=-148.75`라는 구체 좌표 덕이었다.
         *
         * ⚠ 이 상한이 있는 이유는 두 가지다: (1) 여기서만 객체를 만들므로("프레임당 객체 0개"
         * 규약의 예외) 상한이 없으면 역전이 쏟아지는 런에서 GC가 G의 꼬리를 만든다,
         * (2) `session.json`이 감당할 크기여야 한다. **총계는 상한 없이 센다** —
         * 잘리는 것은 좌표 표본뿐이고 그 사실은 총계와 표본 수의 차이가 말한다.
         */
        const val INVERTED_SAMPLE_CAP = 8
    }
}
