package com.bammasil.poc.gl

import com.bammasil.poc.detect.DetectContract

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
 * ## 🔴 회전 — **`hasCameraTransform=true`면 아무도 안 돈다** (실기기 판정)
 *
 * | 무엇 | 누가 돌리나 (`hasCameraTransform=true`) |
 * |---|---|
 * | **영상** | CameraX가 `SurfaceTexture`의 `texMatrix`에 회전을 **이미 넣어 준다**. 패스1이 그 행렬로 샘플링하므로 영상은 **그것만으로 바로 선다**. present 정점 회전은 **0도** |
 * | **박스** | **회전 0도.** Preview와 ImageAnalysis가 **같은 방향 기준 위에** 있고 CameraX가 표시 방향을 처리하므로 추가 회전이 필요 없다 |
 *
 * 🔴 **이 결론은 추론이 아니라 실기기 판정이다.** 박스에 시계 90°를 걸었더니 영상은 바로
 * 섰는데(스크린샷 확정) **박스만 시계 90° 어긋났다** — 건 만큼 어긋났으니 옳은 값은 0이다.
 *
 * 🔴 **그렇게 보면 결함이 하나로 정리된다**(알려진 이슈 67): 세로 normal에서 옛 코드는
 * `targetRotation=ROTATION_0`이라 표시 회전이 0이 나와 **원래 맞았고**, cardboard에서는
 * `ROTATION_90`(상수 1)이 90도로 읽혀 **영상만 돌고 박스는 안 돌았다.** 진짜 결함은 처음부터
 * **"회전각을 `targetRotation`에서 뽑은 것" 하나뿐**이었다.
 *
 * ⚠ 그래도 [mapBox]의 회전 기계는 **남겨 둔다** — `hasCameraTransform=false` 경로에서는
 * 필요하고, 각도가 0이면 회전이 항등이라 비용이 사실상 없다.
 * 🔴 **그 경로는 이 기기에서 한 번도 밟히지 않았다 = 실기기 미검증이다.** [mapBox]의 축
 * 대응도 [BOX_ROTATION_CLOCKWISE]의 방향도 그 경로가 살아나야 화면으로 갈린다.
 *
 * ## 값을 보고 판단한다 — 로그가 있다
 *
 * [com.bammasil.poc.gl.OverlaySmoother]가 런당 처음 몇 박스에 대해 **원시 → 회전 후 → NDC**
 * 세 단계를 logcat에 남긴다(`④ 박스 매핑 #k`). 좌표계 방향 결함이 이번이 세 번째이고 세 번
 * 다 값이 없어서 추론으로 잡으려다 틀렸다 — **그 줄을 먼저 읽는다.**
 *
 * ## 🔴 세로 축 방향과 회전 부호는 **가정이다**
 *
 * 회전이 0이어도 "센서의 y=0 행이 화면 어느 쪽에 놓이는가"는 닫히지 않는다 — 패스1의 OES
 * 샘플링은 드라이버가 준 `texMatrix`를 그대로 쓰는데 그 행렬이 상하를 뒤집어 놓았을 수 있고,
 * **앱은 그것을 관측할 수단이 없다**(원소를 읽어 봐야 그것이 화면의 어느 쪽인지는 나오지
 * 않는다). 🔴 **그 뒤집힘이 실재한다는 것은 이미 실측됐다**: present에 `rotateM(-90)`
 * (NDC 수학으로는 반시계)를 걸었더니 화면에는 **시계 90°**로 나타났다 — NDC와 화면 사이에
 * **홀수 번의 뒤집힘**이 있다는 뜻이다.
 *
 * 스위치는 둘이고 **서로 독립이 아니다**: [FLIP_Y](세로 축)와 [BOX_ROTATION_CLOCKWISE]
 * (회전 방향). 화면이 이상하면 **네 조합**을 하나씩 본다.
 *
 * ⚠ 자체검사([com.bammasil.poc.detect.DetectGeometryCheck])가 확인하는 것은 **"프레임 전체
 * 박스가 NDC 전체로 간다"는 자기 일관성까지**이고 [FLIP_Y]·[BOX_ROTATION_CLOCKWISE]의
 * 참·거짓이 아니다 — 그 값들은 **눈으로만** 확인된다.
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

    /**
     * 🔴 **박스 회전의 방향을 뒤집는 자리는 여기 하나다.**
     *
     * true = `rotationDegrees`를 **시계 방향**으로 적용한다.
     *
     * 유도: `rotationDegrees`는 CameraX가 준 값이고 뜻은 **"센서 버퍼를 시계 방향으로 몇 도
     * 돌려야 바로 서는가"**다(같은 규약이 [DetectContract]의 회전 규약에 문장으로 있다).
     * 따라서 센서 프레임은 바로 선 것보다 **반시계 `rotationDegrees`**에 놓여 있고, 그 위의
     * 박스를 영상에 얹으려면 **시계 `rotationDegrees`**로 돌려야 한다. → true.
     *
     * 검산(90°, 이미지 좌표 u=오른쪽·v=아래): 센서 좌상단 `(0,0)` → 표시 `(1,0)` 우상단.
     * 이미지를 시계로 90° 돌리면 좌상단이 우상단으로 간다. ✅
     *
     * 🔴 **지금 이 스위치는 잠자고 있다.** A34는 `hasCameraTransform=true`라 박스 회전각이
     * **0**이고, 0에서는 방향이 아무 뜻도 없다(`360 - 0 = 0`). 값이 실제로 일하는 것은
     * `hasCameraTransform=false` 경로뿐이며 **그 경로는 실기기에서 한 번도 밟히지 않았다** —
     * 즉 이 값은 **미검증**이다. `PassthroughRenderer.PREVIEW_ROTATION_SIGN`과 같은 처지다.
     * 🚫 그래서 지금 이 값을 바꿔 봐야 화면이 변하지 않는다. **각도가 0인지부터 로그로 본다**
     * (`OverlaySmoother`의 `④ 박스 매핑 #k` 줄, 그리고 `session.json`의
     * `overlay.coordinate_map.box_rotation_degrees`).
     *
     * 🔴 **각도가 0이 아닌 경로에서는 화면으로만 확정된다.** [FLIP_Y]와 **독립이 아니어서**
     * (위 KDoc의 실측된 홀수 뒤집힘) 한 번에 맞히지 못할 수 있다. 박스가 영상과 90° 어긋나
     * 보이면 **여기만** 뒤집고, 그래도 안 맞으면 [FLIP_Y]와의 조합을 본다.
     * ⚠ [com.bammasil.poc.detect.DetectGeometryCheck]는 이 값의 참·거짓을 보지 않는다.
     */
    const val BOX_ROTATION_CLOCKWISE = true

    /** 박스에 회전을 **한 번도 걸지 않았다**(매핑이 한 번도 안 돌았다). */
    const val BOX_ROTATION_NOT_APPLIED = -1


    /** 사람이 읽는 식. 같은 문장이 `session.json`의 `overlay.coordinate_map.formula`로 나간다. */
    const val FORMULA =
        "입력 = ① 센서 좌표(DetectPostprocessor.Box — letterbox 역변환 + 회전 역변환까지 " +
            "끝난 값, 규약 §5-2). " +
            "🔴 **(1) 표시 방향으로 회전한다** — DetectContract.Rotation.forwardBox(시계 " +
            "box_rotation_degrees, BOX_ROTATION_CLOCKWISE=false면 반대)로 센서 박스를 회전 후 " +
            "좌표로 옮긴다. ⚠ **hasCameraTransform=true면 그 각도가 0이라 이 단계는 항등이다** " +
            "(assumptions (0) 참고). 기계가 남아 있는 것은 false 경로를 위해서다. 반사축의 길이는 센서 치수이고 결과 공간의 치수는 " +
            "rotated_w/rotated_h다(90/270이면 analysis_w/h가 자리를 바꾼다). " +
            "**회전은 정규화 공간에서 하는 것과 같다** — 회전 후 치수로 나누므로 FBO 종횡비와 " +
            "무관하고, 그래서 texMatrix가 영상에 한 것과 같은 공간에서 돈다. " +
            "🔴 **(2) 그 다음 NDC로 옮긴다**: " +
            "scaleX = process_w / rotated_w ; px = x_rot * scaleX ; " +
            "ndc_x = 2 * px / process_w - 1. " +
            "scaleY = process_h / rotated_h ; py = y_rot * scaleY ; " +
            "ndc_y = 2 * py / process_h - 1" +
            "(FLIP_Y=true면 부호를 뒤집는다 — 🔴 **회전 뒤에 건다**. 그 순서 자체도 " +
            "눈으로만 확인되는 선택이다). " +
            "🔴 **analysis_w/h는 분석 use case가 실제로 준 ImageProxy의 치수이고 Preview " +
            "해상도가 아니다** — 두 use case는 같은 ResolutionSelector를 써도 같은 값을 " +
            "받는다는 보장이 없다. 대수적으로는 process 치수가 약분되지만(ndc_x = " +
            "2*x_rot/rotated_w - 1) 두 런타임 값을 다 쓰는 형태로 적어 둔다. " +
            "실제로 건 각도는 이 블록의 box_rotation_degrees"

    /** 🔴 이 매핑에 남아 있는 **가정 전부.** 같은 문장이 `session.json`으로 나간다. */
    const val ASSUMPTIONS =
        "🔴 **(0) hasCameraTransform=true면 영상도 박스도 추가 회전이 없다.** 영상은 " +
            "CameraX가 texMatrix에 회전을 이미 넣어 줘서 패스1 샘플링만으로 바로 서고, " +
            "박스는 Preview와 ImageAnalysis가 **같은 방향 기준 위에** 있어 그대로 얹힌다. " +
            "그래서 render.preview_transform의 회전각도 이 블록의 box_rotation_degrees도 " +
            "**0이 정상이다**. " +
            "🔴 **실기기 판정이다**: 박스에 시계 90°를 걸었더니 영상은 바로 섰는데 박스만 " +
            "시계 90° 어긋났다 — 건 만큼 어긋났으니 옳은 값은 0이다. " +
            "🔴 그렇게 보면 결함이 하나로 정리된다(알려진 이슈 13/67): 진짜 결함은 처음부터 " +
            "**'회전각을 targetRotation(표시 방향 상수)에서 뽑은 것' 하나뿐**이었다. 세로 " +
            "normal은 그 값이 ROTATION_0이라 우연히 맞았고, cardboard는 ROTATION_90이 90도로 " +
            "읽혀 영상만 돌았다. " +
            "⚠ **hasCameraTransform=false 경로는 이 기기에서 한 번도 밟히지 않았다** — " +
            "그쪽의 회전 축 대응과 방향(box_rotation_clockwise)은 **실기기 미검증**이다. " +
            "🔴 값을 보려면 logcat의 '④ 박스 매핑 #k' 줄을 읽는다(런당 처음 몇 박스에 대해 " +
            "원시 → 회전 후 → NDC 세 단계를 다 남긴다). " +
            "🔴 **(1) 세로 축 방향은 여전히 가정이다(FLIP_Y=$FLIP_Y).** 회전이 붙어도 " +
            "'센서 y=0 행이 화면 어느 쪽에 놓이는가'는 **다른 질문**이라 닫히지 않는다. " +
            "패스1의 OES 샘플링은 드라이버가 준 texMatrix를 그대로 쓰는데 그 행렬이 이미 " +
            "상하를 뒤집어 놓았을 수 있고, **앱에는 그것을 관측할 수단이 없다.** " +
            "🔴 **그 뒤집힘은 추정이 아니라 실측됐다**: present에 rotateM(-90)(NDC 수학으로는 " +
            "반시계)을 걸었더니 화면에는 시계 90°로 나타났다 — NDC와 화면 사이에 **홀수 번의 " +
            "뒤집힘**이 실재한다. " +
            "**화면에서 위아래가 뒤집혀 보이면 OverlayCoordMap.FLIP_Y 하나만 바꾼다** — " +
            "매핑이 한 곳에 있는 이유가 그것이다. " +
            "⚠ geometry_selfcheck가 확인하는 것은 '프레임 전체 박스가 NDC 전체로 간다'는 " +
            "**자기 일관성까지**이고 FLIP_Y의 참·거짓이 아니다 — 그건 눈으로만 확인된다. " +
            "🔴 **(2) 가로 방향과 회전 방향도 같은 처지다** — 좌우 반전이 있었다면 이 매핑은 " +
            "그대로 틀리고 왕복 검사는 통과한다. 박스 회전의 방향은 " +
            "OverlayCoordMap.BOX_ROTATION_CLOCKWISE 하나가 쥐고 있고, 위 (1)의 뒤집힘과 " +
            "**독립이 아니라서** 한 번에 못 맞힐 수 있다 — 화면에서 (FLIP_Y, " +
            "BOX_ROTATION_CLOCKWISE) **네 조합**을 하나씩 본다. " +
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

    /**
     * [rotationDegrees]를 **실제로 걸 각도**로 바꾼다. [BOX_ROTATION_CLOCKWISE]가 false면
     * 반대 방향(= `360 - deg`)이 된다. 🔴 방향을 뒤집는 자리는 그 상수 하나뿐이고 이 함수가
     * 그것을 읽는 **유일한 곳**이다.
     *
     * ⚠ 90° 배수가 아니면 그대로 돌려준다 — 여기서 근사하지 않는다. 거부는
     * [DetectContract.rotationOf]가 한다(그럴듯한 값을 만들면 그 런의 박스가 조용히 틀어진다).
     */
    fun effectiveBoxRotationDegrees(rotationDegrees: Int): Int {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        if (BOX_ROTATION_CLOCKWISE || normalized == 0) return normalized
        return 360 - normalized
    }

    /**
     * ① 센서 박스 → ④ **처리 해상도 FBO의 NDC 박스**, 표시 방향으로 회전까지 걸어서.
     * 결과는 [out]에 `x1, y1, x2, y2` 순으로 담는다.
     *
     * 🔴 **회전은 [DetectContract.Rotation.forwardBox]를 그대로 쓴다** — 사본을 만들지
     * 않는다. 전처리(센서→회전 후)와 후처리(회전 후→센서)가 이미 그 표 하나를 공유하고
     * 있고, ④가 자기 식을 따로 적으면 **부호 하나가 어긋나는 날 어느 쪽이 틀렸는지 알 수
     * 없다.** 그 함수는 박스용 **연속(모서리) 좌표 규약**(`N − v`)이라 여기 입력과 규약이
     * 같고, **min/max로 뭉개지 않아** 모델이 낸 역전(`x1 > x2`)이 역전인 채로 보존된다.
     *
     * ⚠ **할당이 없다.** [rotation]과 [out]은 호출자가 들고 있는 것을 받는다 — 이 함수는
     * ④ H칸(`stage_h_ms`) 안에서 게시마다 불린다.
     *
     * @param rotation 이번 매핑에 쓸 회전. 각도는 [effectiveBoxRotationDegrees]를 거친 값이고
     *   `srcW`/`srcH`는 **분석 use case가 실제로 준 치수**여야 한다(Preview 치수가 아니다).
     * @param forwardOut null이 아니면 **중간 단계(회전 후, NDC 이전)**를 여기 복사한다.
     *   🔴 진단 로그 전용 출구다 — 호출자가 회전 결과를 다시 계산하지 않게 하려는 것이다.
     *   사본 식을 만들면 그 사본이 조용히 어긋나고, 그때 로그가 **틀린 값으로 안심시킨다.**
     *   ⚠ 그래서 여기 두었지 편의를 위해서가 아니다. 평시에는 null이라 비용이 널 검사뿐이다.
     */
    fun mapBox(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        rotation: DetectContract.Rotation,
        processW: Int,
        processH: Int,
        out: FloatArray,
        forwardOut: FloatArray? = null,
    ) {
        rotation.forwardBox(x1, y1, x2, y2, out)
        if (forwardOut != null) {
            forwardOut[0] = out[0]
            forwardOut[1] = out[1]
            forwardOut[2] = out[2]
            forwardOut[3] = out[3]
        }
        // 회전 **후** 프레임의 치수로 정규화한다. 90/270이면 폭과 높이가 자리를 바꿨다.
        out[0] = ndcX(out[0], rotation.rotatedW, processW)
        out[1] = ndcY(out[1], rotation.rotatedH, processH)
        out[2] = ndcX(out[2], rotation.rotatedW, processW)
        out[3] = ndcY(out[3], rotation.rotatedH, processH)
    }

    /**
     * **회전 후** x → NDC x. [canMap]이 true일 때만 부른다.
     * ⚠ [srcW]는 **회전 후 프레임의 폭**(`Rotation.rotatedW`)이다 — 회전이 0°일 때만 그것이
     * 센서 폭과 같다. [mapBox]가 그 값을 넣어 준다.
     */
    fun ndcX(xRotated: Float, srcW: Int, processW: Int): Float {
        val scaleX = processW.toFloat() / srcW.toFloat()
        val px = xRotated * scaleX
        return 2f * px / processW.toFloat() - 1f
    }

    /**
     * **회전 후** y → NDC y. [canMap]이 true일 때만 부른다. [FLIP_Y]가 참이면 부호를 뒤집는다
     * (🔴 회전 **뒤에** 걸린다 — [mapBox] 참고).
     * ⚠ [srcH]는 **회전 후 프레임의 높이**(`Rotation.rotatedH`)다.
     */
    fun ndcY(yRotated: Float, srcH: Int, processH: Int): Float {
        val scaleY = processH.toFloat() / srcH.toFloat()
        val py = yRotated * scaleY
        val ndc = 2f * py / processH.toFloat() - 1f
        return if (FLIP_Y) -ndc else ndc
    }
}
