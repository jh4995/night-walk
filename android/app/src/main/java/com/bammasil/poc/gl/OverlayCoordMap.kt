package com.bammasil.poc.gl

/**
 * ③ 박스(**① 센서 좌표**) → ④ 오버레이가 그리는 **처리 해상도 FBO의 NDC**. 🔴 **이 매핑은
 * 여기 한 곳에만 있다.**
 *
 * `DetectContract.Letterbox`·`DetectContract.Rotation`을 한 곳에서만 만드는 것과 **같은
 * 논거**다 — 전처리와 후처리가 각자 공식을 적었다면 부호 하나가 어긋나는 날 박스가 통째로
 * 뒤집히고 그때 어느 쪽이 틀렸는지 알 수 없다. 여기도 같다: GL 쪽과 자체검사가 각자 식을
 * 적으면 **같은 오타를 두 번 적고 통과한다.**
 *
 * ## 입력 공간
 *
 * `DetectPostprocessor.Box`는 **① 센서 좌표계**다(letterbox 역변환 + 회전 역변환까지 끝난
 * 값, 규약 §5-2). 그 공간의 치수는 **분석 use case가 실제로 준 `ImageProxy`의 width/height**
 * 이며, 이 파일에서는 [srcW]/[srcH]로 받는다.
 *
 * 🔴 **Preview 해상도를 쓰면 조용히 틀린다.** Preview와 `ImageAnalysis`는 같은
 * `ResolutionSelector`를 쓰지만 **같은 값을 받는다는 보장이 없다**(`SessionWriter`가
 * `camera_analysis_actual`을 `camera_actual`과 따로 내는 이유가 그것이다). 그래서 이 함수는
 * 두 치수를 **둘 다 런타임 값으로** 받는다 — 분석 치수(센서 공간)와 처리 치수(FBO 공간).
 *
 * ## 식
 *
 * ```
 * scaleX = processW / srcW          (센서 px → 처리 해상도 px)
 * px     = xSensor * scaleX
 * ndcX   = 2 * px / processW - 1
 * ```
 * 대수적으로는 `ndcX = 2 * xSensor / srcW - 1`로 접히지만(처리 치수가 약분된다) **두 값을
 * 그대로 쓰는 형태로 적어 둔다** — 접힌 식만 남기면 "처리 해상도를 안 쓴다"로 읽혀 나중에
 * Preview 치수를 끼워 넣는 실수가 다시 생긴다. y도 같다.
 *
 * 🔴 **그러나 그 형태가 잘못된 처리 치수를 잡아 주지는 않는다** — 약분되므로 [processW]에
 * 어떤 값을 넣어도 결과가 같다(독립 검증이 Preview 1920×1080을 넣어 차 0.0을 확인했다).
 * 어긋남을 **실제로 관측하는 수단은 `session.json`의 `overlay.coordinate_map` 하나**이며,
 * 거기서 분석 치수·처리 치수를 나란히 싣고 `aspect_matches`로 기계 대조한다.
 *
 * ⚠ **두 use case의 종횡비가 다르면 이 매핑은 그만큼 틀린다.** 종횡비가 다르다는 것은
 * 시야(crop)가 다르다는 뜻인데 그 crop을 알려 주는 런타임 값이 없다 — 이 식은 **두 use case가
 * 같은 시야를 본다**고 가정한다. 실제 두 치수는 `session.json`의 `overlay.coordinate_map`에
 * 나란히 실리므로 어긋나면 거기서 보인다.
 *
 * ## 🔴 세로 축 방향은 **가정이다**
 *
 * 표시 경로는 **방향 보정을 한다** — present 패스가 `uPositionMatrix`로 회전을 걸고, ④ 박스는
 * 그보다 앞서 회전 전 FBO에 그려지므로 **영상과 박스가 같은 행렬로 함께 돈다.** 이 매핑이
 * 회전을 몰라도 되는 이유가 그것이다.
 *
 * 🔴 **그런데도 세로 축 방향은 여전히 가정이다.** 회전이 걸린다는 것과 "센서의 y=0 행이 화면
 * 어느 쪽에 놓이는가"는 다른 질문이다. 패스1의 OES 샘플링은 드라이버가 준 `texMatrix`를 그대로
 * 쓰는데 그 행렬이 이미 상하를 뒤집어 놓았을 수 있고, **앱은 그것을 관측할 수단이 없다**
 * (`texMatrix`의 원소를 읽어 봐야 그것이 화면의 어느 쪽인지는 나오지 않는다). 회전각을
 * `session.json`에 싣는 것으로도 이 질문은 닫히지 않는다.
 *
 * [FLIP_Y]는 그 가정 하나를 담은 **유일한 스위치**이며, 화면에서 위아래가 뒤집혀 보이면
 * **여기만** 바꾼다.
 *
 * ⚠ 자체검사([com.bammasil.poc.detect.DetectGeometryCheck])가 확인하는 것은 **"프레임 전체
 * 박스가 NDC 전체로 간다"는 자기 일관성까지**이고 [FLIP_Y]의 참·거짓이 아니다 — 그 값은
 * **눈으로만** 확인된다.
 */
object OverlayCoordMap {

    /**
     * 🔴 **가정.** false = 센서 y가 커질수록 NDC y도 커진다(뒤집지 않는다).
     *
     * false로 둔 이유: 이 파이프라인의 전체화면 quad가 `uv.v = 0`을 `NDC y = -1`에 놓고
     * (`PassthroughRenderer.VERTEX_DATA`), 정적 더미 오버레이도 같은 규약으로 좌표를
     * 만들어 왔다. 즉 **기존 코드와 일관된 쪽**을 골랐다.
     * ⚠ 이것은 "화면에서 위아래가 맞다"는 주장이 **아니다** — 위 KDoc 참고.
     */
    const val FLIP_Y = false

