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
 * ## 🔴 회전 — **`hasCameraTransform=true`여도 박스는 돈다** (실기기 판정 2026-08-30)
 *
 * | 무엇 | 누가 돌리나 (`hasCameraTransform=true`) |
 * |---|---|
 * | **영상** | CameraX가 `SurfaceTexture`의 `texMatrix`에 회전을 **이미 넣어 준다**. 패스1이 그 행렬로 샘플링하므로 영상은 **그것만으로 바로 선다**. present 정점 회전은 **0도** |
 * | **박스** | **`rotationDegrees`만큼 돈다**(A34 세로에서 90°). 박스는 `texMatrix`를 **타지 않으므로** CameraX가 영상에 해 준 회전을 ④가 직접 걸어야 한다 |
 *
 * 🔴 **불변식 하나로 적힌다.** ④ 오버레이는 present **앞의** FBO_A에 그려지므로(패스8)
 * present가 돌면 영상과 박스가 **함께** 돈다. 따라서 센서→화면의 총 회전은 이렇게 나뉜다:
 * ```
 * box_rotation_degrees ≡ rotationDegrees + present_rotation_degrees  (mod 360)
 * ```
 * present는 영상과 박스를 함께 돌리는 **공통 모드**라 박스 몫에서 **빠지지 않는다** — 박스가
 * 메우는 것은 `texMatrix`가 영상에 건 회전 T 하나이고 `T = rotationDegrees + present`다.
 * ⚠ **옛 식 `박스 + present ≡ rotationDegrees`는 반증됐다**(2026-08-31) — present를 박스 몫에서
 * 빼는 식이라 카드보드에서 박스가 0으로 떨어져 90°가 통째로 빠졌다. 세로는 present가 0이라
 * 두 식의 값이 같아서 드러나지 않았다. `session.json`의 `render.rotation_budget`이 새 식을
 * 대조하되, ⚠ **그 검사는 박스를 같은 입력에서 만들어 비교하므로 항진명제에 가깝다** —
 * `consistent=true`가 "박스가 맞게 그려졌다"는 뜻이 **아니다.**
 *
 * 🟢 **카드보드(SBS)도 같은 식으로 성립한다.** 그 경로는 `targetRotation = ROTATION_90`이라
 * present가 90을 걸고(팀원 원본 `e387ae9`의 의미) `rotationDegrees`는 0이므로 박스는
 * `0 + 90 = 90`이다 — 세로의 `90 + 0 = 90`과 **같은 값**이다. T(= texMatrix가 영상에 건 회전)가
 * 센서 장착각이라 표시 방향과 무관한 상수이기 때문이다.
 *
 * ✅ **카드보드에서 영상은 바로 선다 — 모든 arm에서.** present가 90을 걸기 때문이고,
 * 처리 arm의 2D 눈이 그 90°를 실어 나를 유니폼을 갖도록 정점 셰이더를 메웠다(실측 확정,
 * 2026-08-30 — 그전에는 `passthrough`만 정상이었다).
 * 🏆 **카드보드의 ④ 박스도 맞는다**(2026-08-31 야외 육안) — 박스가 `0 + 90 = 90`을 지도록
 * 식을 고친 결과다. ⚠ 카드보드 경로는 **스탬프된 런이 아직 0건**이라 근거가 육안뿐이다.
 * 카드보드의 기하·튜닝 코드는 팀원 소유라 우리가 건드리지 않는다.
 *
 * 🔴 **실기기 판정이다.** 옛 코드는 두 분기가 서로 바뀌어 있어 `true` 경로에서 `0 + 0 = 0`이
 * 나왔다. 그러면 센서 가로 좌표(1280×720)가 세로 화면(1080×2340)에 그대로 얹혀 **정규화
 * 좌표가 전치된다**(x↔y 맞바꿈 = 회전 + 거울). 근거 셋:
 * - **런 `20260830_194714`**(결함 상태): 원시 박스가 센서 좌표에서 **가로로 길었는데**
 *   (3~4:1 — 세로로 긴 볼라드가 눕힌 버퍼에서 그렇게 나오는 것이 옳다) 화면에는 거의
 *   정사각으로 **왼쪽에 세로 일렬**로 늘어섰다. 지금 매핑을 걸면 그 박스들이 같은 프레임
 *   스크린샷의 실제 볼라드에 **가로 오차 8~32px**(1080px 화면)로 앉는다.
 * - **같은 기기 5분 간격 대조**: `21:19` 옛 APK가 `④ 박스 회전각=0`, `21:26` 새 APK가
 *   `④ 박스 회전각=90`. 입력은 동일하다(`rotation_degrees=90 has_camera_transform=true`).
 * - **런 `20260830_213737`**(수정 후): 60.5초·1540프레임, 박스를 그린 프레임 550개이고
 *   그중 **동시 4~6개가 350프레임**. 사용자가 화면에서 정상 표시를 확인했다.
 *
 * 🔴 **옛 서술이 왜 통과했는지 남긴다**(이슈 70). 여기에는 *"박스에 시계 90°를 걸었더니
 * 박스만 어긋났다 — 그러니 0이 옳다"*고 적혀 있었다. 그 실험은 **[FLIP_Y]가 false인 채로**
 * 회전만 건 것이라 **세로만 거울**인 결과가 나왔고, 두 스위치가 독립이 아니라는 사실을
 * 놓쳐서 "회전이 틀렸다"로 읽혔다. **판정 장면이 볼라드 1개였던 것도 컸다** — 전치는
 * 대각선 위의 점을 그대로 두므로 **단일 박스 장면은 이 결함을 원리적으로 못 잡는다.**
 *
 * ⚠ **`hasCameraTransform=false` 경로는 이 기기에서 한 번도 밟히지 않았다 = 실기기
 * 미검증이다.** 그쪽에서 박스 회전각은 0이고 도는 것은 present뿐이다.
 *
 * ## 값을 보고 판단한다 — 로그가 있다
 *
 * [com.bammasil.poc.gl.OverlaySmoother]가 런당 처음 몇 박스에 대해 **원시 → 회전 후 → NDC**
 * 세 단계를 logcat에 남긴다(`④ 박스 매핑 #k`). 좌표계 방향 결함이 이번이 세 번째이고 세 번
 * 다 값이 없어서 추론으로 잡으려다 틀렸다 — **그 줄을 먼저 읽는다.**
 *
 * ## 세로 축 방향과 회전 부호 — **화면으로 갈렸다 (2026-08-30)**
 *
 * "센서의 y=0 행이 화면 어느 쪽에 놓이는가"는 앱에 관측 수단이 없어 오래 가정이었다.
 * **이제 값이 있다.** 진단용으로 **센서 좌표계에 L자 마커**를 정의해 프로덕션 [mapBox]에
 * 그대로 태우고 화면을 찍었다(런 `20260830_212611`, A34 세로). 마커가 예측 자리에
 * **3~12px** 오차로 앉았고, 같은 사진에서 종이의 `F`가 똑바로 읽혀 영상 경로도 함께
 * 확인됐다. ⚠ **그 마커는 판정을 마치고 제거됐다 — 지금 코드에 없다.** 다시 필요하면
 * 다시 만든다(센서 좌표로 정의하고, 사본 식을 만들지 말고 [mapBox]를 태울 것).
 *
 * 🔴 **확정된 것: FBO NDC ↔ 화면은 GL 표준이고 여분의 뒤집힘이 없다.** 마커는 `texMatrix`를
 * 타지 않는 경로로 그려지므로, 뒤집힘이 있었다면 예측 자리에 못 온다. 확정된 조합은
 * **[FLIP_Y]=true · [BOX_ROTATION_CLOCKWISE]=true · 회전 90°**다.
 *
 * ⚠ `PassthroughRenderer.PREVIEW_ROTATION_SIGN` 근처에 *"NDC와 화면 사이에 홀수 번의
 * 뒤집힘이 실재한다"*는 옛 관측이 남아 있고 **위 실측과 충돌한다.** 유력한 해석은 **부호
 * 규약 오독**이다 — `rotateM`의 `-90`은 NDC 수학에서 **이미 시계 방향**이라, 화면에 시계
 * 90°로 보인 것은 *뒤집힘이 없을 때* 예상되는 결과다. 🔴 **이것은 가설이며 그 경로를 다시
 * 태워 본 것이 아니다.** 사실(마커가 예측 자리에 앉았다)과 가설(옛 관측은 오독으로 보인다)을
 * 갈라 적어 둔다 — 서로 반대되는 "관측된 사실" 둘을 방치한 것이 이 결함을 세 번 만들었다.
 *
 * ⚠ 자체검사([com.bammasil.poc.detect.DetectGeometryCheck])는 **검사 6**이 [mapBox]를
 * 회전까지 태워 "정순 입력이 정순으로 나오는가"를 보지만, [FLIP_Y]·[BOX_ROTATION_CLOCKWISE]의
 * **참·거짓은 여전히 보지 않는다** — 그건 화면으로만 갈린다.
 */
