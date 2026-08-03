package com.bammasil.poc.gl

/**
 * 셰이더 소스에서 **색공간 변환 호출 지점을 기계가 센다.**
 *
 * ### 왜 필요한가
 * 조합 arm 착수의 근거였던 *"두 arm이 LAB 변환을 공유한다"* 는 문장이 **코드와 어긋난다** —
 * [DragoStage]는 [LabGlsl]을 한 번도 쓰지 않고 선형 RGB에서 돈다(sRGB 선형화도 piecewise
 * 함수가 아니라 `pow(x, uSrcGamma)`다). 그러니 "공유한다/안 한다"를 사람의 기억이 아니라
 * **셰이더 텍스트 자체**로 말해야 한다.
 *
 * 세는 대상은 `glShaderSource`에 **실제로 넘기는 그 String 객체**이므로 셰이더 텍스트와
 * 어긋날 수 없다. 결과는 `session.json`의 `stage2_params.color_transform_sites`로 나간다.
 *
 * ⚠ **정적 호출 지점 수**이지 실행 횟수가 아니다. 예를 들어 CLAHE analyze는 타일 픽셀 루프
 * **안에서** 부르므로 실제로는 픽셀당 1회 × 타일 픽셀 수다. 픽셀당·프레임당 환산값은
 * 같은 세션의 선언 블록(`color_transform_declared`)에 따로 있다.
 *
 * ⚠ **함수 정의 줄은 빼고 센다.** [LabGlsl.FUNCTIONS]가 통째로 삽입되므로, 정의를 세면
 * "쓰지도 않는 함수를 부른다"로 읽힌다.
 *
 * **hot path가 아니다** — `onSurfaceCreated`에서 1회만 돈다.
 */
object ColorTransformCensus {

    /**
     * 셀 토큰. [LabGlsl]의 공개 함수 이름과 그 안에서 불리는 두 개다.
     *
     * ⚠ 뒤 두 개([LabGlsl.LINEAR_TO_L] · [LabGlsl.LINEAR_TO_LAB_F])는 **융합 arm이
     * 들어오면서 추가**했다. 없으면 융합 arm의 계수가 sRGB 계열만 세어 전부 0으로 보이고,
     * 읽는 사람이 "LAB 변환이 아예 없다"고 잘못 읽는다. 체인·단품 arm에서는 0이며 그 0도
     * 정보다("그 arm은 선형 입력 변형을 쓰지 않는다").
     *
     * ⚠ `labFInv`(역방향 f)는 여기 없다 — 토큰 목록이 그렇게 확정됐다. 역방향 평가 수는
     * 선언 블록의 `lab_f_inverse_per_pixel`에 있다.
     */
    val TOKENS: List<String> = listOf(
        LabGlsl.SRGB_TO_L,
        LabGlsl.SRGB_TO_LAB_F,
        LabGlsl.LAB_F_TO_SRGB,
        "srgbToLinear",
        "linearToSrgb",
        "labF",
        LabGlsl.LINEAR_TO_L,
        LabGlsl.LINEAR_TO_LAB_F,
    )

    /**
     * 🔴 **최상위 진입점** — `main()`에서만 불리므로 이 토큰들의 계수는 **살아 있는 호출
     * 수와 같다.** 그 패스가 실제로 무엇을 쓰는지는 여기서 읽는다.
     */
    val ENTRY_POINT_TOKENS: List<String> = listOf(
        LabGlsl.SRGB_TO_L,
        LabGlsl.SRGB_TO_LAB_F,
        LabGlsl.LAB_F_TO_SRGB,
        LabGlsl.LINEAR_TO_L,
        LabGlsl.LINEAR_TO_LAB_F,
    )

    /**
     * 🔴 **내부 헬퍼** — 진입점 함수들의 **본문 안에서** 불린다. [LabGlsl.FUNCTIONS]가 패스에
     * 통째로 삽입되므로 **그 패스가 부르지 않는 진입점의 본문까지 세어진다.** 즉 이 토큰들의
     * 계수는 **살아 있는 호출 수의 상한**이며, 픽셀당 실제 횟수는 선언 블록
     * (`color_transform_declared`)이 진입점에서 따라간 값이다.
     */
    val INNER_TOKENS: List<String> = listOf("srgbToLinear", "linearToSrgb", "labF")

