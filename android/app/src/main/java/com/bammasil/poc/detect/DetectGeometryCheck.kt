package com.bammasil.poc.detect

import com.bammasil.poc.gl.OverlayCoordMap

/**
 * ③ **기하 왕복 자체검사.** 회전과 letterbox가 서로의 역함수인지, 그리고 ④가 쓰는 센서→NDC
 * 매핑이 프레임 전체를 화면 전체로 보내는지를 런을 시작하기 **전에** 기계로 확인한다.
 *
 * ## 왜 런을 시작하는 자리에 다는가
 *
 * `DetectPipeline.start`는 이미 *"임계를 숫자로 못 읽으면 후처리를 시작하지 않는다"*로
 * **런을 거부하는 자리**다. 기하가 틀린 채로 돈 런은 그 자리에서와 똑같이 못 쓰는 런이다 —
 * E는 엉뚱한 픽셀을 읽고 G의 박스는 통째로 어긋나는데, 둘 다 **그럴듯한 숫자로 나온다.**
 * 11분을 찍고 나서 아는 것보다 시작 전에 거부하는 쪽이 싸다.
 *
 * ## 무엇을 태우는가
 *
 * 🔴 **프로덕션 함수를 그대로 태운다 — 사본을 만들지 않는다.**
 * `DetectContract.letterbox` · `Letterbox.toSrcX/Y` · `toDstX/Y` ·
 * `Rotation.inverseBox` · `Rotation.forwardBox`(그리고 그것들이 부르는 점 함수 넷).
 * 검사용으로 같은 공식을 다시 적으면 **같은 오타를 두 번 적고 통과한다.**
 *
 * ## 다섯 검사
 *
 * 🔴 **번호는 아래 인라인 주석의 `검사 N`과 같다** — 세 자리(이 목록 · 인라인 주석 ·
 * `SessionWriter`의 `overlay.coordinate_map.selfcheck`)가 같은 번호를 써야 한다.
 * 갈리면 실패 문장을 읽는 사람이 다른 검사를 들여다본다.
 *
 * 1. **왕복** — letterbox 640 좌표의 박스 → (역) → 센서 → (정) → 640. 원래 값으로 돌아오는가.
 *    잡는 것: 정방향과 역방향이 서로의 역함수인가, 반사 축의 교환 규칙이 짝이 맞는가.
 * 2. **모서리 기준점** — 회전 후 프레임의 `(0,0)`이 센서의 **어디**인가(샘플 맵 = 픽셀
 *    인덱스 규약). 🔴 왕복만으로는 **회전 방향(시계/반시계)을 못 잡는다** — 양쪽이 같은
 *    방향으로 틀리면 왕복은 통과한다.
 * 3. **프레임 전체 박스** — `(0,0,rotatedW,rotatedH)` → `(0,0,srcW,srcH)`(박스 = 연속
 *    모서리 규약). 🔴 **박스에 인덱스 식 `(N−1)−v`를 쓰는 1px 실수를 잡는 유일한 검사다.**
 *    정·역이 같은 규약으로 같이 틀리면 왕복은 통과한다.
 * 4. **샘플 맵의 가역성** — 회전 후 픽셀 인덱스 → 센서 → 복귀. 🔴 **박스 왕복이 이 두 함수를
 *    건드리지 않는다**(모서리 규약이라 다른 경로다) — 전처리가 픽셀마다 부르는 것은
 *    `toSensorX/Y`이므로 그 가역성은 따로 확인해야 한다. 여기서 안 잡으면 E만 조용히 틀린다.
 * 5. **④ 센서 → NDC** — `(0,0,srcW,srcH)` → `(-1,-1,1,1)`([OverlayCoordMap]).
 *    🔴 위 넷은 ③ 안에서만 닫히고 **화면에 나가는 좌표를 확인하지 않는다** — 매핑의 스케일이나
 *    부호가 어긋나면 박스가 화면 어딘가에 **그럴듯하게** 그려지고 기계로는 아무것도 안 걸린다.
 *    ⚠ 이 검사가 확인하는 것도 **자기 일관성까지**다. [OverlayCoordMap.FLIP_Y]의 참·거짓은
 *    **눈으로만** 확인된다(그 파일의 KDoc이 이미 그렇게 적었다). 🔴 그리고 **호출부가 잘못된
 *    처리 해상도를 넘기는 실수도 잡지 못한다**(process 치수가 약분된다) — 그건
 *    `session.json`의 `overlay.coordinate_map`만이 관측한다.
 *
 * 2·3·5의 기대값 표가 이 파일의 유일한 "사본"이고, **사본이어야 하는 것이 맞다** —
 * 오라클은 구현과 독립이어야 한다(4는 왕복이라 사본이 없다).
 *
 * ⚠ [TOLERANCE_PX]는 **우리가 선언한 검사 조건이지 계약값이 아니다**(`SAMPLE_COUNT`와 같은
 * 부류다). 판정보다 먼저 **관측값**(`max|d|`)을 그대로 낸다 — 규약 §7과 같은 태도다.
 */
