package com.bammasil.poc.detect

import com.bammasil.poc.BuildConfig
import com.bammasil.poc.gl.OverlayClassColors

/**
 * ③ 탐지의 **계약값·가정·어휘**를 한 곳에 모은다.
 *
 * 원칙은 `INTERFACES.md` 공통원칙 1이다: *"런타임은 아무것도 추측하지 않는다. 하드코딩된
 * 가정(해상도·정규화 상수·클래스 순서)은 전부 버그의 씨앗이다."* 그래서 이 파일에는
 * **해상도도 클래스 순서도 상수로 없다** — 실측값은 [DetectRuntime]이 그래프와 파일에서
 * 읽고, 여기 있는 것은 그 실측을 **무엇과 대조하는가**(선언값의 출처)와, 기계로 읽을 수
 * 없어 **가정으로 남길 수밖에 없는 것**뿐이다.
 */
object DetectContract {

    /** `getExternalFilesDir(null)` 아래 모델을 두는 하위 디렉토리. adb push 대상 경로다. */
    const val MODELS_SUBDIR = "models"

    // ── 선언값(대조 기준). 출처는 커밋된 metadata.json 하나다 ─────────────────
    // 🔴 **앱은 이 값을 로그에 베껴 쓰지 않는다.** sha256도 클래스도 shape도 전부 앱이
    //    실측하고, 이 값들은 "그 실측이 계약과 맞는가"를 묻는 데만 쓴다. 베껴 쓰면 그건
    //    사실이 아니라 주장이다.

    /**
     * 빌드가 읽은 metadata의 저장소 상대 경로(예: 현재 배선인
     * `"models/0826/bammasil_det_c4e_s3_11n_640-INT8/bammasil_det_c4e_s3_11n_640-INT8_metadata.json"`)
     * 또는 빌드 시점에 못 읽었으면 `"unavailable"`.
     *
     * ⚠ **파일명이 `metadata.json`이라고 가정하지 않는다** — 인수 패키지마다 다르다
     * (build.gradle.kts의 `detectMetadataFileName`).
     *
     * 🔴 **못 읽은 경우 이 값으로 "어디에 파일을 두라"고 안내할 수 없다** — 그때는
     * [expectedMetadataPath] / [expectedModelDir]를 쓴다(읽기 성공과 무관하게 기대 경로다).
     */
    val declaredSource: String get() = BuildConfig.DETECT_DECLARED_SOURCE

    /**
     * 모델 패키지 디렉토리(저장소 루트 기준 상대). **adb push 안내가 쓰는 값**이고
     * [declaredSource]와 달리 metadata를 못 읽어도 비지 않는다.
     *
     * 🔴 사본을 만들지 않는다 — 생산자는 `build.gradle.kts`의 `detectModelDir` 하나다.
     */
    val expectedModelDir: String get() = BuildConfig.DETECT_MODEL_DIR

    /** 기대 metadata 경로. 생산자는 `build.gradle.kts`의 `detectMetadataRelPath` 하나다. */
    val expectedMetadataPath: String get() = BuildConfig.DETECT_METADATA_PATH

    val declaredFileName: String get() = BuildConfig.DETECT_MODEL_FILE
    val declaredSha256: String get() = BuildConfig.DETECT_MODEL_SHA256
    val declaredInputName: String get() = BuildConfig.DETECT_INPUT_NAME
    val declaredInputShape: String get() = BuildConfig.DETECT_INPUT_SHAPE
    val declaredOutputName: String get() = BuildConfig.DETECT_OUTPUT_NAME
    val declaredOutputShape: String get() = BuildConfig.DETECT_OUTPUT_SHAPE

    /** `"0=person,1=stairs"` 형식. 인덱스 순으로 정렬돼 있다(빌드 스크립트가 정렬한다). */
    val declaredClasses: String get() = BuildConfig.DETECT_MODEL_CLASSES

    /** 빌드 시점에 metadata.json을 못 읽으면 이 값이 들어온다. 그 상태로는 대조가 불가능하다. */
    const val UNKNOWN = "unknown"

    // ── 후처리 임계 (앱이 실제로 **쓰는** 값. 위 대조 기준들과 성격이 다르다) ──
    // 🔴 **파싱 실패를 0.0으로 뭉개지 않는다.** conf가 0.0이면 전 앵커가 통과해 NMS가
    //    8400개를 물고, 그 런의 G는 "그럴듯하지만 다른 것을 잰 숫자"가 된다.

    /**
     * 후처리가 **실제로 쓰는** conf. 못 읽었거나 파싱 실패면 **null**.
     *
     * 🔴 **`metadata.json`의 선언값이 아닐 수 있다.** 오버라이드가 걸려 있으면 그 값이고
     * ([confOverrideActive]) 선언값은 [confDeclaredInMetadata]에 따로 있다 — 두 값을 한 곳에
     * 담으면 "어느 임계로 잰 숫자인가"에 답할 수 없다. 오버라이드의 근거 문장은
     * [confOverrideSource]다.
     */
    val confThreshold: Float?
        get() = BuildConfig.DETECT_CONF_THRESHOLD.toFloatOrNull()

    /**
     * `metadata.json`이 **선언한** conf. 후처리가 쓰는 값이 아니다([confThreshold]와 비교용).
     * 못 읽었거나 파싱 실패면 null.
     */
    val confDeclaredInMetadata: Float?
        get() = BuildConfig.DETECT_CONF_DECLARED.toFloatOrNull()

    /** 오버라이드 원문. **빈 문자열이면 오버라이드가 없다**는 뜻이다. */
    val confOverrideRaw: String get() = BuildConfig.DETECT_CONF_OVERRIDE

    /** conf 오버라이드가 걸린 런인가. `session.json`의 `detect.postprocess`로 나간다. */
    val confOverrideActive: Boolean get() = confOverrideRaw.isNotEmpty()

    /** 오버라이드를 왜 걸었는가(사람이 읽는 문장). 생산자는 `build.gradle.kts` 하나다. */
    val confOverrideSource: String get() = BuildConfig.DETECT_CONF_OVERRIDE_SOURCE

    /** `metadata.json`의 `inference.iou`. 못 읽었거나 파싱 실패면 **null**. */
    val iouThreshold: Float?
        get() = BuildConfig.DETECT_IOU_THRESHOLD.toFloatOrNull()