    /**
     * 세는 방법과 읽는 법. `session.json`에 그대로 나간다 — 이 두 단서가 빠지면 숫자가
     * 곧바로 오독된다.
     */
    const val NOTE =
        "세는 대상은 glShaderSource에 실제로 넘기는 셰이더 문자열이며, **정적 호출 지점 수**다" +
            "(함수 정의 줄은 뺐다). 읽을 때 단서 둘: " +
            "(a) **실행 횟수가 아니다** — CLAHE analyze는 타일 픽셀 루프 **안에서** 부르므로 " +
            "실제로는 픽셀당 1회 × 그 타일의 픽셀 수다. 픽셀당·프레임당 환산은 같은 블록의 " +
            "color_transform_declared에 따로 있다. " +
            "(b) **중첩을 이중 계상하지 말 것** — srgbToLinear는 srgbToLabL / srgbToLabF " +
            "**안에서** 불린다. 그래서 srgbToLabF를 1번 부르는 패스에서도 srgbToLinear 계수가 " +
            "따로 올라간다. 두 값을 더하면 같은 변환을 두 번 세는 것이다. " +
            "🔴 (c) **진입점 계수와 내부 헬퍼 계수를 같은 눈으로 읽지 말 것.** " +
            "entry_point_tokens(srgbToLabL / srgbToLabF / labFToSrgb / linearToLabL / " +
            "linearToLabF)는 main()에서만 불리므로 **계수가 곧 살아 있는 호출 수**다 — " +
            "'이 패스가 무엇을 쓰는가'는 여기서 읽는다. 반면 inner_tokens(srgbToLinear / " +
            "linearToSrgb / labF)는 진입점 **본문 안에서** 불리는데 LabGlsl.FUNCTIONS가 패스에 " +
            "**통째로 삽입되므로 그 패스가 부르지도 않는 진입점의 본문까지 세어진다** — " +
            "즉 **살아 있는 호출 수의 상한**이다. " +
            "실제 예: 융합 arm은 srgbToLinear를 **한 번도 실행하지 않는데**(진입점 " +
            "srgbToLabL·srgbToLabF 계수가 0이다) 죽은 본문 때문에 srgbToLinear 계수가 2로 " +
            "나온다. 픽셀당 실제 횟수는 **color_transform_declared**를 볼 것 — 그쪽은 " +
            "진입점에서 호출 그래프를 따라간 값이다. " +
            "⚠ drago 패스의 sRGB 선형화는 여기 잡히지 않는다 — LabGlsl의 piecewise 함수가 " +
            "아니라 pow(x, uSrcGamma)라서다(그 자체가 '두 스테이지가 LAB 변환을 공유하지 " +
            "않는다'는 증거다)"

    /**
     * @param sourcesByPass 패스 이름 → 그 패스가 `glShaderSource`에 넘기는 문자열들
     *   (정점 + 프래그먼트, 또는 컴퓨트 하나).
     * @return 패스 이름 → (토큰 → 호출 지점 수). **입력 순서를 유지한다**(패스 순서다).
     */
    fun countByPass(
        sourcesByPass: List<Pair<String, List<String>>>,
    ): List<Pair<String, Map<String, Int>>> = sourcesByPass.map { (pass, sources) ->
        val counts = LinkedHashMap<String, Int>(TOKENS.size)
        for (token in TOKENS) {
            counts[token] = sources.sumOf { callSites(it, token) }
        }
        pass to counts
    }

    /**
     * `token(` 등장 수에서 **정의 줄**(`float token(` / `vec3 token(`)을 뺀다.
     *
     * `\b`가 접두 충돌을 막는다 — `labF`는 `labFToSrgb(`·`labFInv(`에 걸리지 않는다
     * (`labF` 뒤가 `(`나 공백이어야 하는데 `T`/`I`가 온다).
     */
    private fun callSites(source: String, token: String): Int {
        val quoted = Regex.escape(token)
        val all = Regex("""\b$quoted\s*\(""").findAll(source).count()
        val definitions = Regex("""\b(?:float|vec2|vec3|vec4)\s+$quoted\s*\(""")
            .findAll(source).count()
        return all - definitions
    }
}
