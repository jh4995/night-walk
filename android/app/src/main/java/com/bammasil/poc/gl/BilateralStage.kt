package com.bammasil.poc.gl

import android.util.Log

/**
 * `+bf` — 상류 `scripts/lowlight.py`의 `Bilateral(d=7, sigma_color=50, sigma_space=50)`을
 * **프래그먼트 한 패스**로 옮긴 것. ② 출력 뒤에 붙는 노이즈 억제 단이고 `stage_d_denoise_ms`를
 * 낸다([RenderArm.DRAGO_CLAHE_CHAIN_BF] · [RenderArm.DRAGO_CLAHE_FUSED_BF]).
 *
 * ### 컴퓨트도 SSBO도 쓰지 않는다
 * bilateral은 **gather 필터**다 — 픽셀마다 자기 이웃만 읽고 전역 통계가 없다. 그래서
 * [DragoClaheChainStage]·[DragoClaheFusedStage]와 달리 통계 패스도 SSBO도 필요 없고,
 * 프래그먼트 셰이더 하나가 전부다. 클래스 골격(`ready` / `status` / `releaseGl` /
 * `onProcessSizeChanged`)만 그 둘과 같게 맞췄다.
 *
 * ⚠ **GL 객체를 소유하지 않는다.** 적용 프래그먼트 프로그램은 다른 arm의 적용 패스들과 같이
 * [PassthroughRenderer]가 만들고 지운다(`bilateralProgram`) — 이 클래스가 들고 있는 것은
 * 해상도에서 유도한 [texelX]/[texelY]와 상태 문장뿐이다.
 *
 * ⚠ **프레임 간 상태가 아니다** ([RenderArm.TEMPORAL_STATE] 그대로 stateless다). 이웃만 보므로
 * 앞 스테이지의 출렁임을 줄이지도 늘리지도 않는다([RenderArm.BF_FLICKER_NOTE]).
 *
 * 이식이 상류와 어디서 갈리는지는 [RenderArm.BF_DEVIATION]에 있고 `session.json`에 그대로
 * 나간다 — **`cv2.bilateralFilter`의 내부 정의는 이 저장소에 기록돼 있지 않아서 OpenCV
 * 구현을 읽어 옮겼다.** 그 사실을 숨기면 나중에 골든 대조가 어긋날 때 원인을 못 찾는다.
 *
 * **스레드 규약: 전부 GL 스레드에서만 부른다.**
 */
class BilateralStage {

    var ready = false
        private set

    var status: String = "아직 준비하지 않았다 (arm에 +bf가 없다)"
        private set

    /** 1/처리해상도. `uTexel`로 나간다 — 셰이더에 해상도를 하드코딩하지 않는 지점이다. */
    var texelX = 0f
        private set

    var texelY = 0f
        private set

    // ── 컨텍스트 수명 ─────────────────────────────────────────────────────

    /**
     * `onSurfaceCreated`에서 GL 능력 프로브 직후. 못 쓰면 스스로 꺼지고 이유를 남긴다.
     *
     * ⚠ [GlCapabilities.computeShaderCapable]을 보지만 **컴퓨트를 쓰려는 것이 아니다** —
     * 그 필드의 정의가 "ES 3.1 이상인가"이고, 이 패스의 셰이더가 `#version 310 es`라서다.
     * 왜 310인가: 반경 루프의 상한이 uniform이라 GLSL ES 1.00의 제한된 for 형식에 맞지 않고,
     * 정점 셰이더를 [ES31_QUAD_VERTEX_SHADER]로 **그대로 재사용**해 다른 적용 패스와 정점
     * 단계를 같게 두려면 프래그먼트도 같은 버전이어야 한다(ESSL은 한 프로그램에서 버전을
     * 섞지 못한다). 300 es로 낮출 수도 있지만 그러면 이 arm만 쓰는 정점 셰이더 사본이 생긴다.
     */
    fun onContextCreated(capabilities: GlCapabilities?) {
        val es31 = capabilities?.computeShaderCapable ?: GlCapabilitiesProbe.UNKNOWN
        if (es31 != GlCapabilitiesProbe.TRUE) {
            disable(
                "ES 3.1 이상이 아니다(GL 능력 프로브: computeShaderCapable=$es31, " +
                    "그 필드의 정의가 'ES 3.1 이상인가'다) — 이 패스의 셰이더가 " +
                    "#version 310 es라 컴파일할 수 없다"
            )
            return
        }
        ready = true
        status =
            "준비 완료 — bilateral **1패스**(프래그먼트 gather). 컴퓨트·SSBO를 쓰지 않는다. " +
                "d=${RenderArm.BF_D} → radius=${RenderArm.BF_RADIUS}(=d/2, 정수 나눗셈)이고 " +
                "이웃은 **원형**(i*i+j*j <= radius*radius)이라 " +
                "**${RenderArm.BF_TAP_COUNT}탭**이다(7x7 사각형 49탭이 아니다). " +
                "sigma_color=${RenderArm.BF_SIGMA_COLOR}(0..255 단위) / " +
                "sigma_space=${RenderArm.BF_SIGMA_SPACE}(픽셀 단위)를 uniform으로 넘긴다. " +
                "② 출력 RGBA8을 **sRGB 그대로** 필터한다(선형화·LAB 없음 — 상류가 8비트 BGR " +
                "이미지에 걸기 때문이다). 알파는 필터하지 않고 통과시킨다"
        Log.i(TAG, status)
    }

