package com.bammasil.poc.gl

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * `drago` arm의 **전역 통계 부분**. Drago 톤매핑은 화면 전체의 로그평균 휘도와 최대 휘도가
 * 있어야 톤커브가 정해지므로 픽셀 셰이더 하나로 끝나지 않는다.
 *
 * ### 왜 컴퓨트 + SSBO atomic인가 (프래그먼트 핑퐁 리덕션이 아니라)
 * 프래그먼트로 리덕션하면 해상도를 절반씩 줄이는 **log2(N) 패스**가 필요하다(720p면 6~7패스).
 * `docs/FRAME_LOG_SCHEMA.md` §2는 하위 패스를 앱이 임의로 합치는 것을 금지하므로, 그렇게
 * 짜면 D 계열 슬롯 3개(analyze/build/apply)로는 담을 수 없어 슬롯을 먼저 늘려야 한다.
 * 측정 기기가 **ES 3.2**임을 프로브로 확인했고([GlCapabilities.computeShaderCapable]),
 * 워크그룹 공유메모리 리덕션 + SSBO atomic이면 **전체 화면 통계가 dispatch 한 번**에 접힌다
 * → 슬롯 3개에 정확히 들어간다:
 * ```
 * analyze  컴퓨트 1 dispatch  전역 통계(Σlog 휘도, 최대 휘도)   stage_d_analyze_ms
 * build    컴퓨트 1 스레드    통계 → 톤커브 계수                stage_d_build_ms
 * apply    프래그먼트 1패스   톤맵 + 감마                       stage_d_apply_ms
 * ```
 *
 * ### 결과를 CPU로 읽어오지 않는다
 * 통계를 `glMapBufferRange`로 읽으면 **GPU 동기화가 걸려 측정 대상 자체가 오염된다**
 * (`GpuTimerRing`이 query 결과를 즉시 읽지 않는 것과 같은 이유). 그래서 계수 계산도 GPU에서
 * 하고([build]), 적용 프래그먼트가 같은 SSBO를 읽는다(ES 3.2는 프래그먼트 SSBO 읽기가
 * 보장되지만 그래도 [onContextCreated]에서 실측 확인한다).
 *
 * ### 누산기 리셋도 GPU가 한다
 * 매 프레임 `glBufferSubData`로 0을 밀어 넣으면 CPU→GPU 갱신이 프레임 경로에 들어온다.
 * 대신 [build]가 계수를 쓴 **직후 누산기를 0으로 되돌린다** — 다음 프레임의 analyze가 그
 * 위에 더한다. 버퍼 생성 시점 값도 0이므로 첫 프레임도 성립한다.
 *
 * ⚠ **프레임 간 상태가 아니다.** 누산기는 같은 프레임 안에서 만들고 쓰고 지운다
 * ([RenderArm.TEMPORAL_STATE] 그대로 stateless다).
 *
 * **스레드 규약: 전부 GL 스레드에서만 부른다.**
 */
class DragoStage {

    /** 프로그램·버퍼가 모두 준비됐는가. false면 이 arm은 그릴 수 없다. */
    var ready = false
        private set

    /** 왜 준비됐는지/왜 못 됐는지. `session.json`에 그대로 나간다. */
    var status: String = "아직 준비하지 않았다 (arm != drago)"
        private set

    private var analyzeProgram = 0
    private var buildProgram = 0
    private var statsBuffer = 0

    private var uAnalyzeTexture = -1
    private var uAnalyzeSrcGamma = -1
    private var uAnalyzeSumScale = -1
    private var uBuildSumScale = -1
    private var uBuildPixelCount = -1
    private var uBuildBias = -1

    /** 처리 해상도에서 계산한 고정소수 스케일. [computeSumScale] 참고. */
    private var sumScale = 0f
    private var pixelCount = 0

    /** 32바이트 0으로 초기화용. 한 번만 만든다(프레임 경로에서는 쓰지 않는다). */
    private var zeroInit: ByteBuffer? = null