object OverlayCoordMap {

    /**
     * 🔴 **true = 센서 y가 커질수록 NDC y는 작아진다(뒤집는다).** 이것이 표준이다 — 이미지
     * 좌표는 y가 **아래로** 자라고 NDC는 y가 **위로** 자라므로 부호를 뒤집는 쪽이 맞다.
     *
     * 🔴 **화면으로 확정됐다(2026-08-30).** 옛 값은 `false`였고 그것이 결함의 **절반**이었다
     * (나머지 절반은 박스 회전각 0 — 위 KDoc §회전). 근거는 위 KDoc §세로 축 방향의 L자
     * 마커 판정(3~12px)과 수정 후 런 `20260830_213737`이다.
     *
     * ⚠ 옛 `false`의 근거였던 *"전체화면 quad가 `uv.v = 0`을 `NDC y = -1`에 놓으므로 기존
     * 코드와 일관된 쪽"*은 **유비였지 관측이 아니었다.** 게다가 그때 화면에 있던 것은 정적
     * 더미 오버레이(상하 대칭 격자)라 **세로 뒤집힘을 드러낼 수 없었다** — 그래서 이 값이
     * 오래 틀린 채로 통과했다.
     *
     * 🔴 **이 값이 true이려면 [mapBox]의 y 끝점 교환이 함께 있어야 한다.** 반사는 두 끝점의
     * 순서까지 옮긴다(규약 §5-1). 교환이 없으면 모든 박스가 역전으로 나와 `OverlaySmoother`의
     * IoU가 전멸하고(연결이 끊겨 박스가 깜빡인다) 좌·우 세로 띠가 높이 0으로 붕괴한다
     * (박스가 `▭`가 아니라 `=`로 그려진다). [com.bammasil.poc.detect.DetectGeometryCheck]의
     * **검사 6**이 그 자리를 본다.
     */
    const val FLIP_Y = true

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
     * 🔴 **이 스위치는 A34에서 실제로 일한다(2026-08-30부터).** `hasCameraTransform=true`
     * 경로의 박스 회전각이 **90**이 됐기 때문이다(위 KDoc §회전의 회전 예산 불변식). 옛
     * 서술은 *"각도가 0이라 잠자고 있다"*였는데 **그 전제가 사라졌다.**
     *
     * 🔴 **true가 옳다는 것은 화면으로 확정됐다** — 위 KDoc §세로 축 방향의 L자 마커 판정과
     * 수정 후 런 `20260830_213737`. [FLIP_Y]와 **짝으로** 확정된 것이며 둘은 독립이 아니다:
     * 회전만 뒤집고 [FLIP_Y]를 그대로 두면 **가로는 맞고 세로만 거울**이 된다 — 옛 라운드가
     * 정확히 그 상태를 보고 "회전이 틀렸다"로 오독했다. 🚫 **한 스위치만 만져서는 못
     * 빠져나온다.**
     *
     * 🔴 **여기를 false로 뒤집으면 `render.rotation_budget`이 `consistent=false`를 낸다.**
     * 그 블록이 싣는 `box_rotation_degrees`는 [effectiveBoxRotationDegrees]를 **거친 뒤의**
     * 각도라 `360 − 90 = 270`이 되고 합이 `camera`와 어긋나기 때문이다. **그 false는 회전
     * 예산의 결함이 아니라 이 스위치를 뒤집은 결과다.**
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
            "좌표로 옮긴다. 🔴 **hasCameraTransform=true면 그 각도가 rotationDegrees이고 이 " +
            "단계가 실제로 일한다**(A34 세로에서 90°) — 박스는 texMatrix를 타지 않으므로 " +
            "CameraX가 영상에 해 준 회전을 여기서 직접 건다. false면 present가 대신 돌리므로 " +
            "각도가 0이고 이 단계는 항등이다(assumptions (0) 참고). 반사축의 길이는 센서 치수이고 결과 공간의 치수는 " +
            "rotated_w/rotated_h다(90/270이면 analysis_w/h가 자리를 바꾼다). " +
            "**회전은 정규화 공간에서 하는 것과 같다** — 회전 후 치수로 나누므로 FBO 종횡비와 " +
            "무관하고, 그래서 texMatrix가 영상에 한 것과 같은 공간에서 돈다. " +
            "🔴 **(2) 그 다음 NDC로 옮긴다**: " +
            "scaleX = process_w / rotated_w ; px = x_rot * scaleX ; " +
            "ndc_x = 2 * px / process_w - 1. " +
            "scaleY = process_h / rotated_h ; py = y_rot * scaleY ; " +
            "ndc_y = 2 * py / process_h - 1" +
            "(FLIP_Y=true면 부호를 뒤집고 🔴 **y 두 끝점의 순서도 함께 바꾼다** — 반사는 " +
            "순서까지 옮기는 것이 규약 §5-1이고, 안 바꾸면 모든 박스가 역전으로 나와 IoU가 " +
            "전멸한다. 🔴 **회전 뒤에 건다**). " +
            "🔴 **analysis_w/h는 분석 use case가 실제로 준 ImageProxy의 치수이고 Preview " +
            "해상도가 아니다** — 두 use case는 같은 ResolutionSelector를 써도 같은 값을 " +
            "받는다는 보장이 없다. 대수적으로는 process 치수가 약분되지만(ndc_x = " +
            "2*x_rot/rotated_w - 1) 두 런타임 값을 다 쓰는 형태로 적어 둔다. " +
            "실제로 건 각도는 이 블록의 box_rotation_degrees"