    /** 사람이 읽는 식. 같은 문장이 `session.json`의 `overlay.coordinate_map.formula`로 나간다. */
    const val FORMULA =
        "입력 = ① 센서 좌표(DetectPostprocessor.Box — letterbox 역변환 + 회전 역변환까지 " +
            "끝난 값, 규약 §5-2). " +
            "scaleX = process_w / analysis_w ; px = x_sensor * scaleX ; " +
            "ndc_x = 2 * px / process_w - 1. " +
            "scaleY = process_h / analysis_h ; py = y_sensor * scaleY ; " +
            "ndc_y = 2 * py / process_h - 1" +
            "(FLIP_Y=true면 부호를 뒤집는다). " +
            "🔴 **analysis_w/h는 분석 use case가 실제로 준 ImageProxy의 치수이고 Preview " +
            "해상도가 아니다** — 두 use case는 같은 ResolutionSelector를 써도 같은 값을 " +
            "받는다는 보장이 없다. 대수적으로는 process 치수가 약분되지만(ndc_x = " +
            "2*x/analysis_w - 1) 두 런타임 값을 다 쓰는 형태로 적어 둔다"

    /** 🔴 이 매핑에 남아 있는 **가정 전부.** 같은 문장이 `session.json`으로 나간다. */
    const val ASSUMPTIONS =
        "🔴 **(1) 세로 축 방향은 가정이다(FLIP_Y=$FLIP_Y).** ⚠ 이유가 예전과 다르다: " +
            "표시 경로는 **방향 보정을 한다**(present 패스가 uPositionMatrix로 회전을 걸고, " +
            "④ 박스는 회전 전 FBO에 그려져 영상과 **함께** 돈다 — 실제로 건 각도는 " +
            "render.preview_transform). 그런데도 가정이 남는 까닭은, 회전이 걸린다는 것과 " +
            "'센서 y=0 행이 화면 어느 쪽에 놓이는가'가 **다른 질문**이기 때문이다. 패스1의 " +
            "OES 샘플링은 드라이버가 준 texMatrix를 그대로 쓰는데 그 행렬이 이미 상하를 " +
            "뒤집어 놓았을 수 있고, **앱에는 그것을 관측할 수단이 없다.** " +
            "**화면에서 위아래가 뒤집혀 보이면 OverlayCoordMap.FLIP_Y 하나만 바꾼다** — " +
            "매핑이 한 곳에 있는 이유가 그것이다. " +
            "⚠ geometry_selfcheck가 확인하는 것은 '프레임 전체 박스가 NDC 전체로 간다'는 " +
            "**자기 일관성까지**이고 FLIP_Y의 참·거짓이 아니다 — 그건 눈으로만 확인된다. " +
            "🔴 **(2) 가로 방향도 같은 처지다** — 좌우 반전이 있었다면 이 매핑은 그대로 " +
            "틀리고 왕복 검사는 통과한다. 회전의 **부호**(90 대 270)도 여기 속한다: " +
            "PassthroughRenderer.PREVIEW_ROTATION_SIGN 하나가 뒤집히면 영상과 박스가 함께 " +
            "반대로 눕고, 그 역시 화면으로만 갈린다. " +
            "🔴 **(3) letterbox align=center는 여전히 미확정이다**(INTERFACES.md §A-2가 ☐). " +
            "회전이 붙었으므로 **90/270°에서는 어긋남이 가로 방향으로 나타난다** — " +
            "§A-2의 '상하 균등 분배'는 회전 없이 letterbox 할 때의 축이고, 90/270°로 돌리면 " +
            "720p가 세로로 길어져 패딩이 좌우로 간다. 실제 축은 이 런의 " +
            "detect.run.letterbox의 pad_left/pad_top 중 0이 아닌 쪽이 말한다. " +
            "⚠ **(4) 두 use case가 같은 시야를 본다고 가정한다** — 분석 치수와 처리 치수의 " +
            "종횡비가 다르면 crop이 다르다는 뜻인데 그 crop을 알려 주는 런타임 값이 없다. " +
            "두 치수는 이 블록에 나란히 실리므로 어긋나면 거기서 보인다"

    /** 두 공간의 치수가 매핑을 만들 수 있는 값인가. **0이 들어오면 값을 지어내지 않는다.** */
    fun canMap(srcW: Int, srcH: Int, processW: Int, processH: Int): Boolean =
        srcW > 0 && srcH > 0 && processW > 0 && processH > 0

    /** ① 센서 x → NDC x. [canMap]이 true일 때만 부른다. */
    fun ndcX(xSensor: Float, srcW: Int, processW: Int): Float {
        val scaleX = processW.toFloat() / srcW.toFloat()
        val px = xSensor * scaleX
        return 2f * px / processW.toFloat() - 1f
    }

    /** ① 센서 y → NDC y. [canMap]이 true일 때만 부른다. [FLIP_Y]가 참이면 부호를 뒤집는다. */
    fun ndcY(ySensor: Float, srcH: Int, processH: Int): Float {
        val scaleY = processH.toFloat() / srcH.toFloat()
        val py = ySensor * scaleY
        val ndc = 2f * py / processH.toFloat() - 1f
        return if (FLIP_Y) -ndc else ndc
    }
}