    /** 임계를 못 읽었으면 그 사유(사람이 읽는 문장), 읽었으면 null. */
    val thresholdFailure: String?
        get() {
            // 🔴 **오버라이드가 걸렸는데 파싱이 안 되면 런을 거부한다.** 조용히 선언값으로
            //    되돌아가면 session.json이 "0.25로 쟀다"고 말하는데 실제로는 0.35로 잰 런이
            //    되고, 그 거짓은 나중에 되짚을 수 없다(위 conf=0.0 함정과 같은 취지다).
            if (confOverrideActive && confOverrideRaw.toFloatOrNull() == null) {
                return "🔴 conf 오버라이드를 숫자로 읽지 못했다 — override=\"$confOverrideRaw\" " +
                    "(출처: build.gradle.kts의 detectConfOverride). 선언값 " +
                    "\"${BuildConfig.DETECT_CONF_DECLARED}\"으로 되돌아가지 않는다: 그러면 " +
                    "이 런의 임계가 로그와 달라지므로 후처리를 시작하지 않는다"
            }
            if (confThreshold != null && iouThreshold != null) return null
            return "🔴 후처리 임계를 숫자로 읽지 못했다 — conf=\"${BuildConfig.DETECT_CONF_THRESHOLD}\" " +
                "iou=\"${BuildConfig.DETECT_IOU_THRESHOLD}\" (출처 ${declaredSource}의 inference 블록). " +
                "0으로 뭉개면 **전량 통과하는 임계**가 되어 G가 다른 것을 재게 되므로 " +
                "후처리를 시작하지 않는다"
        }

    /**
     * `metadata.json`을 못 읽었을 때 [declaredSource]에 들어오는 값.
     * **생산자는 `build.gradle.kts`의 `detectDeclaredSource`다** — 두 곳이 갈리면 아래
     * [declaredMissing]이 조용히 false가 되어 대조 없는 런이 통과한다.
     */
    const val SOURCE_UNAVAILABLE = "unavailable"

    /** 선언값을 하나라도 못 읽었으면 true — 이 경우 ③ arm의 런을 거부한다. */
    val declaredMissing: Boolean
        get() = declaredSource == SOURCE_UNAVAILABLE ||
            declaredSha256 == UNKNOWN ||
            declaredClasses == UNKNOWN ||
            declaredInputShape == UNKNOWN ||
            declaredOutputShape == UNKNOWN

    // ── EP 어휘 ───────────────────────────────────────────────────────────
    // `lib/frame_log.py`의 `DETECT_EPS`와 **글자까지** 같아야 한다. 어휘가 갈리면
    // 같은 EP가 "NNAPI"/"nnapi"/"ort_nnapi"로 나뉘어 모든 비교가 "조건 다름"이 된다.
    // ⚠ **QNN은 없다** — 측정 기기 A34가 MediaTek이라 이 기기에서 불가능하다.

    const val EP_CPU = "cpu"
    const val EP_NNAPI = "nnapi"

    /**
     * XNNPACK EP. `OrtEnvironment.getAvailableProviders()`가 이 AAR에 실제로 들어 있음을
     * 보여 줬고(CPU/NNAPI/WEBGPU/XNNPACK), NNAPI가 CPU보다 느렸던 실측 뒤 남은 후보다.
     *
     * 🔴 **`lib/frame_log.py`의 `DETECT_EPS`에 이 토큰이 없으면** 하네스가 "어휘 밖 EP"
     * 참고를 붙이고 `run_session.py`의 계획 어휘 검사가 막는다 — 하네스 트랙에 함께 등록할 것.
     */
    const val EP_XNNPACK = "xnnpack"

    /** 🔴 **판별하지 못했다는 뜻이며 "CPU였다"가 아니다.** 요청값을 베껴 넣는 자리가 아니다. */
    const val EP_UNKNOWN = "unknown"

    // ── XNNPACK 세션 옵션 ─────────────────────────────────────────────────

    /**
     * XNNPACK EP에 넘기는 provider option. **비어 있다 = ORT 기본값을 그대로 썼다.**
     *
     * 🔴 **값을 지어내지 않았다.** ORT 문서는 XNNPACK에 `intra_op_num_threads`를 함께 주고
     * 세션 쪽 intra-op을 1로 낮추라고 안내하지만, 그 숫자는 어느 계약에도 없고 이 기기에서
     * 재 본 적도 없다. 근거 없는 스레드 수를 넣으면 이 arm과 `detect_cpu`의 차이가
     * **EP 차이 + 스레딩 차이**가 되어 이번 라운드의 질문("XNNPACK이 CPU보다 빠른가")에
     * 답할 수 없게 된다 — 세션 옵션을 짝 arm과 글자 그대로 같게 두는 쪽을 택했다.
     * 실제로 쓴 값은 `session.json`의 `detect.ep.provider_options`에 그대로 나간다.
     */
    val XNNPACK_PROVIDER_OPTIONS: Map<String, String> = emptyMap()

    /** 위 선택을 사람이 읽는 문장으로. `session.json`에 함께 실린다. */
    const val XNNPACK_THREAD_OPTIONS_NOTE =
        "⚠ **스레드 옵션을 하나도 건드리지 않았다(ORT 기본값).** provider option은 빈 맵이고 " +
            "세션의 intra/inter-op 스레드 수도 설정하지 않았다 — detect_cpu arm과 **글자 " +
            "그대로 같은 세션 옵션**이며 다른 것은 addXnnpack 호출 하나뿐이다. " +
            "ORT 문서는 XNNPACK에 provider option `intra_op_num_threads`를 주고 세션 " +
            "intra-op을 1로 낮추라고 안내하지만, **그 숫자는 계약에도 없고 이 기기에서 잰 적도 " +
            "없다.** 지어 넣으면 arm 차분이 EP 차이가 아니라 스레딩 차이를 섞게 된다. " +
            "🔴 그러므로 이 런은 '기본 스레딩에서의 XNNPACK'이며 **XNNPACK의 최적 구성이 " +
            "아니다** — 스레드 수를 흔드는 것은 별 라운드의 질문이다"

    // ── EP 판별 수단 이름(`resolution_method`) ─────────────────────────────

    /**
     * 1순위. **프로브 세션**의 ORT 프로파일 JSON에서 노드별 provider를 세어 판별했다.
     *
     * 왜 측정 세션이 아니라 프로브 세션인가: 프로파일러는 노드마다 기록을 남겨 추론 시간에
     * 자기 비용을 얹는다. 그래서 `detect_cpu`/`detect_nnapi`/`detect_xnnpack`(시간을 인용하는
     * arm)의 측정 세션에는 프로파일러를 켤 수 없는데, 그러면 그 arm이 영원히 `unknown`이 된다.
     * 프로브 세션은 **같은 모델·같은 EP 요청·같은 옵션**으로 따로 연 세션이고 파티셔닝은
     * 그 입력들의 결정적 함수이므로, 여기서 나온 배치가 측정 세션의 배치다.
     * ⚠ 다만 **같은 세션 객체에서 관측한 것이 아니다** — 그 사실을 이 이름이 말한다.
     */
    const val METHOD_PROFILE_PROBE = "ort_profile_node_providers (probe session)"