    /** 🔴 이 매핑에 남아 있는 **가정 전부.** 같은 문장이 `session.json`으로 나간다. */
    const val ASSUMPTIONS =
        "🔴 **(0) 이제 가정이 아니다 — 회전 예산은 기계가 대조한다.** ④ 오버레이는 present " +
            "앞의 FBO_A에 그려지므로(패스8) present가 돌면 영상과 박스가 함께 돈다. 따라서 " +
            "**box_rotation_degrees ≡ rotationDegrees + present_rotation_degrees (mod 360)** " +
            "이며 render.rotation_budget이 이 식을 매 런 대조한다. present는 영상과 박스를 " +
            "함께 돌리는 공통 모드라 박스 몫에서 빠지지 않는다 — 박스가 메우는 것은 texMatrix가 " +
            "영상에 건 회전 T 하나이고 T = rotationDegrees + present다. " +
            "⚠ 옛 식 'box + present ≡ rotationDegrees'는 반증됐다(2026-08-31): present를 박스 " +
            "몫에서 빼는 식이라 카드보드에서 박스가 0으로 떨어져 90°가 통째로 빠졌고, 세로는 " +
            "present가 0이라 두 식의 값이 같아 드러나지 않았다. " +
            "⚠ hasCameraTransform=false 경로는 이 기기에서 한 번도 밟히지 않아 미검증이다. " +
            "🟢 **카드보드(SBS)도 같은 식으로 성립한다** — target_rotation=ROTATION_90이라 " +
            "present가 90을 거는데(팀원 원본 e387ae9의 의미) rotation_degrees는 0이므로 " +
            "박스는 0+90=90이고, 세로의 90+0=90과 **같은 값**이다(T가 센서 장착각이라 " +
            "표시 방향과 무관한 상수다). " +
            "✅ **카드보드에서 영상은 모든 arm에서 바로 선다**(처리 arm의 2D 눈이 그 90°를 " +
            "실어 나를 유니폼을 갖도록 정점 셰이더를 메웠다 — 실측 확정 2026-08-30, 그전에는 " +
            "passthrough만 정상이었다). 🏆 **카드보드의 ④ 박스도 맞는다**(2026-08-31 야외 육안, " +
            "박스가 0+90=90을 지도록 식을 고친 결과) — ⚠ 다만 카드보드 경로는 **스탬프된 런이 " +
            "아직 0건**이라 근거가 육안뿐이다. 카드보드의 기하·튜닝 코드는 " +
            "팀원 소유라 건드리지 않는다. " +
            "🔴 **실기기 판정이다(2026-08-30)**: 옛 코드는 두 분기가 서로 바뀌어 있어 true " +
            "경로에서 0+0=0이 나왔고, 그러면 센서 가로 좌표가 세로 화면에 그대로 얹혀 정규화 " +
            "좌표가 **전치**된다(x↔y 맞바꿈 = 회전 + 거울). 근거: 결함 상태 런 20260830_194714 " +
            "(원시 박스가 센서에서 가로로 긴데 화면에는 정사각으로 왼쪽에 세로 일렬) · 같은 " +
            "기기 5분 간격 대조(옛 APK 박스 회전각=0 → 새 APK 90, 입력 동일) · 수정 후 런 " +
            "20260830_213737(60.5초·1540프레임, 동시 4~6개가 350프레임, 화면 정상 확인). " +
            "🔴 **옛 서술('0이 정상이다 / 90을 걸었더니 박스만 어긋났다')은 반증됐다**(이슈 " +
            "13/67/70). 그 실험은 FLIP_Y=false인 채로 회전만 건 것이라 세로가 거울이었고, 판정 " +
            "장면도 볼라드 1개였다 — **전치는 대각선 위의 점을 그대로 두므로 단일 박스 장면은 " +
            "이 결함을 원리적으로 못 잡는다.** " +
            "⚠ **hasCameraTransform=false 경로는 이 기기에서 한 번도 밟히지 않았다** — " +
            "그쪽의 회전 축 대응은 **실기기 미검증**이다. " +
            "🔴 값을 보려면 logcat의 '④ 박스 매핑 #k' 줄을 읽는다(런당 처음 몇 박스에 대해 " +
            "원시 → 회전 후 → NDC 세 단계를 다 남긴다). " +
            "🔴 **(1) 세로 축 방향도 화면으로 갈렸다(FLIP_Y=$FLIP_Y).** 센서 좌표계에 정의한 " +
            "L자 마커를 프로덕션 mapBox에 태워 찍었더니(런 20260830_212611) 예측 자리에 " +
            "**3~12px** 오차로 앉았고 같은 사진에서 종이의 F가 똑바로 읽혔다. 마커는 " +
            "texMatrix를 타지 않는 경로라 **FBO NDC↔화면에 여분의 뒤집힘이 없다**는 뜻이다. " +
            "⚠ 그 마커는 판정을 마치고 제거됐다 — 지금 코드에 없다. " +
            "⚠ PassthroughRenderer의 PREVIEW_ROTATION_SIGN 근처에 '홀수 번의 뒤집힘이 " +
            "실재한다'는 옛 관측이 남아 위 실측과 충돌한다. 유력한 해석은 **부호 규약 오독**" +
            "(rotateM의 -90은 NDC 수학에서 이미 시계 방향이다)이지만 🔴 **이것은 가설이며 그 " +
            "경로를 다시 태워 본 것이 아니다.** " +
            "⚠ geometry_selfcheck의 검사 6이 mapBox를 회전까지 태워 '정순 입력이 정순으로 " +
            "나오는가'를 보지만, FLIP_Y·BOX_ROTATION_CLOCKWISE의 **참·거짓은 여전히 안 본다** " +
            "— 그건 화면으로만 갈린다. " +
            "🔴 **(2) 회전 방향(BOX_ROTATION_CLOCKWISE=true)도 같은 사진으로 갈렸다** — 다만 " +
            "FLIP_Y와 **독립이 아니다**: 회전만 뒤집고 FLIP_Y를 그대로 두면 가로는 맞고 " +
            "**세로만 거울**이 되며, 옛 라운드가 정확히 그 상태를 '회전이 틀렸다'로 오독했다. " +
            "🚫 **한 스위치만 만져서는 못 빠져나온다** — 다시 어긋나 보이면 (FLIP_Y, " +
            "BOX_ROTATION_CLOCKWISE) 네 조합을 한 장으로 가르는 도구부터 만든다. " +
            "⚠ 이 값을 false로 뒤집으면 rotation_budget이 consistent=false를 내는데, 그것은 " +
            "회전 예산의 결함이 아니라 스위치를 뒤집은 결과다(box가 360−90=270이 된다). " +
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
        out[2] = ndcX(out[2], rotation.rotatedW, processW)
        // 🔴 **[FLIP_Y]는 반사다 — 반사가 뒤집는 축에서는 두 끝점의 순서까지 함께 옮긴다.**
        //    같은 규칙이 [DetectContract.Rotation.forwardBox]의 축 대응표에 이미 있고
        //    (`x1' = N − x2`), 규약 §5-1이 *"이것을 안 하면 90/270°에서 모든 박스가
        //    역전으로 나온다"*고 적어 둔 그 규칙이다. [ndcY]는 점 함수라 그 짝을 모른다 —
        //    **교환은 박스를 아는 이 자리 하나에서만** 한다(축별 단독 호출자가 있어
        //    [ndcY] 안에서 하면 그쪽 기대값이 흔들린다).
        //    🔴 교환이 없으면 y가 역전된 박스가 하류로 나가고 **눈에 띄게 망가진다**:
        //      (1) [com.bammasil.poc.gl.OverlaySmoother]의 IoU가 음수 폭·높이를 0으로
        //          떨어뜨려 **모든 쌍의 IoU가 0** → 트랙 연결이 전멸하고 박스가 깜빡인다.
        //      (2) [HighlightOverlay]의 좌·우 띠가 `minOf/maxOf` 클램프로 둘 다 midY에
        //          붕괴해 높이 0이 된다 → 박스가 `▭`가 아니라 `=`로 그려진다.
        //    ⚠ 교환은 **무조건**이라 모델이 낸 진짜 역전 박스를 지우지 않는다(역전은 역전인
        //      채로 남는다 — min/max가 아니다). 게시자가 역전을 이미 걸러 세므로
        //      (`rejected_inverted`) 실제 입력은 항상 정순이고, 이 성질은 자체검사
        //      ([com.bammasil.poc.detect.DetectGeometryCheck] 검사 6)이 지킨다.
        //    ⚠ 지역 float 둘뿐이라 **할당이 없다**(④ H칸 안에서 게시마다 불린다).
        val ndcY1 = ndcY(out[1], rotation.rotatedH, processH)
        val ndcY2 = ndcY(out[3], rotation.rotatedH, processH)
        if (FLIP_Y) {
            out[1] = ndcY2
            out[3] = ndcY1
        } else {
            out[1] = ndcY1
            out[3] = ndcY2
        }
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