object DetectGeometryCheck {

    class Result(
        /** 실제로 돈 케이스 수(회전 × 소스 치수 × 박스 + 기준점). */
        val cases: Int,
        /** 🔴 **관측값**. 왕복 좌표차의 최대 절대값(px). 판정보다 이 값이 먼저다. */
        val maxAbsDelta: Float,
        /** 우리가 선언한 허용치(px). 계약값이 아니다. */
        val tolerancePx: Float,
        val passed: Boolean,
        /** 사람이 읽는 실패 문장. 통과했으면 비어 있다. 🔴 앞 몇 개만 남긴다. */
        val failures: List<String>,
    ) {
        /** 런을 거부할 때 호출자에게 돌려줄 사유. */
        fun failureText(): String =
            "🔴 ③ 기하 왕복 자체검사가 실패했다 — 런을 시작하지 않는다. " +
                "케이스 $cases 개, max|d| = $maxAbsDelta px (허용 $tolerancePx px). " +
                "회전/letterbox의 정·역변환이 서로의 역함수가 아니면 **E는 엉뚱한 픽셀을 " +
                "읽고 G의 박스는 통째로 어긋나는데 둘 다 그럴듯한 숫자로 나온다.** " +
                "처음 실패들: " + failures.joinToString(" | ")
    }