    private val scratch = IntArray(1)

    // ── 컨텍스트 수명 ─────────────────────────────────────────────────────

    /**
     * `onSurfaceCreated`에서 GL 능력 프로브 직후에 부른다. 컴퓨트를 못 쓰거나 프래그먼트가
     * SSBO를 못 읽으면 **여기서 끈다** — 값을 지어내지 않고 arm이 폴백하게 둔다.
     */
    fun onContextCreated(capabilities: GlCapabilities?) {
        releaseGl()
        val compute = capabilities?.computeShaderCapable ?: GlCapabilitiesProbe.UNKNOWN
        if (compute != GlCapabilitiesProbe.TRUE) {
            disable(
                "컴퓨트 셰이더를 쓸 수 없다(GL 능력 프로브: computeShaderCapable=$compute) — " +
                    "전역 리덕션을 단일 dispatch로 접을 수 없다"
            )
            return
        }
        drainGlErrors()
        // 프래그먼트에서 SSBO를 읽지 못하면 계수를 CPU 왕복 없이 넘길 수단이 없다.
        // ES 3.1의 하한은 0이고 ES 3.2는 4 이상이라, 확인하지 않고 쓰면 조용히 링크 실패한다.
        scratch[0] = 0
        GLES20.glGetIntegerv(GLES31.GL_MAX_FRAGMENT_SHADER_STORAGE_BLOCKS, scratch, 0)
        val err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR || scratch[0] < 1) {
            drainGlErrors()
            disable(
                "프래그먼트 셰이더가 SSBO를 읽을 수 없다 " +
                    "(GL_MAX_FRAGMENT_SHADER_STORAGE_BLOCKS=${scratch[0]}, " +
                    "glGetError=0x${Integer.toHexString(err)})"
            )
            return
        }
        val fragmentBlocks = scratch[0]