    /** 판별 실패. 프로파일 이벤트를 하나도 읽지 못했을 때. */
    const val METHOD_NONE = "none"

    // ── 기계로 읽을 수 없는 것 = 가정 ─────────────────────────────────────
    // `RenderArm`의 `*_PROVENANCE` / `*_DEVIATION` 관행 그대로다. 조용히 굳으면 나중에
    // 박스가 어긋날 때 원인을 못 찾는다.

    /**
     * 🔴 **letterbox 패딩을 어느 쪽에 붙이는가는 어디에도 기계로 적혀 있지 않다.**
     *
     * `metadata.json`의 `preprocess.resize`는 `"letterbox (종횡비 유지 + 회색 114 패딩)"`
     * 이라는 **한국어 산문**이고 `align` 키가 아예 없다. `INTERFACES.md` §A-2의
     * "letterbox pad 위치: **상하 균등 분배(center)**"는 여전히 `☐` 미확정 칸이다.
     * 그래서 center는 **제안값이며 확정 계약이 아니다.**
     *
     * ⚠ 이게 틀리면 박스가 통째로 **패딩이 붙는 축 방향으로** 밀린다.
     * 🔴 **그 축은 회전 뒤에 정해진다** — §A-2는 "상하"라고 적었지만 그건 소스가 가로로 긴
     * (16:9) 프레임을 그대로 letterbox 할 때의 이야기다. 90/270°로 돌리면 720p가 **세로로
     * 길어져 패딩이 좌우로 가므로 밀리는 방향도 가로**다. [Rotation]이 붙은 뒤로는
     * "세로"라고 못 박을 수 없다.
     * 🔴 **이번 라운드부터 이 가정을 실제로 쓴다**([LETTERBOX_ALIGN]) — 그래서 가정이라는
     * 사실이 `session.json`에 계속 남아야 한다. **§A-2는 여전히 ☐다.**
     */
    const val LETTERBOX_ALIGN_ASSUMPTION =
        "🔴 **가정이다(미확정).** metadata.json의 preprocess.resize는 한국어 산문이고 " +
            "align 키가 없다. INTERFACES.md §A-2의 'letterbox pad 위치 = 상하 균등 " +
            "분배(center)'도 ☐ 제안값이다. 이 값이 틀리면 **박스가 통째로 패딩이 붙는 축 " +
            "방향으로 밀린다.** 🔴 **그 축은 회전 뒤에 정해진다** — §A-2의 '상하'는 소스를 " +
            "회전 없이 letterbox 할 때의 축이고, 90/270°로 돌리면 720p가 세로로 길어져 " +
            "패딩이 **좌우**로 가므로 밀리는 방향도 **가로**다. 실제 축은 이 런의 " +
            "letterbox pad_left/pad_top 중 0이 아닌 쪽이 말한다. " +
            "⚠ **이번 라운드는 이 가정을 실제로 썼다**(center). 상류에 확정을 요청해 둔 " +
            "상태이며, 답이 center가 아니면 이 런의 박스 좌표는 그 축으로 밀린 값이다 — " +
            "E·F 비용은 영향이 없고(패딩 면적이 같다) G의 좌표만 틀린다"

    /**
     * 패딩 색. `metadata.json`의 산문과 README 표에는 `(114,114,114)`가 적혀 있고
     * `INTERFACES.md` §A-2도 같은 값을 제안하지만 그 칸도 `☐`다. 산문에서 읽은 값이므로
     * **기계가 확인한 값이 아니다.**
     */
    const val PAD_VALUE_ASSUMPTION =
        "🔴 **산문에서 읽은 값이다(미확정).** metadata.json preprocess.resize의 " +
            "'회색 114 패딩'과 README 표의 (114,114,114)가 출처이며, 기계가 읽을 수 있는 " +
            "키는 없다. INTERFACES.md §A-2의 같은 칸도 ☐다. 학습 시 값과 다르면 조용히 " +
            "정확도만 떨어진다 — ⚠ **이번 라운드는 이 값을 실제로 썼다**(114/255)"

    /** letterbox 정렬. 위 [LETTERBOX_ALIGN_ASSUMPTION]이 이 값의 근거이자 경고다. */
    const val LETTERBOX_ALIGN = "center"

    /** 패딩 값(0..255). 위 [PAD_VALUE_ASSUMPTION]이 이 값의 근거이자 경고다. */
    const val PAD_VALUE_U8 = 114

    /**
     * 🔴 **리샘플링 방식은 어디에도 적혀 있지 않다.**
     *
     * `INTERFACES.md` §A-2의 "리사이즈 방식" 칸은 `letterbox (종횡비 유지)`까지만 적고
     * 보간을 말하지 않으며 그 칸도 `☐`다. 상류 README도 "긴 변을 640에 맞춰 축소"까지다.
     * 학습·평가 파이프라인(ultralytics `LetterBox`)은 `cv2.resize`의 기본
     * `INTER_LINEAR`(이중선형)를 쓰므로 **그쪽에 맞추는 것이 이탈이 가장 작은 선택**이라
     * 판단해 이중선형을 골랐다. 최근접으로 바꾸면 **E가 싸지고 정확도가 조용히 달라진다** —
     * 그 둘을 함께 움직이는 값이라 여기 남긴다.
     */
    const val RESIZE_INTERPOLATION_ASSUMPTION =
        "🔴 **가정이다(미확정).** 보간 방식은 INTERFACES.md §A-2에도 상류 README에도 없다. " +
            "학습·평가 쪽 ultralytics LetterBox가 cv2.resize 기본값 INTER_LINEAR를 쓰므로 " +
            "**이중선형**을 골랐다(휘도 Y는 이중선형, 색차 U/V는 최근접 — 색차는 이미 " +
            "2:1로 서브샘플된 평면이고 저주파라 표준 관행이다). " +
            "⚠ 최근접으로 바꾸면 **E가 싸지고 정확도가 함께 바뀐다** — 둘을 같이 움직이는 " +
            "값이라 성능 보고에 이 문장을 붙일 것"