    /**
     * @param dstW / [dstH] 입력 텐서의 목적지 치수. 🔴 **그래프에서 읽은 값을 넘긴다** —
     *   여기에 640을 적으면 그게 곧 하드코딩이다.
     */
    fun run(dstW: Int, dstH: Int): Result {
        val failures = ArrayList<String>()
        var cases = 0
        var maxAbs = 0f
        val out = FloatArray(4)
        val back = FloatArray(4)

        for (degrees in DetectContract.SUPPORTED_ROTATIONS) {
            for (src in SOURCES) {
                val srcW = src[0]
                val srcH = src[1]
                val rot = DetectContract.rotationOf(degrees, srcW, srcH)
                if (rot == null) {
                    failures.add("rotationOf($degrees, ${srcW}x$srcH)가 null이다")
                    cases++
                    continue
                }
                val box = DetectContract.letterbox(rot.rotatedW, rot.rotatedH, dstW, dstH)

                // ── 검사 2: 모서리 기준점(회전 **방향**을 잡는 유일한 검사) ──────────
                cases++
                val anchorX = rot.toSensorX(0f, 0f)
                val anchorY = rot.toSensorY(0f, 0f)
                val expectX = when (degrees) {
                    // 회전 후 프레임의 (0,0)이 센서의 어디에서 왔는가.
                    // 종이를 시계 방향으로 90° 돌리면 **왼쪽 아래** 모서리가 왼쪽 위로 간다.
                    90 -> 0f
                    180 -> (srcW - 1).toFloat()
                    270 -> (srcW - 1).toFloat()
                    else -> 0f
                }
                val expectY = when (degrees) {
                    90 -> (srcH - 1).toFloat()
                    180 -> (srcH - 1).toFloat()
                    270 -> 0f
                    else -> 0f
                }
                if (anchorX != expectX || anchorY != expectY) {
                    note(
                        failures,
                        "기준점 어긋남 deg=$degrees src=${srcW}x$srcH: " +
                            "회전 후 (0,0) → 센서 ($anchorX,$anchorY), " +
                            "기대 ($expectX,$expectY). **회전 방향이 뒤집혔을 수 있다**"
                    )
                }

                // ── 검사 4: 샘플 맵의 가역성 (인덱스 규약) ──────────────────────
                // 🔴 **박스 왕복은 이 두 함수를 건드리지 않는다**(모서리 규약이라 다른
                //    경로다). 전처리가 픽셀마다 부르는 것은 toSensorX/Y이므로 그 가역성은
                //    따로 확인해야 한다 — 여기서 안 잡으면 E만 조용히 틀린다.
                for (p in INDEX_PROBES) {
                    cases++
                    val rx = (if (p[0] < 0) rot.rotatedW + p[0] else p[0]).toFloat()
                    val ry = (if (p[1] < 0) rot.rotatedH + p[1] else p[1]).toFloat()
                    val sx = rot.toSensorX(rx, ry)
                    val sy = rot.toSensorY(rx, ry)
                    val bx = rot.toRotatedX(sx, sy)
                    val by = rot.toRotatedY(sx, sy)
                    if (bx != rx || by != ry) {
                        note(
                            failures,
                            "샘플 맵 왕복 어긋남 deg=$degrees src=${srcW}x$srcH: " +
                                "회전 후 ($rx,$ry) → 센서 ($sx,$sy) → 복귀 ($bx,$by)"
                        )
                    }
                    // 센서 인덱스가 평면 밖으로 나가면 전처리가 다른 픽셀을 읽는다.
                    if (sx < 0f || sx > (srcW - 1).toFloat() ||
                        sy < 0f || sy > (srcH - 1).toFloat()
                    ) {
                        note(
                            failures,
                            "샘플 맵이 센서 밖을 가리킨다 deg=$degrees src=${srcW}x$srcH: " +
                                "회전 후 ($rx,$ry) → 센서 ($sx,$sy)"
                        )
                    }
                }

                // ── 검사 3: 프레임 전체 박스 (모서리 규약을 잡는 유일한 검사) ────────
                // 🔴 **왕복만으로는 못 잡는다.** 정·역이 **같은 규약으로 같이 틀리면**
                //    왕복은 통과한다 — 그 1px이 조용히 남는 자리가 정확히 여기다.
                //    주장: 회전 후 프레임 전체 (0,0,rotatedW,rotatedH)는 센서 프레임 전체
                //    (0,0,srcW,srcH)로 가야 한다. 인덱스 식 `(N−1)−v`를 박스에 쓰면
                //    반사축의 시작이 −1로 나와 여기서 걸린다.
                cases++
                rot.inverseBox(0f, 0f, rot.rotatedW.toFloat(), rot.rotatedH.toFloat(), out)
                if (out[0] != 0f || out[1] != 0f ||
                    out[2] != srcW.toFloat() || out[3] != srcH.toFloat()
                ) {
                    note(
                        failures,
                        "프레임 전체 박스 어긋남 deg=$degrees src=${srcW}x$srcH: " +
                            "(0,0,${rot.rotatedW},${rot.rotatedH}) → " +
                            "(${out[0]},${out[1]},${out[2]},${out[3]}), " +
                            "기대 (0.0,0.0,${srcW}.0,${srcH}.0). " +
                            "**박스에 픽셀 인덱스 규약((N−1)−v)을 썼을 수 있다** — " +
                            "박스는 연속(모서리) 좌표라 N−v가 맞다"
                    )
                }

                // ── 검사 5: ① 센서 → ④ NDC 매핑 (모서리 → NDC 모서리) ──────────
                // 🔴 **④를 연결하면서 들어온 검사다.** 오버레이가 그리는 좌표는
                //    OverlayCoordMap이 만드는데, 그 식이 축을 뒤집거나 스케일이 어긋나면
                //    **박스가 화면 어딘가에 그럴듯하게 그려진다** — 눈으로 봐도 "좀 안 맞네"
                //    까지만 알 수 있고 기계로는 아무것도 안 걸린다.
                //    주장: 센서 프레임 전체 (0,0,srcW,srcH)는 NDC 전체 (-1,-1,1,1)로 가야 한다.
                // 🔴 **이 검사는 호출부가 잘못된 처리 해상도를 넘기는 실수를 잡지 못한다.**
                //   ndcX/ndcY 안에서 process 치수가 대수적으로 약분되므로 **어떤 process 값을
                //   넣어도 결과가 같다**(독립 검증의 파괴 시험이 Preview 1920x1080을 넣어
                //   차 0.0을 확인했다). 여기서 두 치수를 다 넘기는 것은 **식의 형태를 유지해
                //   다음 사람이 접힌 식만 보고 "처리 해상도를 안 쓴다"로 읽지 않게** 하는
                //   것까지이며, 그 이상을 주장하지 않는다.
                //   ⚠ **실제 관측 수단은 session.json의 overlay.coordinate_map뿐이다** —
                //   analysis_resolution / process_resolution을 나란히 싣고 aspect_matches로
                //   기계 대조한다. 두 use case의 시야가 다르면 거기서만 보인다.
                // 🔴 이 검사가 확인하는 것은 **자기 일관성까지**이고 FLIP_Y의 참·거짓이
                //   아니다 — 그 값은 **눈으로만** 확인된다(OverlayCoordMap의 KDoc).
                for (proc in PROCESS_SIZES) {
                    cases++
                    val pw = proc[0]
                    val ph = proc[1]
                    if (!OverlayCoordMap.canMap(srcW, srcH, pw, ph)) {
                        note(
                            failures,
                            "canMap이 false다 src=${srcW}x$srcH process=${pw}x$ph — " +
                                "매핑을 만들 수 없는 치수를 검사표에 넣었다"
                        )
                        continue
                    }
                    val nx0 = OverlayCoordMap.ndcX(0f, srcW, pw)
                    val nx1 = OverlayCoordMap.ndcX(srcW.toFloat(), srcW, pw)
                    val ny0 = OverlayCoordMap.ndcY(0f, srcH, ph)
                    val ny1 = OverlayCoordMap.ndcY(srcH.toFloat(), srcH, ph)
                    // FLIP_Y면 y의 두 끝이 뒤바뀐다 — 그 경우도 **모서리로는 가야 한다.**
                    val expectY0 = if (OverlayCoordMap.FLIP_Y) 1f else -1f
                    val expectY1 = if (OverlayCoordMap.FLIP_Y) -1f else 1f
                    var dn = abs(nx0 - (-1f))
                    val dn2 = abs(nx1 - 1f)
                    val dn3 = abs(ny0 - expectY0)
                    val dn4 = abs(ny1 - expectY1)
                    if (dn2 > dn) dn = dn2
                    if (dn3 > dn) dn = dn3
                    if (dn4 > dn) dn = dn4
                    // ⚠ **maxAbsDelta에 섞지 않는다** — 그 값은 px 단위 왕복 오차이고 이쪽은
                    //   NDC(무차원)다. 한 칸에 담으면 관측값의 단위가 두 가지가 된다.
                    if (!(dn <= NDC_TOLERANCE)) {
                        note(
                            failures,
                            "센서→NDC 모서리 어긋남 src=${srcW}x$srcH process=${pw}x$ph: " +
                                "(0,0,$srcW,$srcH) → ($nx0,$ny0,$nx1,$ny1), " +
                                "기대 (-1.0,$expectY0,1.0,$expectY1), |d|=$dn. " +
                                "**OverlayCoordMap의 스케일·부호를 의심할 것**"
                        )
                    }
                }

                // ── 검사 1: 왕복 ────────────────────────────────────────────────
                for (b in boxCases(box, dstW, dstH)) {
                    cases++
                    // ③ → ② letterbox 역 → ① 회전 역 (= 후처리가 실제로 하는 두 단계)
                    val rx1 = box.toSrcX(b[0])
                    val ry1 = box.toSrcY(b[1])
                    val rx2 = box.toSrcX(b[2])
                    val ry2 = box.toSrcY(b[3])
                    rot.inverseBox(rx1, ry1, rx2, ry2, out)
                    // ① → ② 회전 정 → ③ letterbox 정
                    rot.forwardBox(out[0], out[1], out[2], out[3], back)
                    val gx1 = box.toDstX(back[0])
                    val gy1 = box.toDstY(back[1])
                    val gx2 = box.toDstX(back[2])
                    val gy2 = box.toDstY(back[3])

                    var d = abs(gx1 - b[0])
                    val d2 = abs(gy1 - b[1])
                    val d3 = abs(gx2 - b[2])
                    val d4 = abs(gy2 - b[3])
                    if (d2 > d) d = d2
                    if (d3 > d) d = d3
                    if (d4 > d) d = d4
                    if (d > maxAbs) maxAbs = d
                    if (!(d <= TOLERANCE_PX)) {
                        // NaN도 여기서 걸린다(`!(d <= t)`로 쓴 이유다).
                        note(
                            failures,
                            "왕복 어긋남 deg=$degrees src=${srcW}x$srcH " +
                                "pad=(${box.padX},${box.padY}) scale=${box.scale}: " +
                                "입력 (${b[0]},${b[1]},${b[2]},${b[3]}) → " +
                                "센서 (${out[0]},${out[1]},${out[2]},${out[3]}) → " +
                                "복귀 ($gx1,$gy1,$gx2,$gy2), |d|=$d"
                        )
                    }
                }
            }
        }
        return Result(
            cases = cases,
            maxAbsDelta = maxAbs,
            tolerancePx = TOLERANCE_PX,
            passed = failures.isEmpty(),
            failures = failures,
        )
    }