        analyzeProgram = buildComputeProgram(ANALYZE_SHADER, "analyze")
        buildProgram = buildComputeProgram(BUILD_SHADER, "build")
        if (analyzeProgram == 0 || buildProgram == 0) {
            releaseGl()
            disable("컴퓨트 프로그램 컴파일/링크에 실패했다 — 위 로그(DragoStage)에 원문이 있다")
            return
        }
        uAnalyzeTexture = GLES20.glGetUniformLocation(analyzeProgram, "uTexture")
        uAnalyzeSrcGamma =
            GLES20.glGetUniformLocation(analyzeProgram, RenderArm.DRAGO_SRC_GAMMA_UNIFORM)
        uAnalyzeSumScale = GLES20.glGetUniformLocation(analyzeProgram, "uSumScale")
        uBuildSumScale = GLES20.glGetUniformLocation(buildProgram, "uSumScale")
        uBuildPixelCount = GLES20.glGetUniformLocation(buildProgram, "uPixelCount")
        uBuildBias = GLES20.glGetUniformLocation(buildProgram, RenderArm.DRAGO_BIAS_UNIFORM)

        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        statsBuffer = ids[0]
        val zeros = zeroInit ?: ByteBuffer
            .allocateDirect(STATS_BYTES)
            .order(ByteOrder.nativeOrder())
            .also { zeroInit = it }
        zeros.position(0)
        GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, statsBuffer)
        GLES20.glBufferData(
            GLES31.GL_SHADER_STORAGE_BUFFER, STATS_BYTES, zeros, GLES30.GL_DYNAMIC_COPY
        )
        GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)
        val bufErr = GLES20.glGetError()
        if (bufErr != GLES20.GL_NO_ERROR) {
            releaseGl()
            disable("통계 SSBO 생성에 실패했다 (glGetError=0x${Integer.toHexString(bufErr)})")
            return
        }

        ready = true
        status =
            "준비 완료 — 리덕션 = 컴퓨트 dispatch 1회(워크그룹 " +
                "${LOCAL_SIZE}x$LOCAL_SIZE 공유메모리 리덕션 + SSBO atomic), " +
                "계수 = 컴퓨트 1스레드, 적용 = 프래그먼트 1패스. " +
                "GL_MAX_FRAGMENT_SHADER_STORAGE_BLOCKS=$fragmentBlocks. " +
                "통계를 CPU로 읽지 않는다(읽으면 GPU 동기화로 측정이 오염된다)"
        Log.i(TAG, status)
    }

    /**
     * 처리 해상도가 정해졌을 때(또는 바뀌었을 때) 부른다. GL 자원은 해상도에 의존하지 않고
     * **고정소수 스케일만** 다시 잡는다. 해상도를 하드코딩하지 않는 지점이다.
     */
    fun onProcessSizeChanged(width: Int, height: Int) {
        pixelCount = width * height
        sumScale = computeSumScale(pixelCount)
    }

    fun releaseGl() {
        if (analyzeProgram != 0) GLES20.glDeleteProgram(analyzeProgram)
        if (buildProgram != 0) GLES20.glDeleteProgram(buildProgram)
        if (statsBuffer != 0) GLES20.glDeleteBuffers(1, intArrayOf(statsBuffer), 0)
        analyzeProgram = 0
        buildProgram = 0
        statsBuffer = 0
        ready = false
        status = "GL 컨텍스트가 사라져 자원을 반납했다"
    }

    // ── 프레임 경로 (렌더 스레드 hot path) ────────────────────────────────

    /**
     * 패스2 — 전역 통계. `stage_d_analyze_ms`.
     *
     * 선행 배리어가 필요한 이유: **직전 프레임의 apply 프래그먼트가 같은 SSBO를 읽었다.**
     * 그 읽기가 끝나기 전에 이번 프레임 atomic이 덮으면 안 된다(WAR). `glMemoryBarrier`는
     * 명령 하나이고 실제 대기는 GPU가 하므로, 대기 비용은 이 패스의 query에 담긴다.
     *
     * ⚠ 소스 텍스처는 직전 패스가 FBO로 그린 것이다. **프레임버퍼 쓰기 → 텍스처 읽기는
     * GL이 자동으로 순서를 맞춰 주므로**(비일관 접근이 아니다) 그쪽에는 배리어를 걸지 않는다.
     */
    fun analyze(textureId: Int, width: Int, height: Int) {
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
        GLES20.glUseProgram(analyzeProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(uAnalyzeTexture, 0)
        // 상수로 박지 않고 uniform으로 넣는다(INTERFACES.md §B-5 요청).
        GLES20.glUniform1f(uAnalyzeSrcGamma, RenderArm.DRAGO_SRC_GAMMA)
        GLES20.glUniform1f(uAnalyzeSumScale, sumScale)
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, STATS_BINDING, statsBuffer)
        GLES31.glDispatchCompute(groups(width), groups(height), 1)
    }

    /** 패스3 — 통계 → 톤커브 계수. `stage_d_build_ms`. 스레드 1개짜리 dispatch다. */
    fun build() {
        // analyze의 atomic 쓰기가 보이도록. 이게 없으면 계수가 이전 프레임 값일 수 있다.
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
        GLES20.glUseProgram(buildProgram)
        GLES20.glUniform1f(uBuildSumScale, sumScale)
        GLES20.glUniform1f(uBuildPixelCount, pixelCount.toFloat())
        GLES20.glUniform1f(uBuildBias, RenderArm.DRAGO_BIAS)
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, STATS_BINDING, statsBuffer)
        GLES31.glDispatchCompute(1, 1, 1)
    }

    /** 패스4 앞. build가 쓴 계수를 프래그먼트가 볼 수 있게 한다. */
    fun beforeApply() {
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, STATS_BINDING, statsBuffer)
    }

    // ── 내부 ─────────────────────────────────────────────────────────────

    private fun groups(size: Int): Int = (size + LOCAL_SIZE - 1) / LOCAL_SIZE

    private fun disable(reason: String) {
        ready = false
        status = reason
        Log.w(TAG, "drago 비활성: $reason")
    }

    private fun buildComputeProgram(source: String, label: String): Int {
        val shader = GLES20.glCreateShader(GLES31.GL_COMPUTE_SHADER)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val statusArr = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, statusArr, 0)
        if (statusArr[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "컴퓨트 셰이더 컴파일 실패($label): ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, shader)
        GLES20.glLinkProgram(program)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, statusArr, 0)
        GLES20.glDeleteShader(shader)
        if (statusArr[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "컴퓨트 프로그램 링크 실패($label): ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun drainGlErrors() {
        var guard = 0
        while (GLES20.glGetError() != GLES20.GL_NO_ERROR && guard < ERROR_DRAIN_LIMIT) {
            guard++
        }
    }

    companion object {
        const val TAG = "DragoStage"

        /** 워크그룹 한 변. 셰이더의 `local_size_x/y`와 **같아야 한다.** */
        const val LOCAL_SIZE = 16

        /** 셰이더의 `binding = 0`과 같아야 한다. */
        const val STATS_BINDING = 0

        /** uint 4개 + float 4개. std430 기준 32바이트. */
        const val STATS_BYTES = 32

        private const val ERROR_DRAIN_LIMIT = 16

        /** `-log(1e-4)`. 픽셀 하나가 낼 수 있는 최대 `-log(휘도)`다(셰이더의 클램프와 짝). */
        private const val MAX_NEG_LOG = 9.2103404

        /**
         * 누산기가 uint32에 안전하게 담기도록 남기는 상한. 2^32-1 = 4.29e9에서 20% 여유.
         * **넘치면 조용히 감기므로** 스케일을 해상도에서 계산한다(해상도 하드코딩 금지).
         */
        private const val UINT_HEADROOM = 3.4e9

        /** 스케일 상한. 이보다 크면 정밀도가 남아돌고 여유만 깎는다. */
        private const val SUM_SCALE_MAX = 1024f

        /**
         * `Σ -log(휘도)`를 uint atomic에 담기 위한 고정소수 스케일.
         *
         * 픽셀 하나의 `-log`는 최대 [MAX_NEG_LOG]이므로 최악의 합은 `N * 9.21`이다.
         * 720p(921600px)면 스케일 ≈ 400이고, 그때 평균 log의 양자화 오차는 1/400 미만 —
         * 로그평균 휘도로 환산하면 0.3% 미만이라 톤커브에 보이지 않는다.
         */
        fun computeSumScale(pixels: Int): Float {
            if (pixels <= 0) return 0f
            val limit = UINT_HEADROOM / (pixels.toDouble() * MAX_NEG_LOG)
            return minOf(SUM_SCALE_MAX, limit.toFloat())
        }

        /**
         * 통계 SSBO. **세 프로그램(analyze/build/apply)이 글자까지 같은 선언을 써야 한다** —
         * std430 레이아웃이 어긋나면 조용히 다른 오프셋을 읽는다.
         *
         * - `sumLogQ` / `maxGrayBits` : analyze가 atomic으로 채우고 build가 0으로 되돌린다.
         * - `logAvg` / `lmax` / `biasPow` : build가 쓰고 apply가 읽는다.
         */
        private val STATS_BLOCK = """
            layout(std430, binding = $STATS_BINDING) buffer DragoStats {
                uint sumLogQ;
                uint maxGrayBits;
                uint reserved0;
                uint reserved1;
                float logAvg;
                float lmax;
                float biasPow;
                float reserved2;
            } gStats;
        """.trimIndent()

        /**
         * 휘도 가중치. OpenCV `COLOR_RGB2GRAY`와 같은 값이라 상류 레퍼런스와 채널 정의가
         * 어긋나지 않는다([RenderArm.DRAGO_LUMA_WEIGHTS]와 같은 값이다).
         */
        const val LUMA_GLSL = "vec3(0.299, 0.587, 0.114)"

        /** OpenCV `log_()`가 쓰는 하한. `log(0)`을 막는 것이 아니라 **상류와 같은 값**이다. */
        const val LOG_FLOOR_GLSL = "1e-4"

        /**
         * 패스2 — 전역 통계 리덕션. `texelFetch`이므로 필터링·정규화 좌표가 끼지 않는다.
         *
         * 워크그룹 밖 픽셀(해상도가 [LOCAL_SIZE]의 배수가 아닐 때)은 **아무것도 더하지
         * 않는다.** 합은 실재 픽셀에 대해서만 쌓이고, 나눗셈은 실제 픽셀 수로 하므로
         * 경계 처리 때문에 평균이 밝은 쪽으로 치우치지 않는다.
         */
        private val ANALYZE_SHADER = """
            #version 310 es
            layout(local_size_x = $LOCAL_SIZE, local_size_y = $LOCAL_SIZE) in;
            precision highp float;
            precision highp int;
            uniform sampler2D uTexture;
            uniform float ${RenderArm.DRAGO_SRC_GAMMA_UNIFORM};
            uniform float uSumScale;
            $STATS_BLOCK
            shared uint sSumQ;
            shared uint sMaxBits;
            void main() {
                if (gl_LocalInvocationIndex == 0u) {
                    sSumQ = 0u;
                    sMaxBits = 0u;
                }
                barrier();
                ivec2 size = textureSize(uTexture, 0);
                ivec2 p = ivec2(gl_GlobalInvocationID.xy);
                if (p.x < size.x && p.y < size.y) {
                    vec3 lin = pow(texelFetch(uTexture, p, 0).rgb,
                                   vec3(${RenderArm.DRAGO_SRC_GAMMA_UNIFORM}));
                    float gray = dot(lin, $LUMA_GLSL);
                    float negLog = -log(max(gray, $LOG_FLOOR_GLSL));
                    atomicAdd(sSumQ, uint(negLog * uSumScale + 0.5));
                    // 음이 아닌 float은 IEEE 비트 패턴 순서가 값 순서와 같아서
                    // uint atomicMax로 최대값을 그대로 얻을 수 있다.
                    atomicMax(sMaxBits, floatBitsToUint(gray));
                }
                barrier();
                if (gl_LocalInvocationIndex == 0u) {
                    atomicAdd(gStats.sumLogQ, sSumQ);
                    atomicMax(gStats.maxGrayBits, sMaxBits);
                }
            }
        """.trimIndent()

        /**
         * 패스3 — 통계 → 계수. 스레드 하나다.
         *
         * OpenCV `TonemapDrago`의 통계 부분과 같은 식:
         * ```
         * logAvg  = exp(mean(log(gray)))
         * L       = gray / logAvg
         * Lmax    = max(gray) / logAvg
         * biasPow = log(bias) / log(0.5)
         * ```
         * ⚠ **다만 OpenCV는 이 통계를 앞 정규화를 거친 입력에서 낸다.** 이 이식은 정규화를
         * 넣지 않았으므로 `logAvg`·`Lmax`가 상류와 다른 값이 된다 → `RenderArm.DRAGO_DEVIATION`.
         * 마지막에 누산기를 0으로 되돌린다 — 다음 프레임의 analyze가 그 위에 더한다.
         */
        private val BUILD_SHADER = """
            #version 310 es
            layout(local_size_x = 1) in;
            precision highp float;
            precision highp int;
            uniform float uSumScale;
            uniform float uPixelCount;
            uniform float ${RenderArm.DRAGO_BIAS_UNIFORM};
            $STATS_BLOCK
            void main() {
                float sumLog = -float(gStats.sumLogQ) / max(uSumScale, 1e-6);
                float logAvg = max(exp(sumLog / max(uPixelCount, 1.0)), $LOG_FLOOR_GLSL);
                float maxGray = uintBitsToFloat(gStats.maxGrayBits);
                gStats.logAvg = logAvg;
                gStats.lmax = max(maxGray / logAvg, $LOG_FLOOR_GLSL);
                gStats.biasPow = log(${RenderArm.DRAGO_BIAS_UNIFORM}) / log(0.5);
                gStats.sumLogQ = 0u;
                gStats.maxGrayBits = 0u;
            }
        """.trimIndent()

        /**
         * 패스4 정점 — 적용 프래그먼트가 `#version 310 es`(SSBO를 읽어야 한다)라서 정점도
         * 같은 버전이어야 한다. ESSL은 한 프로그램 안에서 버전을 섞지 못한다.
         * 텍스처 좌표 변환은 패스1에서 끝났으므로 uTexMatrix가 없다(다른 2D 패스와 같다).
         */
        val VERTEX_SHADER_ES31 = """
            #version 310 es
            in vec4 aPosition;
            in vec2 aTexCoord;
            out vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        /**
         * 패스4 프래그먼트 — 톤맵 적용 + 감마. **선형 RGB 3채널 전부**에 작용한다
         * (A1·A2가 LAB의 L만 건드리는 것과 다르다).
         *
         * 톤맵 곡선 자체는 OpenCV `TonemapDrago`와 같다:
         * ```
         * map  = log(L + 1) / log(2 + 8 * pow(L / Lmax, biasPow))
         * out  = pow(lin / lum, saturation) * map
         * out  = pow(out, 1 / gamma)
         * ```
         * 🔴 **그러나 전체가 OpenCV와 같은 식은 아니다.** 세 곳이 다르다 —
         * 앞 정규화 없음 · **뒤 정규화도 없음** · `mapLuminance`의 분모가 OpenCV의
         * `gray/mean`이 아니라 **원시 `lum`**(위 2행). 뒤 둘은 서로 거의 상쇄되므로
         * **하나만 고치면 화면이 크게 틀어진다.** 전문·실측 이탈 폭·왜 지금 재현하지 않는지는
         * [RenderArm.DRAGO_DEVIATION].
         */
        val APPLY_SHADER = """
            #version 310 es
            precision highp float;
            in vec2 vTexCoord;
            out vec4 fragColor;
            uniform sampler2D uTexture;
            uniform float ${RenderArm.DRAGO_SRC_GAMMA_UNIFORM};
            uniform float ${RenderArm.DRAGO_OUT_GAMMA_UNIFORM};
            uniform float ${RenderArm.DRAGO_SATURATION_UNIFORM};
            layout(std430, binding = $STATS_BINDING) readonly buffer DragoStats {
                uint sumLogQ;
                uint maxGrayBits;
                uint reserved0;
                uint reserved1;
                float logAvg;
                float lmax;
                float biasPow;
                float reserved2;
            } gStats;
            void main() {
                vec3 lin = pow(texture(uTexture, vTexCoord).rgb,
                               vec3(${RenderArm.DRAGO_SRC_GAMMA_UNIFORM}));
                float lum = dot(lin, $LUMA_GLSL);
                float l = lum / gStats.logAvg;
                float denom = log(2.0 + 8.0 * pow(max(l / gStats.lmax, 0.0), gStats.biasPow));
                float mapped = log(l + 1.0) / max(denom, 1e-6);
                vec3 ratio = pow(lin / max(lum, $LOG_FLOOR_GLSL),
                                 vec3(${RenderArm.DRAGO_SATURATION_UNIFORM}));
                vec3 tone = pow(max(ratio * mapped, 0.0),
                                vec3(1.0 / ${RenderArm.DRAGO_OUT_GAMMA_UNIFORM}));
                fragColor = vec4(clamp(tone, 0.0, 1.0), 1.0);
            }
        """.trimIndent()
    }
}