    /**
     * 🔴 **YUV→RGB 계수도 계약에 없다.**
     *
     * 상류는 이미 RGB인 이미지를 받으므로 이 변환 자체가 상류 파이프라인에 존재하지 않는다.
     * 우리는 카메라에서 `YUV_420_888`을 받으므로 반드시 변환해야 하고, 그 계수 선택이
     * 픽셀값을 바꾼다(따라서 점수도 바꾼다).
     */
    const val YUV_TO_RGB_ASSUMPTION =
        "🔴 **가정이다(계약에 없다).** BT.601 **full range**(JFIF) 계수를 썼다: " +
            "R=Y+1.402(V−128), G=Y−0.344136(U−128)−0.714136(V−128), B=Y+1.772(U−128). " +
            "상류는 이미 RGB인 이미지를 읽으므로 이 변환이 상류에 아예 없다 — 대조할 원본이 " +
            "없다는 뜻이다. limited range(16..235)였다면 대비가 약간 낮은 입력이 되어 " +
            "점수가 소수점에서 달라진다. 골든 샘플(INTERFACES.md §A-6)이 오면 여기서 걸린다"

    // ── 회전을 **어디서** 적용했는가 (규약 §4-1의 어휘) ───────────────────
    // 🔴 값을 지어내지 않는다. 이 두 토큰은
    //    `docs/plans/20260806_detect_parity_dump_format.md` §4-1이 정한 어휘이며,
    //    PC 소비자(`scripts/detect_parity.py`)가 같은 문서를 보고 읽는다.
    //    ⚠ `camerax_output_rotation`은 **이 어휘에 없다** — ImageAnalysis가 회전까지 해서
    //      주면 변환이 E 구간 **밖**에서 일어나 E가 과소로 나온다(RGBA_8888을 안 고른 것과
    //      글자 그대로 같은 함정이다). 그래서 그 길을 아예 만들지 않았다.

    /** letterbox 목적지 좌표 → 회전 역함수 → 센서 좌표를 **한 번에** 매핑한다. */
    const val ROTATION_SITE_SAMPLE_MAP = "preprocess_sample_map"

    /** 회전을 **의도적으로** 적용하지 않았다 — 짝 대조 arm(`detect_cpu_norot`). */
    const val ROTATION_SITE_NONE = "none"

    /**
     * 회전을 **전처리 샘플 맵에 합성해** 적용했다는 사실의 기록.
     *
     * 🔴 **E의 정의와 E의 값을 갈라 쓴다.** 한 문장으로 뭉치면 "E가 회귀했다"로 읽힌다.
     * - E의 **정의**는 `DetectPipeline`이 `t`를 찍는 두 줄이고 **그대로다.**
     * - E의 **값**은 바뀐다. 회귀가 아니라 **조건 변경**이다(그리고 아직 미측정이다).
     */
    const val ROTATION_APPLIED_SAMPLE_MAP =
        "✅ **분석 프레임의 rotationDegrees를 적용했다.** 어디서: " +
            "**$ROTATION_SITE_SAMPLE_MAP** — letterbox 목적지 좌표에서 회전 역함수를 거쳐 " +
            "센서 좌표를 한 번에 잡는다. **추가 패스도 추가 버퍼도 없다**(평면을 회전 복사한 " +
            "뒤 기존 경로를 태우는 preprocess_plane_copy와 다르다). " +
            "🔴 **E의 *정의*는 바뀌지 않았다** — 그것은 DetectPipeline이 t를 찍는 두 줄이고 " +
            "그 자리는 그대로다(detect_timestamp_sites 참고). " +
            "🔴 **E의 *값*은 바뀐다.** 90/270°에서는 목적지 행 하나가 센서의 **열**을 " +
            "훑으므로 Y 평면의 캐시 지역성이 나빠진다. ⚠ **그 비용은 미측정이다** — " +
            "짝 arm(detect_cpu_norot)과의 차분으로만 말한다. " +
            "🔴 **이것은 회귀가 아니라 조건 변경이다.** 08-06 라운드의 E와 나란히 놓고 " +
            "'느려졌다'로 읽지 말 것. " +
            "⚠ **남은 가정 둘**: (1) rotationDegrees를 **시계 방향**으로 읽었다(CameraX " +
            "ImageInfo의 규약이며 기계로 확인한 값이 아니라 문서를 읽은 것이다. 앱과 PC가 " +
            "**같은 가정**을 했을 뿐이며, 틀리면 실기기 E가 크게 어긋난다 — 조용히 틀리지는 " +
            "않는다), " +
            "(2) 모델이 **바로 선** 장면을 기대한다(상류 학습 데이터가 대시캠 정립 영상이라는 " +
            "정황이지 계약이 아니다 — INTERFACES.md에 이 칸이 없다). " +
            "🔴 **반사 규약은 이제 가정이 아니라 규약이다(규약 §5-1).** 그리고 **하나가 " +
            "아니라 둘이며 자리마다 다르다** — 섞으면 정확히 1px 틀린다: " +
            "**전처리 샘플 맵은 픽셀 인덱스/중심 공간이라 `(N−1) − v`**이고(이 문장이 " +
            "말하는 자리다), **후처리 박스는 연속(모서리) 좌표라 `N − v`**다" +
            "(letterbox 역변환이 낸 값이라 `[0, N]`을 가득 채운다). 둘은 같은 기하의 다른 " +
            "매개화이며(`c = i + 0.5`로 서로 유도된다) 그래서 **한 함수를 두 자리에 쓰면 " +
            "안 된다** — 앱은 Rotation.toSensorX/Y(인덱스)와 Rotation.inverseBox(모서리)를 " +
            "따로 둔다. 박스 쪽 값과 축 대응은 detect.parity.boxes_coordinate_space와 " +
            "규약 §5-1의 표가 말한다. " +
            "⚠ **이 1px은 어떤 왕복 검사로도 안 잡힌다**(정·역이 같은 규약이면 왕복은 " +
            "통과한다) — geometry_selfcheck의 **프레임 전체 박스 검산**과 폰↔PC 대조, " +
            "둘뿐이다. " +
            "🔴 그래도 이 런의 boxes_out·max_conf를 **탐지 정확도로 읽으면 안 된다** — " +
            "정답 라벨도 골든 샘플(INTERFACES.md §A-6)도 여전히 없다"

    /**
     * 짝 대조 arm(`detect_cpu_norot`)이 쓰는 문장. **의도된 대조군**이며 "아직 구현하지
     * 않았다"가 아니다 — 규약 §4-2가 그 두 뜻을 가르라고 못 박은 자리다.
     */
    const val ROTATION_NOT_APPLIED_CONTROL =
        "🟢 **회전을 의도적으로 적용하지 않았다 — 대조군 arm이다**(rotation_site=" +
            "$ROTATION_SITE_NONE). 규약 §4-2: `rotation_applied=false`의 뜻은 둘인데 " +
            "이쪽은 **'아직 구현하지 않았다'(이슈 29)가 아니라 '회전 전 E를 같은 세션에서 " +
            "재기 위한 짝 arm'**이다. 짝(detect_cpu)과 **회전 여부 하나만 다르고 나머지는 " +
            "글자 그대로 같다** — 같은 EP·같은 세션 옵션·같은 렌더 경로이며, 전처리도 같은 " +
            "함수에 회전각 0을 넣어 태운다(별도 코드 경로가 아니다). " +
            "🔴 **이 arm의 박스는 '바로 선' 입력에서 나온 것이 아니다** — 모델이 옆으로 " +
            "누운 장면을 보므로 boxes_out·max_conf를 탐지 품질로 읽으면 안 된다. " +
            "⚠ `rotation_degrees`는 기기가 준 값 그대로이고 `rotation_applied=false`와 " +
            "**다른 사실이다**(0°를 받아 항등으로 적용한 것과 구분된다)"

