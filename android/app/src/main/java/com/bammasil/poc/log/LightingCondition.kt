package com.bammasil.poc.log

/**
 * `session.json`의 `lighting_condition` 어휘.
 *
 * 야간 앱에서 조명은 취향이 아니라 **공급 fps를 직접 바꾸는 측정 조건**이다(저조도에서 AE가
 * 노출을 늘리면 프레임 간격 자체가 벌어진다). 그래서 `baseline_diff.py`의 비교 조건에 든다.
 *
 * ⚠ 이 목록은 `lib/frame_log.py`의 `LIGHTING_CONDITIONS`와 **정확히** 같아야 한다.
 * 어휘 밖 값을 쓰면 하네스가 경고하고 그 런은 비교 근거가 못 된다.
 * `synthetic`은 합성 로그 전용이므로 앱이 고를 수 있게 두지 않는다.
 */
object LightingCondition {

    const val UNKNOWN = "unknown"

    /** UI 목록. 첫 항목이 기본값이며, 고르지 않으면 `unknown`으로 남는다. */
    val CHOICES: List<String> = listOf(
        UNKNOWN,
        "indoor_bright",
        "indoor_dim",
        "outdoor_night_lit",
        "outdoor_night_dark",
    )
}
