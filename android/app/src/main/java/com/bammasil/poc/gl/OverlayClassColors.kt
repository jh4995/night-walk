package com.bammasil.poc.gl

import java.util.Locale

/**
 * ④ 오버레이의 **클래스 이름 → 색** 표. 🔴 **인덱스로 고르지 않는다.**
 *
 * ## 왜 이름인가 (이 파일이 있는 이유)
 *
 * `INTERFACES.md` 계약 A-4는 `0=stairs, 1=person`인데 **모델 임베드 메타(`names`)는
 * `{0:'person', 1:'stairs'}`로 정반대**다([DetectContract.contractConflictText]가 그 충돌을
 * 기계로 남긴다). 인덱스로 색을 고르면 **계약과 모델 중 어느 쪽이 확정되든 한쪽에서 틀리고**,
 * 틀린 결과는 "사람에게 계단 색을 칠하는" 무음 버그다.
 *
 * 이름으로 걸면 팀이 어느 쪽으로 확정하든 **이 코드는 바뀌지 않는다** — 그것이 이 설계의
 * 목적이다. 이름의 출처는 [com.bammasil.poc.detect.DetectRuntime]의 `classNames`
 * (모델 임베드 메타의 `names`) **하나뿐**이며, 계약 문서의 순서를 쓰지 않는다.
 *
 * ## 어휘 밖 이름은 지우지 않고 **중립색으로 그린다**
 *
 * 🔴 어휘에 없는 이름(또는 `cls`가 모델 이름 목록의 범위 밖)이면 계단색도 사람색도 주지
 * 않고 [colorFor]가 [UNKNOWN_NAME_COLOR_TEXT]의 중립색을 준다. 근거는 **"탐지된 위험물을
 * 화면에서 지우는 것이 더 나쁘다"**이며, 관측된 이름과 개수는
 * `session.json`의 `overlay.class_color_mapping`에 남는다.
 *
 * ⚠ **빨강은 중립색 후보에서 제외된다** — 빨강은 휘도가 낮아 야간 배경에 묻히고 적록색약에서
 * 무너진다(`RESEARCH_20260803_UPSTREAM.md` §5, [RenderArm.HIGHLIGHT_NO_RED_REASON]).
 *
 * **스레드 규약:** 순수 함수다. 탐지 워커(게시 시점)와 GL 스레드(정적 arm의 지오메트리)가
 * 함께 부른다. 🔴 [colorFor]가 돌려주는 배열은 **공유 상수이므로 쓰지 말 것**(복사해 쓴다).
 */
object OverlayClassColors {

    /** 상류 `scripts/emphasize.py`가 확정한 어휘 2종. 이름 문자열 자체가 계약면이다. */
    const val CLASS_STAIRS = "stairs"
    const val CLASS_PERSON = "person"

    /**
     * 이름 정규화 규칙. **이 문장이 곧 규칙이고, 같은 문장이 `session.json`으로 나간다** —
     * 규칙을 코드에만 두면 나중에 "왜 이 이름이 unknown으로 셌나"를 되물을 수 없다.
     */
    const val NORMALIZATION =
        "`trim()`으로 앞뒤 공백을 지운 뒤 `lowercase(Locale.ROOT)`로 소문자화한다. " +
            "**그 이상은 하지 않는다** — 가운데 공백·언더스코어·복수형을 접지 않고(예: " +
            "`stair`, `stairs_up`, `person 1`은 전부 어휘 밖이다), 로케일 의존 소문자화를 " +
            "피하려고 Locale.ROOT를 쓴다(터키어 로케일에서 'I'가 'ı'로 내려가는 함정). " +
            "🔴 **어휘 밖 이름을 이름으로 추측해 접지 않는다** — 접으면 모델이 클래스를 " +
            "늘린 날 새 클래스가 조용히 기존 색을 물려받는다"

    const val STAIRS_COLOR_TEXT = "노랑 (1, 1, 0)"
    const val PERSON_COLOR_TEXT = "시안 (0, 1, 1)"
    const val UNDERLINE_COLOR_TEXT = "검정 (0, 0, 0)"
    const val UNKNOWN_NAME_COLOR_TEXT = "중립색 = 흰색 (1, 1, 1)"

