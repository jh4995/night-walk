package com.bammasil.poc.gl

/**
 * `clahe_gamma`(A1) · `agcwd`(A2)가 공유하는 **sRGB ↔ CIE L\*a\*b\*** GLSL.
 *
 * ### 왜 LAB인가 (그리고 왜 YUV가 아닌가)
 * 상류(모델링 담당 `scripts/lowlight.py`) A1·A2는 둘 다 **LAB의 `L`** 에서 돈다
 * (`docs/research/RESEARCH_20260731_UPSTREAM.md` §2-2). 반면 `INTERFACES.md` §B-2의
 * 제안값은 **YUV의 `Y`** 이고 그 칸은 여전히 `☐`다. **둘은 다른 값이다** — `Y`는 감마
 * 인코딩된 신호의 선형 가중합이고 `L*`은 선형 휘도의 세제곱근 척도다. 상류 구현을 따르되
 * 그 차이를 [RenderArm.LAB_DEVIATION]에 적어 `session.json`으로 내보낸다.
 * 계약이 `Y`로 확정되면 이 파일의 [SRGB_TO_L] 하나만 바꾸면 된다.
 *
 * ### 계수의 출처
 * OpenCV `COLOR_BGR2Lab`(8비트 경로)이 쓰는 값 그대로다 — sRGB 역감마(piecewise) →
 * D65 XYZ → L\*a\*b\*. 상류가 `cv2.cvtColor`를 쓰므로 여기서 다른 백색점이나 다른
 * 근사식을 쓰면 골든 이미지 대조(`INTERFACES.md` §B-6)가 우리 쪽 실수로 어긋난다.
 *
 * ### `a`,`b`를 실제로 계산하지 않는 이유 (수학적으로 같다)
 * ```
 * a = 500 * (f(X/Xn) - f(Y/Yn))     b = 200 * (f(Y/Yn) - f(Z/Zn))
 * ```
 * `a`,`b`를 **그대로 두는 것**은 `f(X/Xn) - f(Y/Yn)`와 `f(Y/Yn) - f(Z/Zn)`를 보존하는 것과
 * 같다. 그래서 [SRGB_TO_LAB_F]가 `f` 삼중항을 그대로 넘기고, [LAB_F_TO_SRGB]는 새 `L*`이
 * 만든 `fy`의 **변화량만큼 fx·fz를 함께 민다.** ×500/÷500 왕복과 8비트 양자화가 빠지므로
 * 상류보다 오히려 정밀하다(그 차이는 [RenderArm.LAB_DEVIATION]에 적는다).
 */
object LabGlsl {

    /** sRGB → L\*(0..100). 히스토그램 패스가 쓴다. */
    const val SRGB_TO_L = "srgbToLabL"

    /** sRGB → f 삼중항. 적용 패스가 쓴다. */
    const val SRGB_TO_LAB_F = "srgbToLabF"

    /** f 삼중항 + 새 L\* → sRGB. 적용 패스가 쓴다. */
    const val LAB_F_TO_SRGB = "labFToSrgb"

    /**
     * `L*`(0..100) → OpenCV 8비트 LAB의 `L` 인덱스(0..255) 스케일. `255/100`이다.
     * 상류가 `cv2.cvtColor`의 8비트 경로에서 히스토그램·LUT를 만들므로 **빈 정의가
     * 같아야** 결과가 비교된다.
     */
    const val L_TO_BIN = "2.55"

    /** 히스토그램·LUT 빈 수. OpenCV의 `histSize`와 같다. */
    const val BIN_COUNT = 256

    /**
     * 세 셰이더(analyze 컴퓨트 · apply 프래그먼트)가 **글자까지 같은 것**을 쓴다.
     * 한쪽만 고치면 히스토그램을 만든 척도와 적용하는 척도가 어긋나 조용히 틀린다.
     */
    val FUNCTIONS = """
        const float LAB_EPS = 0.008856;
        const float LAB_KAPPA = 7.787;
        const float LAB_OFFSET = 0.137931034;
        const vec3 LAB_XN_YN_ZN = vec3(0.950456, 1.0, 1.088754);

        vec3 srgbToLinear(vec3 c) {
            vec3 v = clamp(c, 0.0, 1.0);
            vec3 lo = v / 12.92;
            vec3 hi = pow((v + 0.055) / 1.055, vec3(2.4));
            return mix(lo, hi, step(vec3(0.04045), v));
        }

        vec3 linearToSrgb(vec3 c) {
            vec3 v = clamp(c, 0.0, 1.0);
            vec3 lo = v * 12.92;
            vec3 hi = 1.055 * pow(v, vec3(1.0 / 2.4)) - 0.055;
            return mix(lo, hi, step(vec3(0.0031308), v));
        }

        float labF(float t) {
            return t > LAB_EPS ? pow(t, 1.0 / 3.0) : (LAB_KAPPA * t + LAB_OFFSET);
        }

        float labFInv(float t) {
            float t3 = t * t * t;
            return t3 > LAB_EPS ? t3 : (t - LAB_OFFSET) / LAB_KAPPA;
        }

        // sRGB(0..1) -> L* (0..100). 히스토그램에는 이것만 있으면 된다.
        float $SRGB_TO_L(vec3 c) {
            float y = dot(srgbToLinear(c), vec3(0.212671, 0.715160, 0.072169));
            return 116.0 * labF(y) - 16.0;
        }

        // sRGB(0..1) -> (f(X/Xn), f(Y/Yn), f(Z/Zn)). L* = 116*f.y - 16 이다.
        vec3 $SRGB_TO_LAB_F(vec3 c) {
            vec3 lin = srgbToLinear(c);
            vec3 xyz = vec3(
                dot(lin, vec3(0.412453, 0.357580, 0.180423)),
                dot(lin, vec3(0.212671, 0.715160, 0.072169)),
                dot(lin, vec3(0.019334, 0.119193, 0.950227))
            ) / LAB_XN_YN_ZN;
            return vec3(labF(xyz.x), labF(xyz.y), labF(xyz.z));
        }

        // f 삼중항 + 새 L*(0..100) -> sRGB. a,b는 건드리지 않는다(위 주석 참고).
        vec3 $LAB_F_TO_SRGB(vec3 f, float newL) {
            float fy = (newL + 16.0) / 116.0;
            float d = fy - f.y;
            vec3 xyz = vec3(labFInv(f.x + d), labFInv(fy), labFInv(f.z + d)) * LAB_XN_YN_ZN;
            vec3 lin = vec3(
                dot(xyz, vec3(3.240479, -1.537150, -0.498535)),
                dot(xyz, vec3(-0.969256, 1.875991, 0.041556)),
                dot(xyz, vec3(0.055648, -0.204043, 1.057311))
            );
            return linearToSrgb(lin);
        }
    """.trimIndent()
}
