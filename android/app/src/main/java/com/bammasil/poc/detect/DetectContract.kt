package com.bammasil.poc.detect

import com.bammasil.poc.BuildConfig

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

    /** `"models/det_c4b_loli0_640/metadata.json"` 또는 빌드 시점에 못 읽었으면 `"unavailable"`. */
    val declaredSource: String get() = BuildConfig.DETECT_DECLARED_SOURCE

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

    /** 🔴 **판별하지 못했다는 뜻이며 "CPU였다"가 아니다.** 요청값을 베껴 넣는 자리가 아니다. */
    const val EP_UNKNOWN = "unknown"

    // ── EP 판별 수단 이름(`resolution_method`) ─────────────────────────────

    /**
     * 1순위. **프로브 세션**의 ORT 프로파일 JSON에서 노드별 provider를 세어 판별했다.
     *
     * 왜 측정 세션이 아니라 프로브 세션인가: 프로파일러는 노드마다 기록을 남겨 추론 시간에
     * 자기 비용을 얹는다. 그래서 `detect_cpu`/`detect_nnapi`(시간을 인용하는 arm)의 측정
     * 세션에는 프로파일러를 켤 수 없는데, 그러면 그 arm이 영원히 `unknown`이 된다.
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
     * ⚠ 이게 틀리면 박스가 통째로 세로로 밀린다(§A-2가 직접 그렇게 경고한다).
     * 실제 전처리는 다음 라운드이므로 **이번 라운드에 이 가정을 쓴 코드는 없다** —
     * 지금 기록해 두는 이유는 다음 라운드가 이 문장을 읽고 시작하게 하기 위해서다.
     */
    const val LETTERBOX_ALIGN_ASSUMPTION =
        "🔴 **가정이다(미확정).** metadata.json의 preprocess.resize는 한국어 산문이고 " +
            "align 키가 없다. INTERFACES.md §A-2의 'letterbox pad 위치 = 상하 균등 " +
            "분배(center)'도 ☐ 제안값이다. 이 값이 틀리면 **박스가 통째로 세로로 밀린다.** " +
            "이번 라운드는 전처리를 붙이지 않았으므로 이 가정을 **쓴 코드가 없다** — " +
            "다음 라운드가 letterbox를 구현할 때 상류에 확정을 받고 시작할 것"

    /**
     * 패딩 색. `metadata.json`의 산문과 README 표에는 `(114,114,114)`가 적혀 있고
     * `INTERFACES.md` §A-2도 같은 값을 제안하지만 그 칸도 `☐`다. 산문에서 읽은 값이므로
     * **기계가 확인한 값이 아니다.**
     */
    const val PAD_VALUE_ASSUMPTION =
        "🔴 **산문에서 읽은 값이다(미확정).** metadata.json preprocess.resize의 " +
            "'회색 114 패딩'과 README 표의 (114,114,114)가 출처이며, 기계가 읽을 수 있는 " +
            "키는 없다. INTERFACES.md §A-2의 같은 칸도 ☐다. 학습 시 값과 다르면 조용히 " +
            "정확도만 떨어진다 — 이번 라운드는 전처리가 없어 쓰지 않았다"

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
     */
    fun contractConflictText(modelOrder: List<String>): String =
        "🔴 **INTERFACES.md 계약 A-4와 모델의 클래스 순서가 반대다.** " +
            "계약 A-4 = ${INTERFACES_A4_CLASS_ORDER.mapIndexed { i, n -> "$i=$n" }} , " +
            "모델 임베드 메타(names) = ${modelOrder.mapIndexed { i, n -> "$i=$n" }}. " +
            "**고치지 않았다** — 계약 문서는 팀 합의 기록이라 런타임이 임의로 못 바꾸고, " +
            "모델은 가중치의 사실이라 코드로 뒤집으면 그게 곧 무음 버그다. " +
            "⚠ **④ 오버레이(HighlightOverlay)에 'index 0 = stairs'라는 A-4 기반 가정이 " +
            "이미 들어 있다.** 지금은 정적 더미 박스라 무해하지만, ③→④를 연결하는 순간 " +
            "**사람과 계단의 색이 뒤바뀐다**(person=시안이어야 할 것이 노랑으로 나간다). " +
            "그래서 이번 라운드는 ③→④를 **연결하지 않았다.** 팀이 어느 쪽으로 확정하든 " +
            "런타임은 이 블록의 classes(모델에서 읽은 것)를 1순위로 쓴다"

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
    fun paddingPixelFraction(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Double? {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return null
        val scale = minOf(dstW.toDouble() / srcW, dstH.toDouble() / srcH)
        val contentW = Math.round(srcW * scale).toInt().coerceIn(1, dstW)
        val contentH = Math.round(srcH * scale).toInt().coerceIn(1, dstH)
        return 1.0 - (contentW.toDouble() * contentH) / (dstW.toDouble() * dstH)
    }
}