    /**
     * `INTERFACES.md` §A-4가 정한 클래스 인덱스. **모델은 이것과 반대다.**
     *
     * 🔴 **고치지 않는다.** 계약 문서는 팀 합의 기록이고 모델은 가중치의 사실이다. 어느
     * 쪽을 바꿀지는 팀 결정이며, 런타임이 할 일은 **둘이 다르다는 것을 기계로 기록**해
     * 다음 사람이 이 충돌을 모른 채 오버레이 색을 고르지 않게 하는 것뿐이다.
     */
    val INTERFACES_A4_CLASS_ORDER: List<String> = listOf("stairs", "person")

    /**
     * 위 충돌을 사람이 읽는 문장으로. 실제 값은 [DetectRuntime]이 실측한 것을 끼워 넣는다 —
     * 여기에 모델 쪽 순서를 상수로 적으면 그것도 하드코딩이고, 모델이 바뀌는 날 어긋난다.
     *
     * 🔴 **문장 자체를 데이터에서 만든다.** 클래스 이름도 개수도 어휘도 이 함수에 열거하지
     * 않고, [INTERFACES_A4_CLASS_ORDER]와 [modelOrder]를 **인덱스로 대조**해서 "몇 개가 몇
     * 개로 늘었나 / ☐였던 자리가 무엇으로 채워졌나 / 어느 인덱스가 어긋나나"를 만들고,
     * 어휘 판정은 [OverlayClassColors.isKnown] 하나에 묻는다.
     *
     * ⚠ **이 함수가 낡은 자리가 정확히 그것이었다.** 2026-08-24에 모델이 2클래스 → 3클래스가
     * 됐는데 본문에 하드코딩돼 있던 "어휘(stairs/person)"와 "person=시안" 두 절이 그대로
     * 남아, **같은 `session.json` 안에서** `overlay.class_note`
     * (gl/RenderArm.HIGHLIGHT_CLASS_NOTE, "어휘는 셋")를 부정했다. 런은 죽지 않고 로그만
     * 그럴듯하게 거짓말했다 — 이 저장소가 가장 경계하는 부류다.
     */
    fun contractConflictText(modelOrder: List<String>): String {
        val contract = INTERFACES_A4_CLASS_ORDER
        // 계약이 ☐로 비워 둔 자리(계약 목록의 길이를 넘는 인덱스)를 모델이 무엇으로 채웠는가.
        val filled = modelOrder.indices.filter { it >= contract.size }
        // 양쪽에 다 있는 인덱스 중 이름이 어긋난 자리.
        val mismatched = modelOrder.indices.filter { it < contract.size && modelOrder[it] != contract[it] }
        // 반대 방향의 어긋남 — 계약에는 있는데 모델에 없는 인덱스(클래스가 줄면 여기가 찬다).
        val dropped = contract.indices.filter { it >= modelOrder.size }
        val a4First = contract.firstOrNull() ?: "?"
        // 🔴 어휘 목록을 여기 적지 않는다. 판정자는 OverlayClassColors 하나다.
        val inVocab = modelOrder.filter { OverlayClassColors.isKnown(OverlayClassColors.normalize(it)) }
        val outVocab = modelOrder.filter { !OverlayClassColors.isKnown(OverlayClassColors.normalize(it)) }

        val countClause = if (contract.size != modelOrder.size) {
            "🔴 **클래스 수가 계약 ${contract.size}개 → 모델 ${modelOrder.size}개로 바뀌었다.** " +
                "이것이 INTERFACES.md §A-4의 불변 규칙이 **런타임에 통보 필수**라고 못 박은 " +
                "바로 그 사건이다: '클래스가 늘면 출력 shape의 `4+nc`가 바뀐다' · " +
                "'클래스 추가 = **렌더 규약 변경**'. 실제로 이 런의 출력 shape은 " +
                "${declaredOutputShape}이며, 오버레이 어휘도 함께 움직였다(아래 어휘 대조). "
        } else {
            "클래스 **수**는 계약과 같다(${contract.size}개). "
        }

        val filledClause = if (filled.isEmpty()) {
            "계약이 비워 둔 자리를 모델이 채운 것은 없다. "
        } else {
            "🔴 **계약이 ☐로 비워 둔 자리가 채워졌다**: " +
                "${filled.joinToString(", ") { "index $it = ${modelOrder[it]}" }}. " +
                "그 자리에는 계약 문서에 이름이 **아예 없으므로 대조할 선언값이 없다** — " +
                "틀렸다고 말하는 것이 아니라 **확정된 적이 없다**는 뜻이고, 팀이 A-4를 " +
                "확정할 때 함께 적어야 하는 칸이다. "
        }

        val orderClause = if (mismatched.isEmpty() && dropped.isEmpty()) {
            "양쪽에 다 있는 인덱스의 이름은 전부 일치한다. "
        } else {
            "🔴 **양쪽에 다 있는 인덱스의 이름이 어긋난다**: " +
                "${mismatched.joinToString(", ") { "index $it (계약 ${contract[it]} ↔ 모델 ${modelOrder[it]})" }}" +
                (if (dropped.isEmpty()) "" else
                    " · 계약에는 있는데 모델에 없는 인덱스 = " +
                        dropped.joinToString(", ") { "index $it (계약 ${contract[it]})" }) +
                ". 🔴 **그래서 인덱스로 색을 고르면 안 된다** — 인덱스를 계약 이름으로 읽는 " +
                "순간 " +
                mismatched.joinToString(", ") {
                    "**모델의 ${modelOrder[it]}에 ${contract[it]}의 색이** 칠해진다(index $it)"
                } +
                ". 예전에 HighlightOverlay에 있던 'index 0 = ${a4First}'라는 A-4 기반 " +
                "가정은 **지웠다** — 그 줄이 남아 있었다면 ③→④를 연결하는 순간 위 뒤바뀜이 " +
                "그대로 화면에 나갔다. 이것은 취향이 아니라 **안전 문제**다. "
        }

        val vocabClause = "**이 런의 이름을 어휘와 대조한 결과**(판정자는 " +
            "OverlayClassColors.isKnown 하나이고 🔴 **어휘 목록을 이 문장에 열거하지 " +
            "않는다** — 열거했다가 클래스가 늘던 날 이 문장이 거짓이 됐다): 어휘 **안** = " +
            (if (inVocab.isEmpty()) "없다" else inVocab.toString()) +
            ", 어휘 **밖** = " +
            (if (outVocab.isEmpty()) {
                "**없다 — 이 런은 모델 이름 전부가 어휘 안이고, 따라서 중립색으로 떨어지는 " +
                    "이름이 하나도 없어야 한다**"
            } else {
                outVocab.toString() + " (중립색으로 그려진다)"
            }) +
            ". 어휘 밖 이름이나 범위 밖 cls는 지우지 않고 **중립색으로** 그리고 그 사실을 " +
            "session.json의 overlay.class_color_mapping에 남긴다 — 정책 원문은 그 블록의 " +
            "unknown_policy이며 색 값은 그 블록의 table이 말한다. "

        return "🔴 **INTERFACES.md 계약 A-4와 모델의 클래스 목록이 어긋난다.** " +
            "계약 A-4 = ${contract.mapIndexed { i, n -> "$i=$n" }} , " +
            "모델 임베드 메타(names) = ${modelOrder.mapIndexed { i, n -> "$i=$n" }}. " +
            "🔴 **어긋난 방식이 하나가 아니라 셋이므로 갈라 적는다** — '순서가 반대다' " +
            "하나로 뭉치면 개수 변화가 문장에서 사라지고, 실제로 그렇게 사라진 적이 있다: " +
            "**(a) 개수** — $countClause" +
            "**(b) ☐ 자리** — $filledClause" +
            "**(c) 순서** — $orderClause" +
            "**고치지 않았다** — 계약 문서는 팀 합의 기록이라 런타임이 임의로 못 바꾸고, " +
            "모델은 가중치의 사실이라 코드로 뒤집으면 그게 곧 무음 버그다. " +
            "🔴 **③→④는 2026-08-07에 연결됐고, 그래서 이 충돌이 무해해졌다** — ④ 오버레이의 " +
            "색은 **클래스 이름**으로 고른다(gl/OverlayClassColors). 이름의 출처는 이 블록의 " +
            "classes(모델 임베드 메타의 names) **하나뿐**이고 계약 A-4의 순서를 쓰지 않는다. " +
            "🔴 그러므로 팀이 A-4를 어느 쪽으로 확정해도 **오버레이 코드는 바뀌지 않는다** — " +
            "그것이 이름으로 거는 설계의 목적이다. " +
            vocabClause +
            "⚠ **색 값과 상류 명세 이탈은 이 문장이 말하지 않는다**(여기에 색을 베껴 쓰면 " +
            "색이 바뀌는 날 또 거짓이 된다) — overlay.class_color_mapping의 " +
            "person_color_deviation(OverlayClassColors.PERSON_COLOR_DEVIATION)과 " +
            "bollard_color_provenance가 그 기록이다"
    }