    /**
     * 이 letterbox에서 검사할 박스들(letterbox 640 좌표계). 🔴 **모델이 내는 공간에서
     * 출발한다** — 후처리가 실제로 받는 것이 이 공간의 박스이기 때문이다.
     *
     * 포함: 패딩 **안에만** 있는 박스 · 프레임 경계를 넘는 박스 · 1px 박스 · 코너 박스 ·
     * 소수 좌표 박스 · **역전 박스**(모델이 `w<0`을 낸 경우 — 교환 규칙이 그 뒤집힘을
     * 지우지 않는지 본다).
     */
    private fun boxCases(
        box: DetectContract.Letterbox,
        dstW: Int,
        dstH: Int,
    ): List<FloatArray> {
        val cases = ArrayList<FloatArray>(8)
        // 패딩 안에만 있는 박스. 패딩이 없는 기하(정사각 소스)면 이 케이스는 성립하지 않는다.
        if (box.padY >= 2) cases.add(floatArrayOf(10f, 0f, 60f, (box.padY - 1).toFloat()))
        if (box.padX >= 2) cases.add(floatArrayOf(0f, 10f, (box.padX - 1).toFloat(), 60f))
        // 프레임 경계를 넘는 박스(클램프가 없으므로 실제로 나올 수 있다).
        cases.add(floatArrayOf(-50f, -30f, dstW + 60f, dstH + 40f))
        // 1px 박스 · 코너 박스.
        cases.add(floatArrayOf(100f, 100f, 101f, 101f))
        cases.add(floatArrayOf(0f, 0f, 1f, 1f))
        cases.add(floatArrayOf((dstW - 1).toFloat(), (dstH - 1).toFloat(), dstW.toFloat(), dstH.toFloat()))
        // 소수 좌표(모델은 정수를 내지 않는다).
        cases.add(floatArrayOf(100.5f, 200.25f, 300.75f, 400.125f))
        // 🔴 역전 박스. 교환 규칙이 min/max가 아님을 왕복으로 확인한다.
        cases.add(floatArrayOf(300f, 400f, 200f, 100f))
        return cases
    }

