package com.bammasil.poc.gl

import kotlin.math.roundToInt

/**
 * 카드보드 SBS 의 **눈 기하를 한 곳에 모은 것**. [PassthroughRenderer] 가 GL 뷰포트를 세울 때와
 * `MainActivity` 가 시연 HUD(B/L 표식)를 그 눈 위에 얹을 때가 **같은 식을 봐야** 둘이 어긋나지
 * 않는다. 식을 두 곳에 적으면 영상과 표식이 서로 다른 사각형을 믿게 되고, 그 어긋남은
 * 실기기에서만 보인다.
 *
 * 🔴 **`android.*` 를 import 하지 않는다.** 여기 있는 것은 GL 컨텍스트도 안드로이드 프레임워크도
 * 필요 없는 순수 산술이라, 기기 없이 단독으로 컴파일해 대조할 수 있어야 한다. `android.opengl.*`
 * 가 한 줄이라도 들어오면 그 성질이 사라진다.
 *
 * 🔴 **`Float` 를 `Double` 로 승격시키지 않는다.** 아래 뷰포트 식은 [PassthroughRenderer] 가
 * 쓰던 것을 연산자 단위로 그대로 옮긴 것이고, 한 항이라도 Double 이 되면 `roundToInt()` 의
 * 반올림 경계에서 1px 이 갈린다.
 */
object CardboardGeometry {

    /** 눈 하나가 채우는 화면 비율의 기본값(FOV 슬라이더의 초기 위치와 짝이다). */
    const val DEFAULT_CARDBOARD_IMAGE_SCALE = 0.90f

    /** 좌우 상 정렬의 기본값. 음수가 안쪽이다. */
    const val DEFAULT_CARDBOARD_EYE_OFFSET = -0.08f

    const val MIN_CARDBOARD_IMAGE_SCALE = 0.60f

    const val MAX_CARDBOARD_EYE_OFFSET = 0.30f

    /**
     * 카드보드 프래그먼트 셰이더의 렌즈 프리디스토션 계수(`uLensDistortion` 에 그대로 들어간다).
     * 🔴 이 값이 바뀌면 [visibleCornerFraction] 도 같이 바뀐다 — 그래서 둘이 같은 파일에 산다.
     */
    const val CARDBOARD_LENS_DISTORTION = 0.12f

    /**
     * 왜곡이 없는 경로에서 쓰는 "보이는 모서리" 비율. NORMAL 표시 모드의 present 는
     * `FRAGMENT_SHADER_BLIT` 이라 uv 를 버리지 않으므로 뷰포트 모서리가 곧 영상 모서리다.
     */
    const val NORMAL_CORNER_FRACTION = 1f

    /** 뉴턴 반복 횟수. **고정이라 상수 시간**이다. 8 회면 Float 정밀도를 넘어 수렴한다. */
    private const val NEWTON_ITERATIONS = 8

    /**
     * `f(t) = t + 2*D*t³ - 1 = 0` 의 뉴턴 해. object 초기화 때 **한 번만** 푼다 —
     * 프레임 경로나 HUD 갱신에서 매번 풀지 않는다.
     */
    private val VISIBLE_CORNER_FRACTION: Float = run {
        val d2 = 2f * CARDBOARD_LENS_DISTORTION
        // f(1) = 2D > 0 이고 f' 는 항상 양수라 t=1 에서 시작하면 단조 감소로 해에 붙는다.
        var t = 1f
        repeat(NEWTON_ITERATIONS) {
            val f = t + d2 * t * t * t - 1f
            val df = 1f + 3f * d2 * t * t
            t -= f / df
        }
        t
    }

    /** [PassthroughRenderer.setCardboardTuning] 의 클램프. 멱등이다. */
    fun clampImageScale(imageScale: Float): Float =
        imageScale.coerceIn(MIN_CARDBOARD_IMAGE_SCALE, 1f)

    /** [PassthroughRenderer.setCardboardTuning] 의 클램프. 멱등이다. */
    fun clampEyeOffset(eyeOffset: Float): Float =
        eyeOffset.coerceIn(-MAX_CARDBOARD_EYE_OFFSET, MAX_CARDBOARD_EYE_OFFSET)

    /**
     * 왼쪽 눈의 폭. 🔴 **정수 나눗셈이다** — 오른쪽 눈은 `surfaceWidth - leftEyeWidth` 로
     * 나머지를 가져가므로 홀수 폭에서도 두 눈이 화면을 정확히 덮는다.
     */
    fun leftEyeWidth(surfaceWidth: Int): Int = surfaceWidth / 2