    /**
     * letterbox 패딩이 입력 텐서에서 차지하는 **픽셀 비율**.
     *
     * `1 − (내용 픽셀 수 / 입력 픽셀 수)`이며, 720p(16:9) → 640 정사각이면
     * 긴 변이 꽉 차 `1 − (640·360)/(640·640) = 0.4375`가 된다
     * (`docs/FRAME_LOG_SCHEMA.md`가 든 예 `1 − 360/640`과 같은 값이다).
     *
     * 🔴 **상수를 복사하지 않고 계산한다.** 소스 해상도나 입력 크기가 바뀌면 이 값도 바뀌고,
     * 그때 상수는 조용히 틀린다. **F의 일부는 회색 패딩을 미는 비용**이라 이 값 없이 다른
     * 입력 크기의 F와 비교하면 안 된다.
     *
     * ⚠ 패딩을 **어느 쪽에** 붙이는지는 이 값에 영향이 없다(면적은 같다) —
     * 그 미확정은 [LETTERBOX_ALIGN_ASSUMPTION]이 따로 들고 있다.
     */
    /**
     * letterbox 기하. **전처리와 후처리가 같은 값을 써야** 역변환이 성립하므로 한 곳에서 낸다
     * (두 곳에서 각자 계산하면 반올림이 어긋나는 날 박스가 조용히 몇 px씩 밀린다).
     *
     * [padX]/[padY]는 **왼쪽/위쪽** 패딩이다. 총 패딩이 홀수면 남는 1px은 오른쪽/아래로 간다 —
     * `align=center`의 통상 구현(ultralytics `LetterBox`도 같다)이다.
     */
    class Letterbox(
        /** 🔴 **소스는 ② 회전 후 프레임이다**(센서가 아니다 — 규약 §5의 사슬 두 번째 칸). */
        val srcW: Int,
        val srcH: Int,
        val dstW: Int,
        val dstH: Int,
        val scale: Float,
        val contentW: Int,
        val contentH: Int,
        val padX: Int,
        val padY: Int,
    ) {
        /** [scale]의 역수. **한 곳에서만 만든다** — 두 곳에서 나누면 반올림이 갈린다. */
        val invScale: Float = 1f / scale

        /** letterbox 640 좌표 → ② 회전 후 좌표 (x 성분). */
        fun toSrcX(x: Float): Float = (x - padX) * invScale

        /** letterbox 640 좌표 → ② 회전 후 좌표 (y 성분). */
        fun toSrcY(y: Float): Float = (y - padY) * invScale

        /** ② 회전 후 좌표 → letterbox 640 좌표 (x 성분). */
        fun toDstX(x: Float): Float = x * scale + padX

        /** ② 회전 후 좌표 → letterbox 640 좌표 (y 성분). */
        fun toDstY(y: Float): Float = y * scale + padY
    }