    /** 🔴 **어휘 밖 이름을 만났을 때의 정책.** 같은 문장이 `session.json`으로 나간다. */
    const val UNKNOWN_POLICY =
        "🔴 **지우지 않고 중립색으로 그린다.** 어휘(stairs/person) 밖 이름이거나 `cls`가 " +
            "모델 이름 목록의 범위 밖이면 계단색도 사람색도 주지 않고 " +
            // ⚠ 중괄호를 빼면 안 된다 — 한글은 식별자 문자라 `$X으로`가 이름 `X으로`로 파싱돼
            //   "Unresolved reference"가 난다(이 줄에서 실제로 컴파일이 깨졌다).
            "${UNKNOWN_NAME_COLOR_TEXT}으로 그린다. 근거: **탐지된 위험물을 화면에서 지우는 " +
            "것이 더 나쁘다.** 관측된 이름과 개수는 이 블록의 unknown_names_seen / " +
            "counts_by_class에 그대로 남고 logcat에도 나간다. " +
            "⚠ **빨강은 후보에서 제외된다**(no_red_reason) — 야간 휘도·적록색약 때문이다. " +
            "⚠ 중립색이 화면에 보이면 그것은 **정상 동작이 아니라 신호다**: 모델의 클래스가 " +
            "늘었거나 이름이 바뀐 것이므로 오버레이 어휘를 팀과 함께 갱신해야 한다"

    /**
     * 노랑 (1, 1, 0). 🔴 **쓰지 말 것** — 공유 상수다.
     * 값의 출처는 상류 `scripts/emphasize.py`(확정 사양)다.
     */
    private val STAIRS_COLOR = floatArrayOf(1f, 1f, 0f)

    /** 시안 (0, 1, 1). 🔴 **쓰지 말 것** — 공유 상수다. */
    private val PERSON_COLOR = floatArrayOf(0f, 1f, 1f)

    /**
     * 어휘 밖 이름의 **중립색** = 흰색 (1, 1, 1).
     *
     * ⚠ **상류 사양이 아니라 우리가 선언한 값이다.** 상류는 stairs/person 둘만 정했고 어휘
     * 밖 이름을 말하지 않는다. 흰색을 고른 이유: (a) 빨강이 아니고(no_red_reason),
     * (b) 노랑·시안 어느 쪽과도 혼동되지 않으며, (c) 휘도가 가장 높아 야간 배경에서 사라지지
     * 않는다. 🔴 **쓰지 말 것** — 공유 상수다.
     */
    private val UNKNOWN_COLOR = floatArrayOf(1f, 1f, 1f)

    /** 검정 밑선. 이중 스트로크의 바깥 띠다. 🔴 **쓰지 말 것** — 공유 상수다. */
    val UNDERLINE_COLOR = floatArrayOf(0f, 0f, 0f)

    /**
     * `cls`가 모델의 이름 목록 범위 밖일 때 통계에 쓰는 키. 🔴 **이름을 지어내지 않는다** —
     * 그 자리에 `"person"`을 넣으면 범위 밖 사건이 통계에서 사라진다.
     */
    fun outOfRangeKey(cls: Int): String = "<cls $cls: 모델 이름 목록 범위 밖>"

    /** [NORMALIZATION]의 실체. 규칙을 바꾸면 그 문자열도 함께 바꾼다. */
    fun normalize(raw: String?): String =
        raw?.trim()?.lowercase(Locale.ROOT) ?: ""

    /** 정규화된 이름이 오버레이 어휘 안에 있는가. */
    fun isKnown(normalized: String): Boolean =
        normalized == CLASS_STAIRS || normalized == CLASS_PERSON

    /**
     * 정규화된 이름 → 색. 🔴 **돌려주는 배열은 공유 상수다 — 값을 복사해 쓰고 쓰지 말 것.**
     * 어휘 밖이면 [UNKNOWN_COLOR](중립색)다 — null도 아니고 계단·사람 색도 아니다.
     */
    fun colorFor(normalized: String): FloatArray = when (normalized) {
        CLASS_STAIRS -> STAIRS_COLOR
        CLASS_PERSON -> PERSON_COLOR
        else -> UNKNOWN_COLOR
    }
}
