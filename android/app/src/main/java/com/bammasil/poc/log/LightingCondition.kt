package com.bammasil.poc.log

/**
 * `session.json`의 `lighting_condition` 어휘.
 *
 * 야간 앱에서 조명은 취향이 아니라 **공급 fps를 직접 바꾸는 측정 조건**이다(저조도에서 AE가
 * 노출을 늘리면 프레임 간격 자체가 벌어진다). 그래서 `baseline_diff.py`의 비교 조건에 든다.
 *
 * ⚠ 이 목록의 **어휘(집합)** 는 `lib/frame_log.py`의 `LIGHTING_CONDITIONS`와 **정확히** 같아야
 * 한다. 어휘 밖 값을 쓰면 하네스가 경고하고 그 런은 비교 근거가 못 된다.
 * 🟢 **순서는 같지 않아도 된다** — 저쪽은 어휘 정의이고 이쪽은 UI 목록이라 첫 항목이 기본값이
 * 되기 때문이다. 순서를 맞추려고 아래를 되돌리지 말 것.
 * `synthetic`은 합성 로그 전용이므로 앱이 고를 수 있게 두지 않는다.
 */
object LightingCondition {

    const val UNKNOWN = "unknown"

    /**
     * UI 목록. **첫 항목이 기본값이다.**
     *
     * 🔎 **기본값을 `unknown`에서 [DEFAULT]로 바꿨다(08-29).** 사용자 테스트에서 스피너를
     * 만지지 않고 시작하는 경우가 흔한데, 이 앱의 실제 사용 조건이 야간 어두운 구간이라
     * 매번 고르게 하는 것이 실수를 부른다.
     *
     * 🔴 **대가를 알고 바꿨다.** `unknown`은 하네스가 *"이 런은 비교 대상이 못 된다"* 고
     * **소리 내어 거부**하는 값이었다. 기본값이 실제 어휘가 되면, 조명을 안 고른 런이
     * **정상적인 야간 런으로 조용히 통과**한다 — 시끄러운 실패가 조용한 오답이 된다.
     * 그래서 `MainActivity`가 **측정 시작 시 선택된 조명을 화면에 띄운다**(그 확인이 이
     * 기본값의 짝이다. 없애면 신호가 사라진다).
     *
     * 🔴 **실내에서 잴 때는 반드시 바꿔야 한다.** 앱은 조명을 알 수 없고 이 값은 **사람의
     * 신고**다. 틀리면 `baseline_diff`가 실내 런과 야간 런을 "조건 동일"로 비교한다.
     * ⚠ 야간도 둘로 갈린다 — 가로등이 있으면 `outdoor_night_lit`이다.
     */
    val CHOICES: List<String> = listOf(
        DEFAULT,               // 이 앱의 실제 사용 조건 — 기본값
        "outdoor_night_lit",   // 가로등 있는 보도. 야간이라고 다 dark가 아니다
        "indoor_dim",
        "indoor_bright",       // 하네스 배선 점검용. 야간 근거로는 못 쓴다
        UNKNOWN,               // 고를 수는 있다. 고르면 비교 대상이 못 된다
    )

    /** 스피너 기본값 = [CHOICES]의 첫 항목. **두 곳에 적지 않는다.** */
    const val DEFAULT = "outdoor_night_dark"
}