    /**
     * @param srcW / [srcH] 🔴 **회전 후 치수**를 넣는다. 센서 치수를 넣으면 90/270°에서
     *   종횡비가 뒤집혀 `pad_x`와 `pad_y`가 자리를 바꾼다(규약 §3-3이 `/1`→`/2` 버전을
     *   올린 이유가 바로 이것이다).
     */
    fun letterbox(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Letterbox {
        val scale = minOf(dstW.toFloat() / srcW, dstH.toFloat() / srcH)
        val contentW = Math.round(srcW * scale).coerceIn(1, dstW)
        val contentH = Math.round(srcH * scale).coerceIn(1, dstH)
        return Letterbox(
            srcW = srcW,
            srcH = srcH,
            dstW = dstW,
            dstH = dstH,
            scale = scale,
            contentW = contentW,
            contentH = contentH,
            // align=center. 🔴 이 한 줄이 LETTERBOX_ALIGN_ASSUMPTION의 실체다.
            padX = (dstW - contentW) / 2,
            padY = (dstH - contentH) / 2,
        )
    }

    /**
     * 회전 기하. **[Letterbox]와 같은 이유로 한 곳에서만 낸다** — 전처리(목적지→센서)와
     * 후처리(회전 후→센서)가 각자 공식을 적으면 부호 하나가 어긋나는 날 박스가 통째로
     * 뒤집히고, 그때 어느 쪽이 틀렸는지 알 수 없다.
     *
     * ## 좌표 공간 둘 (규약 §5의 사슬 ①과 ②)
     *
     * | 이름 | 무엇 | 치수 |
     * |---|---|---|
     * | **센서** | `ImageProxy`가 준 그대로(회전 전) | [srcW] × [srcH] |
     * | **회전 후** | 시계 방향으로 [degrees]만큼 돌린 것. 모델이 보는 방향 | [rotatedW] × [rotatedH] |
     *
     * ## 방향 규약
     *
     * [degrees]는 `ImageProxy.imageInfo.rotationDegrees`이며 **"바로 세우려면 시계 방향으로
     * 몇 도 돌려야 하는가"**다. 값은 `{0, 90, 180, 270}`뿐이고 **그 밖은 [rotationOf]가
     * 거부한다** — 그럴듯한 근사(가장 가까운 90° 배수 등)를 만들지 않는다. 근사를 만들면
     * 그 런의 박스가 조용히 몇 도 틀어진 채로 나오고, 그건 아무도 못 잡는다.
     *
     * ## 🔴 반사 규약이 **둘**이다 — 섞으면 정확히 1px 틀린다
     *
     * | 쓰는 곳 | 좌표의 성질 | 범위 | 반사식 | 함수 |
     * |---|---|---|---|---|
     * | 전처리 샘플 맵 | **픽셀 인덱스**(정수 = 픽셀 **중심**) | `[0, N−1]` | `(N−1) − v` | [toSensorX]/[toSensorY] |
     * | 후처리 박스 | **연속 좌표**(모서리) | `[0, N]` | `N − v` | [inverseBox]/[forwardBox] |
     *
     * 박스 좌표는 letterbox 역변환 `(x − pad)/scale`이 낸 **연속 좌표**이고 회전 후 프레임
     * `[0, rotatedW]`를 **가득 채운다**(정수 인덱스 `[0, rotatedW−1]`이 아니다). 여기에
     * 인덱스 쪽 식 `(N−1) − v`를 쓰면 **프레임 전체가 원점 쪽으로 정확히 1px 밀린다** —
     * 회전 후 오른쪽 끝 `rotatedW`가 센서 `−1`로 간다. 샘플 맵은 반대로 정수 인덱스를
     * 다루므로 거기서는 `−1`이 맞다.
     *
     * 🔴 **그래서 [inverseBox]는 [toSensorX]/[toSensorY]를 부르지 않는다.** 한 함수를 두
     * 규약에 쓰면 한쪽이 조용히 1px 틀리고, 그 1px은 어떤 왕복 검사로도 안 잡힌다(양쪽이
     * 같은 규약이면 왕복은 통과한다). [DetectGeometryCheck]가 **프레임 전체 박스**를 따로
     * 대조해 그 자리를 감시한다.
     *
     * ⚠ 90° 배수라 축이 축으로 가므로 **보간을 새로 끌어들이지 않는다**(순수 좌표 치환이다).
     */
    class Rotation internal constructor(
        val degrees: Int,
        val srcW: Int,
        val srcH: Int,
    ) {
        /** 90/270이면 폭과 높이가 자리를 바꾼다. */
        val swapsAxes: Boolean = degrees == 90 || degrees == 270

        /** ② 회전 후 프레임의 폭. `parity.json`의 `source.rotated_width`가 이 값이다. */
        val rotatedW: Int = if (swapsAxes) srcH else srcW

        /** ② 회전 후 프레임의 높이. */
        val rotatedH: Int = if (swapsAxes) srcW else srcH

        /**
         * **정방향** — ① 센서 좌표 → ② 회전 후 좌표 (x 성분). 🔴 **픽셀 인덱스 규약**.
         *
         * ⚠ **프레임 경로는 정방향 점 함수를 부르지 않는다**(전처리는 역방향만 쓴다).
         * 있는 이유는 [DetectGeometryCheck]가 **샘플 맵의 가역성**을 확인하기 위해서다 —
         * 박스 왕복 검사는 모서리 규약이라 이 두 함수를 건드리지 않는다.
         */
        fun toRotatedX(x: Float, y: Float): Float = when (degrees) {
            90 -> (srcH - 1) - y
            180 -> (srcW - 1) - x
            270 -> y
            else -> x
        }

        /** **정방향** — ① 센서 좌표 → ② 회전 후 좌표 (y 성분). 🔴 **픽셀 인덱스 규약**. */
        fun toRotatedY(x: Float, y: Float): Float = when (degrees) {
            90 -> x
            180 -> (srcH - 1) - y
            270 -> (srcW - 1) - x
            else -> y
        }

        /**
         * **역방향** — ② 회전 후 좌표 → ① 센서 좌표 (x 성분).
         * 🔴 전처리의 샘플 맵이 픽셀마다 부르는 함수다(목적지 → letterbox 역 → **여기**).
         * 🔴 **픽셀 인덱스 규약**(`(N−1) − v`)이다 — **박스에 쓰지 말 것**([inverseBox] 참고).
         */
        fun toSensorX(rx: Float, ry: Float): Float = when (degrees) {
            90 -> ry
            180 -> (srcW - 1) - rx
            270 -> (srcW - 1) - ry
            else -> rx
        }

        /** **역방향** — ② 회전 후 좌표 → ① 센서 좌표 (y 성분). 🔴 **픽셀 인덱스 규약**. */
        fun toSensorY(rx: Float, ry: Float): Float = when (degrees) {
            90 -> (srcH - 1) - rx
            180 -> (srcH - 1) - ry
            270 -> rx
            else -> ry
        }

        /**
         * **역방향 박스** — ② 회전 후 좌표계의 축 정렬 박스 → ① 센서 좌표계의 축 정렬 박스.
         * 결과는 [out]에 `x1, y1, x2, y2` 순으로 담는다(**할당 없음** — G 경로에서 불린다).
         *
         * 🔴 **연속(모서리) 좌표 규약이다** — 반사식이 `N − v`이고 `(N−1) − v`가 아니다.
         * 위 클래스 KDoc의 표 참고. 그래서 [toSensorX]/[toSensorY]를 **부르지 않고** 여기서
         * 축 대응을 직접 적는다. 한 함수를 두 규약에 쓰면 한쪽이 조용히 1px 틀린다.
         * 검산: 회전 후 프레임 전체 박스 `(0, 0, rotatedW, rotatedH)`는 센서 프레임 전체
         * `(0, 0, srcW, srcH)`로 가야 한다 — 인덱스 식을 쓰면 `y1`이 `−1`로 나온다.
         *
         * 🔴 **min/max가 아니다.** 회전이 뒤집는 축에서는 두 끝점의 **순서까지 함께** 옮긴다
         * (`x1' = N − x2`). 그래서 잘 생긴 박스는 잘 생긴 채로 돌아오고(90°마다 전부
         * 역전되는 일이 없다), **뒤집힌 박스는 뒤집힌 채로 돌아온다.** min/max로 뭉개면
         * 모델이 낸 역전(`w < 0`)까지 조용히 고쳐져 [DetectPostprocessor]의 역전 카운터가
         * 아무것도 못 잡는다 — 그 카운터가 지금 `x1 < x2` 불변식의 **유일한 감시자**다
         * (알려진 이슈 34의 남은 위험).
         *
         * 🔴 **축 대응은 규약 §5-1의 표 그대로다**(`W = rotated_w`, `H = rotated_h`):
         * `90°: (y1, W−x2, y2, W−x1)` · `180°: (W−x2, H−y2, W−x1, H−y1)` ·
         * `270°: (H−y2, x1, H−y1, x2)`. PC 재구현(`scripts/detect_parity.py`의
         * `RotationMap.invert_box`)도 같은 표를 쓴다 — **어긋나면 문서가 맞다.**
         *
         * ⚠ v1.2 초판에는 이 절이 **없었고** 양 트랙이 각자 구현하며 코드 주석으로 올렸다.
         * 지금은 문서에 있다(2026-08-07). 앱이 먼저 정하는 자리가 아니다.
         */
        fun inverseBox(x1: Float, y1: Float, x2: Float, y2: Float, out: FloatArray) {
            // 반사축의 길이는 **회전 후 프레임**의 것이다(rotatedW/H). 90/270이면 그것이
            // 곧 센서의 높이/폭이고, 그 대응이 아래 축 배치의 근거다.
            val w = rotatedW.toFloat()
            val h = rotatedH.toFloat()
            when (degrees) {
                // sx ← ry (순서 보존) · sy ← rotatedW − rx (순서 반전)
                90 -> {
                    out[0] = y1
                    out[1] = w - x2
                    out[2] = y2
                    out[3] = w - x1
                }
                180 -> {
                    out[0] = w - x2
                    out[1] = h - y2
                    out[2] = w - x1
                    out[3] = h - y1
                }
                // sx ← rotatedH − ry (반전) · sy ← rx (보존)
                270 -> {
                    out[0] = h - y2
                    out[1] = x1
                    out[2] = h - y1
                    out[3] = x2
                }
                else -> {
                    out[0] = x1
                    out[1] = y1
                    out[2] = x2
                    out[3] = y2
                }
            }
        }

        /**
         * **정방향 박스** — ① 센서 → ② 회전 후. [inverseBox]와 **같은 모서리 규약**이다.
         *
         * ⚠ **프레임 경로는 이 함수를 부르지 않는다** — 전처리·후처리 둘 다 역방향만 쓴다.
         * 있는 이유는 [DetectGeometryCheck]의 왕복 검사이며, 그 검사가 잡는 것은
         * "정방향과 역방향이 서로의 역함수인가"다.
         */
        fun forwardBox(x1: Float, y1: Float, x2: Float, y2: Float, out: FloatArray) {
            // 이쪽 반사축의 길이는 **센서 프레임**의 것이다([inverseBox]와 뒤집혀 있다).
            val w = srcW.toFloat()
            val h = srcH.toFloat()
            when (degrees) {
                // rx ← srcH − y (반전) · ry ← x (보존)
                90 -> {
                    out[0] = h - y2
                    out[1] = x1
                    out[2] = h - y1
                    out[3] = x2
                }
                180 -> {
                    out[0] = w - x2
                    out[1] = h - y2
                    out[2] = w - x1
                    out[3] = h - y1
                }
                // rx ← y (보존) · ry ← srcW − x (반전)
                270 -> {
                    out[0] = y1
                    out[1] = w - x2
                    out[2] = y2
                    out[3] = w - x1
                }
                else -> {
                    out[0] = x1
                    out[1] = y1
                    out[2] = x2
                    out[3] = y2
                }
            }
        }
    }

    /** [Rotation]이 받아들이는 각. 🔴 **이 밖은 거부한다**(근사를 만들지 않는다). */
    val SUPPORTED_ROTATIONS: List<Int> = listOf(0, 90, 180, 270)

    /**
     * 회전 기하를 만든다. 각이 [SUPPORTED_ROTATIONS] 밖이거나 치수가 양수가 아니면 **null**.
     * 🔴 null을 받은 쪽은 **그 프레임을 처리하지 않는다** — 그럴듯한 근사를 만들지 않는다.
     */
    fun rotationOf(degrees: Int, srcW: Int, srcH: Int): Rotation? {
        if (degrees !in SUPPORTED_ROTATIONS) return null
        if (srcW <= 0 || srcH <= 0) return null
        return Rotation(degrees, srcW, srcH)
    }

    /** [rotationOf]가 null을 낸 사유(사람이 읽는 문장). `session.json`으로 그대로 나간다. */
    fun rotationFailure(degrees: Int, srcW: Int, srcH: Int): String =
        "🔴 회전 기하를 만들지 못했다 — rotationDegrees=$degrees, 소스 ${srcW}x$srcH. " +
            "받아들이는 각은 ${SUPPORTED_ROTATIONS.joinToString(",")}뿐이고 **그 밖의 값에 " +
            "가장 가까운 90° 배수를 골라 주지 않는다** — 근사를 만들면 그 런의 박스가 " +
            "조용히 몇 도 틀어진 채로 나오고 아무도 못 잡는다. 이 프레임은 처리하지 않고 " +
            "errors로 계상했다"

    fun paddingPixelFraction(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Double? {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return null
        val scale = minOf(dstW.toDouble() / srcW, dstH.toDouble() / srcH)
        val contentW = Math.round(srcW * scale).toInt().coerceIn(1, dstW)
        val contentH = Math.round(srcH * scale).toInt().coerceIn(1, dstH)
        return 1.0 - (contentW.toDouble() * contentH) / (dstW.toDouble() * dstH)
    }
}