    private fun note(failures: MutableList<String>, message: String) {
        if (failures.size < MAX_FAILURES) failures.add(message)
    }

    private fun abs(v: Float): Float = if (v < 0f) -v else v

    /**
     * 소스 치수 표. 🔴 **패딩이 홀수로 남는 것을 일부러 섞었다** — `align=center`의 남는
     * 1px이 오른쪽/아래로 가는 규약이라 홀수에서만 드러나는 어긋남이 있다.
     *
     * | 치수 | 왜 |
     * |---|---|
     * | 1280×720 | 측정 기기가 실제로 주는 값 |
     * | 720×1280 | 위를 90° 돌린 것과 같은 종횡비 |
     * | 1280×717 | dst−content가 **홀수** |
     * | 1277×720 | 다른 축에서 **홀수** |
     * | 640×640 | 패딩이 **아예 없다**(경계 케이스) |
     * | 1920×1080 | 다른 해상도로 협상될 수 있다 |
     */
    private val SOURCES: List<IntArray> = listOf(
        intArrayOf(1280, 720),
        intArrayOf(720, 1280),
        intArrayOf(1280, 717),
        intArrayOf(1277, 720),
        intArrayOf(640, 640),
        intArrayOf(1920, 1080),
    )

    /**
     * 왕복 허용치(px). ⚠ **우리가 선언한 검사 조건이지 계약값이 아니다.**
     *
     * 근거: 왕복은 `x·scale` 다음 `/scale`이라 float32에서 상대오차 몇 ulp가 남고, 좌표
     * 크기가 2000px 안쪽이면 그 절대값은 1e-3px 수준이다. 여기 쓴 1e-2px는 그보다 한 자리
     * 위이면서, 부호·축·패딩이 어긋나는 진짜 결함(보통 수십~수백 px)과는 자릿수가 한참 멀다.
     * 🔴 상류가 자기 대조에 쓴 바(0.0001px)와 **다른 검사다** — 그쪽은 PyTorch↔ONNX 값 대조이고
     * 이쪽은 우리 기하의 왕복이다. 그 바를 여기 끌어오지 않는다.
     */
    const val TOLERANCE_PX = 1e-2f