    /**
     * 눈 하나의 GL 뷰포트를 [out] 에 `x, y, w, h` 로 쓴다(GL 좌표계 — **좌하단 원점**).
     *
     * 🔴 **반환값을 만들지 않는다.** 이 함수는 프레임마다 두 번 불리는 자리에 있어서
     * 객체를 하나라도 만들면 그만큼 GC 가 프레임 경로에 섞인다.
     *
     * [imageScale] · [eyeOffset] 은 **안에서 클램프한다**(멱등) — 호출자가 슬라이더 원값을
     * 그대로 넘겨도 렌더러가 보는 값과 항상 같아지게 하려는 것이다.
     */
    fun eyeViewport(
        eyeLeft: Int,
        eyeWidth: Int,
        horizontalDirection: Int,
        surfaceHeight: Int,
        processWidth: Int,
        processHeight: Int,
        imageScale: Float,
        eyeOffset: Float,
        out: IntArray,
    ) {
        val scale = clampImageScale(imageScale)
        val offset = clampEyeOffset(eyeOffset)
        // ⚠ [sourceAspect]는 **회전 전** 처리 해상도의 종횡비다. present가 회전을 걸게 된 뒤로
        //    90/270°에서는 이 비가 뒤집혀야 맞지만, 종횡비 정책이 미정이라(STATUS 이슈 68)
        //    이번 변경에서는 건드리지 않는다. 실제로 cardboard는 MainActivity가 LANDSCAPE를
        //    강제해 rotationDegrees가 0/180이 되므로 회전이 사실상 없어질 것으로 보는데,
        //    **그것은 실기기에서 확인할 항목**이다 — 코드에 분기를 만들지 않는다.
        // 🔴 협상 전(processWidth == 0)에는 16:9 로 떨어진다. 이 폴백으로 계산된 사각형은
        //    실제 사각형이 아니므로, 협상이 끝나면 부르는 쪽이 다시 물어야 한다.
        val sourceAspect = if (processWidth > 0 && processHeight > 0) {
            processWidth.toFloat() / processHeight.toFloat()
        } else {
            16f / 9f
        }
        val maxWidth = eyeWidth * scale
        val maxHeight = surfaceHeight * scale
        var contentWidth = maxWidth
        var contentHeight = contentWidth / sourceAspect
        if (contentHeight > maxHeight) {
            contentHeight = maxHeight
            contentWidth = contentHeight * sourceAspect
        }
        val offsetPx = eyeWidth * offset * horizontalDirection
        out[0] = (
            eyeLeft + (eyeWidth - contentWidth) * 0.5f + offsetPx
        ).roundToInt()
        out[1] = ((surfaceHeight - contentHeight) * 0.5f).roundToInt()
        out[2] = contentWidth.roundToInt().coerceAtLeast(1)
        out[3] = contentHeight.roundToInt().coerceAtLeast(1)
    }

    /**
     * 눈 뷰포트의 중심에서 대각선으로 나갈 때 **영상이 실제로 남아 있는 마지막 비율**.
     *
     * 카드보드 프래그먼트 셰이더는 `p = (vTexCoord - 0.5) * 2.0`,
     * `uv = 0.5 + 0.5 * p * (1.0 + D * dot(p, p))` 를 계산하고 `uv` 가 [0,1] 밖이면 그 픽셀을
     * `vec4(0,0,0,1)` 로 버린다. 대각선 `p = (t, t)` 에서 `dot(p,p) = 2t²` 이므로 남는 조건은
     * `t * (1 + 2*D*t²) <= 1` 이고, 등호가 되는 t 가 이 값이다
     * (D = [CARDBOARD_LENS_DISTORTION] = 0.12 → t ≈ 0.8517).
     *
     * 🔴 **뷰포트의 기하 모서리에는 영상이 없다** — 거기는 셰이더가 버린 검정이다. HUD 표식을
     * 기하 모서리에 붙이면 카드보드에서 검은 여백 위에 떠 렌즈 밖으로 밀린다.
     */
    fun visibleCornerFraction(): Float = VISIBLE_CORNER_FRACTION

    /**
     * 눈 사각형 [rect](`x, y, w, h`, GL 좌표)에서 **보이는 영상의 우상단**을 [out] 에
     * `x, y` 로 쓴다. 좌표계는 그대로 GL(좌하단 원점)이라 [out]`[1]` 은 위쪽 변이다.
     *
     * [cornerFraction] 은 왜곡이 있는 경로면 [visibleCornerFraction], 없으면
     * [NORMAL_CORNER_FRACTION] 이다.
     */
    fun hudAnchor(rect: IntArray, cornerFraction: Float, out: IntArray) {
        val halfWidth = rect[2] * 0.5f
        val halfHeight = rect[3] * 0.5f
        out[0] = (rect[0] + halfWidth + cornerFraction * halfWidth).roundToInt()
        out[1] = (rect[1] + halfHeight + cornerFraction * halfHeight).roundToInt()
    }

    /** 카드보드(렌즈 왜곡 있음) 기본값을 쓰는 [hudAnchor]. */
    fun hudAnchor(rect: IntArray, out: IntArray) =
        hudAnchor(rect, VISIBLE_CORNER_FRACTION, out)
}