    /** 처리 해상도가 정해졌을 때. 셰이더에 해상도를 하드코딩하지 않는 지점이다. */
    fun onProcessSizeChanged(width: Int, height: Int) {
        // 0 나눗셈을 만들지 않는다. 협상 전에는 0으로 두고, 그 상태에서는 arm이 폴백한다.
        texelX = if (width > 0) 1f / width.toFloat() else 0f
        texelY = if (height > 0) 1f / height.toFloat() else 0f
    }

    /**
     * 컨텍스트가 사라졌다. **이 클래스는 GL 객체를 갖고 있지 않다** — 프로그램은
     * [PassthroughRenderer]가 `releaseGlResources`에서 지운다. 여기서는 유도값과 상태만
     * 되돌린다(다른 스테이지와 호출 지점을 같게 두기 위해 함수 자체는 둔다).
     */
    fun releaseGl() {
        texelX = 0f
        texelY = 0f
        ready = false
        status = "GL 컨텍스트가 사라졌다 (이 스테이지가 소유한 GL 객체는 없다)"
    }

    // ── 내부 ─────────────────────────────────────────────────────────────

    private fun disable(reason: String) {
        ready = false
        status = reason
        Log.w(TAG, "bilateral 비활성: $reason")
    }

    companion object {
        const val TAG = "BilateralStage"

        /**
         * bilateral 1패스. **`cv2.bilateralFilter`(`bilateralFilter_8u`)의 정의를 그대로
         * 옮긴 것**이며, 그 정의가 상류 문서에 없어 OpenCV 구현을 읽어 옮겼다는 사실은
         * [RenderArm.BF_DEVIATION]에 적혀 `session.json`으로 나간다.
         *
         * 옮긴 규칙 네 가지:
         *  - **반경** `radius = d/2`(정수 나눗셈). `sigma_space`는 반경을 정하지 않는다.
         *  - **원형 이웃** — `i*i + j*j <= radius*radius`인 탭만 쓴다
         *    ([RenderArm.BF_TAP_COUNT]탭). OpenCV가 `sqrt(i*i+j*j) > radius`를 `continue`로
         *    걸러 내는 것과 같은 조건이다.
         *  - **색 거리는 3채널 L1 합을 제곱한다** — OpenCV는 `color_weight[|Δb|+|Δg|+|Δr|]`를
         *    `exp(i*i * -0.5/σc²)`로 미리 구워 둔다. **채널별 분리가 아니다.**
         *  - **스케일** — OpenCV는 0..255 uchar에서 돈다. 셰이더는 0..1을 샘플하므로 L1 합에
         *    255를 곱해 255 단위로 만든 뒤 σc와 비교한다. σ는 상류 원문 값 그대로 넣는다.
         *
         * `exp(a) * exp(b) = exp(a + b)`라 공간·색 가중을 **한 번의 exp**로 합쳤다 —
         * OpenCV가 두 LUT를 곱하는 것과 대수적으로 같다.
         *
         * ⚠ σs=50이면 반경 3 안에서 공간 가중이 `exp(0)=1` ~ `exp(-9/5000)=0.9982`로 **사실상
         * 평탄하다.** 상류 값이 그러니 그대로 쓴다 — 이 관찰도 [RenderArm.BF_DEVIATION]에 있다.
         *
         * 루프 상한이 uniform이므로 드라이버가 언롤하지 못할 수 있다. 그래도 상수로 굽지
         * 않는 이유: §B-5가 전부 ☐이고, 팀장이 값을 확정하면 셰이더를 고치지 않고 교체해야
         * 한다(다른 ② 파라미터와 같은 규약).
         */
        val DENOISE_SHADER = """
            #version 310 es
            precision highp float;
            precision highp int;
            in vec2 vTexCoord;
            out vec4 fragColor;
            uniform sampler2D uTexture;
            uniform int ${RenderArm.BF_RADIUS_UNIFORM};
            uniform float ${RenderArm.BF_SIGMA_COLOR_UNIFORM};
            uniform float ${RenderArm.BF_SIGMA_SPACE_UNIFORM};
            uniform vec2 ${RenderArm.BF_TEXEL_UNIFORM};
            void main() {
                vec4 center = texture(uTexture, vTexCoord);
                // OpenCV: gauss_color_coeff = -0.5/(sigma_color*sigma_color)
                //         gauss_space_coeff = -0.5/(sigma_space*sigma_space)
                float colorCoeff = -0.5 / (${RenderArm.BF_SIGMA_COLOR_UNIFORM} *
                                           ${RenderArm.BF_SIGMA_COLOR_UNIFORM});
                float spaceCoeff = -0.5 / (${RenderArm.BF_SIGMA_SPACE_UNIFORM} *
                                           ${RenderArm.BF_SIGMA_SPACE_UNIFORM});
                int radius = ${RenderArm.BF_RADIUS_UNIFORM};
                int r2 = radius * radius;
                vec3 sum = vec3(0.0);
                float wsum = 0.0;
                for (int i = -radius; i <= radius; ++i) {
                    for (int j = -radius; j <= radius; ++j) {
                        int d2 = i * i + j * j;
                        // 원형 이웃. OpenCV의 sqrt(i*i+j*j) > radius → continue 와 같다.
                        if (d2 > r2) continue;
                        vec2 off = vec2(float(j), float(i)) * ${RenderArm.BF_TEXEL_UNIFORM};
                        // 경계는 샘플러의 CLAMP_TO_EDGE다. OpenCV 기본은
                        // BORDER_REFLECT_101이라 이 부분이 다르다(BF_DEVIATION).
                        vec3 s = texture(uTexture, vTexCoord + off).rgb;
                        // 3채널 L1 합을 **255 단위로** 만든 뒤 제곱한다(채널 분리가 아니다).
                        vec3 dc = abs(s - center.rgb) * 255.0;
                        float l1 = dc.r + dc.g + dc.b;
                        float w = exp(l1 * l1 * colorCoeff + float(d2) * spaceCoeff);
                        sum += s * w;
                        wsum += w;
                    }
                }
                // 중심 탭의 가중이 exp(0)=1이라 wsum >= 1이다 — 0 나눗셈이 생기지 않는다.
                // 알파는 필터하지 않고 그대로 통과시킨다.
                fragColor = vec4(sum / wsum, center.a);
            }
        """.trimIndent()

        /**
         * 색공간 변환 **자동 계수**용. 기존 arm의 패스 목록에 bf 패스 하나를 끼운다.
         *
         * 규약: 패스 목록의 **마지막이 present**이므로 그 앞에 넣는다. 목록을 복사해서 새로
         * 쓰지 않는 이유는 하나다 — 복사하면 앞 arm의 패스 목록이 바뀔 때 한쪽만 고쳐진다.
         *
         * 패스 이름은 `stage2_bilateral`이며 `lib/frame_log.py`의 `STAGE2_BILATERAL` 토큰과
         * 같은 표기다. 이 패스의 색공간 변환 토큰 계수는 **전부 0이어야 한다**(위 셰이더가
         * [LabGlsl]을 한 번도 삽입하지 않는다) — 그것이 기계로 확증된다.
         */
        fun withDenoisePass(
            base: List<Pair<String, List<String>>>,
        ): List<Pair<String, List<String>>> {
            val denoise = "stage2_bilateral" to listOf(ES31_QUAD_VERTEX_SHADER, DENOISE_SHADER)
            if (base.isEmpty()) return listOf(denoise)
            return base.dropLast(1) + denoise + base.last()
        }
    }
}