    /**
     * ④ NDC 매핑 검사의 허용치(무차원). ⚠ **우리가 선언한 검사 조건이지 계약값이 아니다**
     * ([TOLERANCE_PX]와 같은 부류다). NDC 폭이 2이므로 1e-5는 720p에서 0.007px 수준이다.
     * 🔴 [TOLERANCE_PX]와 **다른 값이고 다른 단위다** — 한 상수를 돌려 쓰면 px 허용치가
     * 무차원 좌표에 적용된다(720p에서 1e-2 NDC = 6.4px이라 부호 실수를 놓칠 수 있다).
     */
    const val NDC_TOLERANCE = 1e-5f

    /**
     * ④ NDC 매핑 검사가 쓸 **처리 해상도** 표. 대수적으로는 약분되지만 두 치수를 다 태우는
     * 형태를 유지한다(위 검사 5의 ⚠).
     *
     * | 치수 | 왜 |
     * |---|---|
     * | 1280×720 | 측정 기기가 실제로 협상하는 값 |
     * | 640×360 | 처리 해상도를 깎는 레버가 검토 중이다(§6) |
     * | 720×1280 | 분석 치수와 **종횡비가 어긋난** 경우(그때도 모서리는 모서리로 가야 한다) |
     */
    private val PROCESS_SIZES: List<IntArray> = listOf(
        intArrayOf(1280, 720),
        intArrayOf(640, 360),
        intArrayOf(720, 1280),
    )

    /** 실패 문장을 남기는 상한. 다 남기면 `session.json`이 실패 문장으로 뒤덮인다. */
    private const val MAX_FAILURES = 6

    /**
     * 샘플 맵 가역성을 볼 **회전 후 프레임의 픽셀 인덱스**. 음수는 끝에서부터 센다
     * (`-1` = 마지막 픽셀). 네 모서리 + 안쪽 한 점이며, **모서리가 중요하다** —
     * 반사식의 `N−1`이 틀리면 거기서만 드러난다.
     */
    private val INDEX_PROBES: List<IntArray> = listOf(
        intArrayOf(0, 0),
        intArrayOf(-1, 0),
        intArrayOf(0, -1),
        intArrayOf(-1, -1),
        intArrayOf(7, 3),
    )
}
