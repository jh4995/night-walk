package com.bammasil.poc.gl

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * `agcwd` arm(상류 `A2`)의 **전역 통계 부분**. AGCWD(Huang 2013, *Adaptive Gamma Correction
 * with Weighting Distribution*)는 화면 전체 히스토그램 하나로 **빈마다 다른 감마**를 만든다.
 *
 * ### 슬롯 3개에 정확히 들어간다
 * ```
 * analyze  컴퓨트 dispatch(화면)   전역 256빈 히스토그램          stage_d_analyze_ms
 * build    컴퓨트 dispatch(1)      가중 분포 → 1D LUT(256엔트리)  stage_d_build_ms
 * apply    프래그먼트 1패스        LUT 적용                       stage_d_apply_ms
 * ```
 *
 * ### 식 (상류 `AGCWD(alpha=0.5)`)
 * ```
 * pdf(l)   = n(l) / N
 * pdf_w(l) = pdf_max * ((pdf(l) - pdf_min) / (pdf_max - pdf_min)) ^ alpha
 * cdf_w(l) = Σ_{k<=l} pdf_w(k) / Σ_k pdf_w(k)
 * T(l)     = 255 * (l / 255) ^ (1 - cdf_w(l))
 * ```
 * `alpha`는 uniform이다(`INTERFACES.md` §B-5 요청).
 *
 * ### [DragoStage]와 같은 점 / 다른 점
 * 같은 점 — 전역 리덕션이라 **워크그룹 공유메모리 + SSBO atomic으로 dispatch 한 번**이고,
 * 통계를 CPU로 읽지 않으며(읽으면 GPU 동기화로 측정이 오염된다), 누산기 리셋도 build가
 * 마지막에 GPU에서 한다.
 * 다른 점 — Drago는 **선형 RGB 3채널 전부**에 작용하지만 이 arm은 **LAB의 `L`만** 바꾸고
 * `a`,`b`는 그대로 둔다([LabGlsl], [RenderArm.LAB_DEVIATION]).
 *
 * ⚠ **프레임 간 상태가 아니다.** 히스토그램은 같은 프레임 안에서 만들고 쓰고 지운다
 * ([RenderArm.TEMPORAL_STATE] 그대로 stateless다).
 *
 * **스레드 규약: 전부 GL 스레드에서만 부른다.**
 */
class AgcwdStage : Stage2ComputeStage {

    override var ready = false
        private set

    override var status: String = "아직 준비하지 않았다 (arm != agcwd)"
        private set

    private var analyzeProgram = 0
    private var buildProgram = 0
    private var histBuffer = 0
    private var lutBuffer = 0

    private var uAnalyzeTexture = -1
    private var uBuildAlpha = -1

    /** 1KB 0으로 초기화용. 한 번만 만든다(프레임 경로에서는 쓰지 않는다). */
    private var zeroInit: ByteBuffer? = null

    private val scratch = IntArray(1)

    // ── 컨텍스트 수명 ─────────────────────────────────────────────────────

    override fun onContextCreated(capabilities: GlCapabilities?) {
        releaseGl()
        // ⚠ [ClaheStage]와 **같은 불변식**이다: 워크그룹 스레드 수 == 빈 수(16x16 = 256).
        //   analyze의 공유 히스토그램 초기화(`sHist[gl_LocalInvocationIndex] = 0u`)와 전역
        //   반영, build의 "빈 하나에 스레드 하나"가 전부 이것에 기댄다. 어긋나면 남는 빈이
        //   **초기화되지 않은 쓰레기로 남는데 컴파일도 실행도 성공한다.**
        if (LOCAL_SIZE * LOCAL_SIZE != LabGlsl.BIN_COUNT) {
            disable(
                "구현 불변식이 깨졌다 — 워크그룹 스레드 수(${LOCAL_SIZE}x$LOCAL_SIZE=" +
                    "${LOCAL_SIZE * LOCAL_SIZE})와 히스토그램 빈 수(${LabGlsl.BIN_COUNT})가 " +
                    "달라 공유 히스토그램 전체를 덮지 못한다(남는 빈은 초기화되지 않은 값). " +
                    "값을 지어내지 않고 arm을 끈다 — " +
                    "AgcwdStage.LOCAL_SIZE와 LabGlsl.BIN_COUNT를 함께 맞출 것"
            )
            return
        }
        val compute = capabilities?.computeShaderCapable ?: GlCapabilitiesProbe.UNKNOWN
        if (compute != GlCapabilitiesProbe.TRUE) {
            disable(
                "컴퓨트 셰이더를 쓸 수 없다(GL 능력 프로브: computeShaderCapable=$compute) — " +
                    "전역 히스토그램을 단일 dispatch로 접을 수 없다"
            )
            return
        }
        drainStageGlErrors()

        scratch[0] = 0
        GLES20.glGetIntegerv(GLES31.GL_MAX_FRAGMENT_SHADER_STORAGE_BLOCKS, scratch, 0)
        var err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR || scratch[0] < 1) {
            drainStageGlErrors()
            disable(
                "프래그먼트 셰이더가 SSBO를 읽을 수 없다 " +
                    "(GL_MAX_FRAGMENT_SHADER_STORAGE_BLOCKS=${scratch[0]}, " +
                    "glGetError=0x${Integer.toHexString(err)})"
            )
            return
        }
        val fragmentBlocks = scratch[0]

        // build가 "빈 하나에 스레드 하나"라 워크그룹 256스레드가 필요하다.
        // ES 3.1의 보장 하한은 128이므로 추측하지 않고 실측한다.
        scratch[0] = 0
        GLES20.glGetIntegerv(GLES31.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS, scratch, 0)
        err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR || scratch[0] < LabGlsl.BIN_COUNT) {
            drainStageGlErrors()
            disable(
                "워크그룹당 스레드가 모자란다 " +
                    "(GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS=${scratch[0]}, " +
                    "필요=${LabGlsl.BIN_COUNT}, glGetError=0x${Integer.toHexString(err)})"
            )
            return
        }
        val maxInvocations = scratch[0]

        analyzeProgram = compileComputeProgram(TAG, "analyze", ANALYZE_SHADER)
        buildProgram = compileComputeProgram(TAG, "build", BUILD_SHADER)
        if (analyzeProgram == 0 || buildProgram == 0) {
            releaseGl()
            disable("컴퓨트 프로그램 컴파일/링크에 실패했다 — 위 로그(AgcwdStage)에 원문이 있다")
            return
        }
        uAnalyzeTexture = GLES20.glGetUniformLocation(analyzeProgram, "uTexture")
        uBuildAlpha = GLES20.glGetUniformLocation(buildProgram, RenderArm.AGCWD_ALPHA_UNIFORM)

        val ids = IntArray(2)
        GLES20.glGenBuffers(2, ids, 0)
        histBuffer = ids[0]
        lutBuffer = ids[1]
        // 히스토그램은 **atomicAdd로 쌓으므로** 0에서 출발해야 한다. 이후 프레임은 build가
        // 마지막에 0으로 되돌린다(프레임 경로에 CPU→GPU 갱신을 만들지 않기 위해서다).
        val zeros = zeroInit ?: ByteBuffer
            .allocateDirect(TABLE_BYTES)
            .order(ByteOrder.nativeOrder())
            .also { zeroInit = it }
        zeros.position(0)
        GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, histBuffer)
        GLES20.glBufferData(
            GLES31.GL_SHADER_STORAGE_BUFFER, TABLE_BYTES, zeros, GLES30.GL_DYNAMIC_COPY
        )
        GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, lutBuffer)
        GLES20.glBufferData(
            GLES31.GL_SHADER_STORAGE_BUFFER, TABLE_BYTES, null, GLES30.GL_DYNAMIC_COPY
        )
        GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)
        val bufErr = GLES20.glGetError()
        if (bufErr != GLES20.GL_NO_ERROR) {
            releaseGl()
            disable("히스토그램/LUT SSBO 생성에 실패했다 (glGetError=0x${Integer.toHexString(bufErr)})")
            return
        }

        ready = true
        status =
            "준비 완료 — 히스토그램 = 컴퓨트 dispatch 1회(워크그룹 " +
                "${LOCAL_SIZE}x$LOCAL_SIZE 공유메모리 atomic + SSBO atomic), " +
                "LUT = 컴퓨트 dispatch 1회(워크그룹 1개, 빈 하나에 스레드 하나 + " +
                "공유메모리 prefix-scan), 적용 = 프래그먼트 1패스. " +
                "빈 ${LabGlsl.BIN_COUNT}, 테이블 ${TABLE_BYTES}B x2. " +
                "GL_MAX_FRAGMENT_SHADER_STORAGE_BLOCKS=$fragmentBlocks, " +
                "GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS=$maxInvocations. " +
                "통계를 CPU로 읽지 않는다(읽으면 GPU 동기화로 측정이 오염된다)"
        Log.i(TAG, status)
    }

    /** 디스패치 크기를 [analyze]가 인자로 받은 해상도에서 낸다 — 여기서 잡을 상수가 없다. */
    override fun onProcessSizeChanged(width: Int, height: Int) = Unit

    override fun releaseGl() {
        if (analyzeProgram != 0) GLES20.glDeleteProgram(analyzeProgram)
        if (buildProgram != 0) GLES20.glDeleteProgram(buildProgram)
        if (histBuffer != 0) GLES20.glDeleteBuffers(1, intArrayOf(histBuffer), 0)
        if (lutBuffer != 0) GLES20.glDeleteBuffers(1, intArrayOf(lutBuffer), 0)
        analyzeProgram = 0
        buildProgram = 0
        histBuffer = 0
        lutBuffer = 0
        ready = false
        status = "GL 컨텍스트가 사라져 자원을 반납했다"
    }

    // ── 프레임 경로 (렌더 스레드 hot path) ────────────────────────────────

    /**
     * 패스2 — 전역 히스토그램. `stage_d_analyze_ms`.
     * 선행 배리어는 [DragoStage.analyze]와 같은 이유다(직전 프레임 build의 읽기와 WAR).
     */
    override fun analyze(textureId: Int, width: Int, height: Int) {
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
        GLES20.glUseProgram(analyzeProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(uAnalyzeTexture, 0)
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, HIST_BINDING, histBuffer)
        GLES31.glDispatchCompute(groups(width), groups(height), 1)
    }

    /** 패스3 — 가중 분포 → 1D LUT. `stage_d_build_ms`. 워크그룹 하나짜리 dispatch다. */
    override fun build() {
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
        GLES20.glUseProgram(buildProgram)
        // 상수로 박지 않고 uniform으로 넣는다(INTERFACES.md §B-5 요청).
        GLES20.glUniform1f(uBuildAlpha, RenderArm.AGCWD_ALPHA)
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, HIST_BINDING, histBuffer)
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, LUT_BINDING, lutBuffer)
        GLES31.glDispatchCompute(1, 1, 1)
    }

    /** 패스4 앞. build가 쓴 LUT를 프래그먼트가 볼 수 있게 한다. */
    override fun beforeApply() {
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, LUT_BINDING, lutBuffer)
    }

    // ── 내부 ─────────────────────────────────────────────────────────────

    private fun groups(size: Int): Int = (size + LOCAL_SIZE - 1) / LOCAL_SIZE

    private fun disable(reason: String) {
        ready = false
        status = reason
        Log.w(TAG, "agcwd 비활성: $reason")
    }

    companion object {
        const val TAG = "AgcwdStage"

        /** 워크그룹 한 변. 셰이더의 `local_size_x/y`와 **같아야 한다.** 16×16 = 256빈. */
        const val LOCAL_SIZE = 16

        /** 셰이더의 `binding` 값과 같아야 한다. */
        const val HIST_BINDING = 0
        const val LUT_BINDING = 1

        /** 빈수 × 4바이트. 히스토그램(uint)과 LUT(float)가 같은 크기다. */
        val TABLE_BYTES = LabGlsl.BIN_COUNT * 4

        /**
         * 패스2 — 전역 히스토그램. 워크그룹마다 공유메모리에 먼저 모으고 **비어 있지 않은
         * 빈만** 전역 atomic으로 올린다(어두운 장면에서 전역 atomic 경합을 줄인다).
         *
         * 워크그룹 밖 픽셀(해상도가 [LOCAL_SIZE]의 배수가 아닐 때)은 아무것도 더하지 않는다.
         */
        private val ANALYZE_SHADER = """
            #version 310 es
            layout(local_size_x = $LOCAL_SIZE, local_size_y = $LOCAL_SIZE) in;
            precision highp float;
            precision highp int;
            uniform sampler2D uTexture;
            layout(std430, binding = $HIST_BINDING) buffer AgcwdHist {
                uint bins[];
            } gHist;
            shared uint sHist[${LabGlsl.BIN_COUNT}];
            ${LabGlsl.FUNCTIONS}
            void main() {
                uint i = gl_LocalInvocationIndex;
                sHist[i] = 0u;
                memoryBarrierShared();
                barrier();
                ivec2 size = textureSize(uTexture, 0);
                ivec2 p = ivec2(gl_GlobalInvocationID.xy);
                if (p.x < size.x && p.y < size.y) {
                    float l = ${LabGlsl.SRGB_TO_L}(texelFetch(uTexture, p, 0).rgb);
                    uint bin = uint(clamp(l * ${LabGlsl.L_TO_BIN} + 0.5, 0.0, 255.0));
                    atomicAdd(sHist[bin], 1u);
                }
                memoryBarrierShared();
                barrier();
                if (sHist[i] != 0u) {
                    atomicAdd(gHist.bins[i], sHist[i]);
                }
            }
        """.trimIndent()

        /**
         * 패스3 — 가중 분포 → LUT. **빈 하나에 스레드 하나**(256스레드 = 16×16), 워크그룹 1개.
         *
         * `pdf_max`·`pdf_min`을 따로 리덕션하지 않는다 — 모든 빈이 같은 `N`으로 나뉘므로
         * **`pdf`의 최대/최소는 도수의 최대/최소**와 같은 빈에서 나온다. uint atomicMax/Min
         * 하나로 끝난다.
         *
         * `Σ pdf_w`도 따로 구하지 않는다 — prefix-scan의 마지막 원소가 그 합이다.
         *
         * 마지막에 누산기를 0으로 되돌린다. 각 스레드가 **자기 빈만** 쓰므로 경합이 없다.
         */
        private val BUILD_SHADER = """
            #version 310 es
            layout(local_size_x = $LOCAL_SIZE, local_size_y = $LOCAL_SIZE) in;
            precision highp float;
            precision highp int;
            uniform float ${RenderArm.AGCWD_ALPHA_UNIFORM};
            layout(std430, binding = $HIST_BINDING) buffer AgcwdHist {
                uint bins[];
            } gHist;
            layout(std430, binding = $LUT_BINDING) writeonly buffer AgcwdLut {
                float entries[];
            } gLut;
            shared uint sTotal;
            shared uint sMax;
            shared uint sMin;
            shared float sScan[${LabGlsl.BIN_COUNT}];
            void main() {
                uint i = gl_LocalInvocationIndex;
                uint h = gHist.bins[i];
                if (i == 0u) {
                    sTotal = 0u;
                    sMax = 0u;
                    sMin = 0xFFFFFFFFu;
                }
                memoryBarrierShared();
                barrier();
                atomicAdd(sTotal, h);
                atomicMax(sMax, h);
                atomicMin(sMin, h);
                memoryBarrierShared();
                barrier();
                float total = max(float(sTotal), 1.0);
                float pdf = float(h) / total;
                float pdfMax = float(sMax) / total;
                float pdfMin = float(sMin) / total;
                float denom = max(pdfMax - pdfMin, 1e-9);
                sScan[i] = pdfMax * pow(clamp((pdf - pdfMin) / denom, 0.0, 1.0),
                                        ${RenderArm.AGCWD_ALPHA_UNIFORM});
                memoryBarrierShared();
                barrier();
                for (uint off = 1u; off < ${LabGlsl.BIN_COUNT}u; off <<= 1u) {
                    float v = (i >= off) ? sScan[i - off] : 0.0;
                    memoryBarrierShared();
                    barrier();
                    sScan[i] += v;
                    memoryBarrierShared();
                    barrier();
                }
                float sumW = max(sScan[${LabGlsl.BIN_COUNT}u - 1u], 1e-9);
                float cdfW = clamp(sScan[i] / sumW, 0.0, 1.0);
                float ln = float(i) / 255.0;
                // i==0 은 pow(0, e)다. GLSL은 x==0에서 pow를 정의하지 않으므로(정의역이
                // x>0) 값을 드라이버에 맡기지 않고 0으로 못박는다.
                // ⚠ **상류와 같은 값이라고 단언하지 않는다.** 지수가 정확히 0이면
                //   (cdf_w(0)==1인 경우) numpy는 0.0**0.0 = 1.0 → 255를 낸다. 그 입력에서
                //   이 이식은 0, 상류는 255로 **한 빈이 어긋난다**(빈 0 하나뿐이다).
                //   0에 가까운 지수에서도 numpy 쪽은 1에 가까운 값을 내므로 방향은 같다.
                gLut.entries[i] = (i == 0u) ? 0.0 : 255.0 * pow(ln, max(1.0 - cdfW, 0.0));
                gHist.bins[i] = 0u;
            }
        """.trimIndent()

        /**
         * 패스4 프래그먼트 — LUT 적용. **LAB의 `L`만** 바꾸고 `a`,`b`는 그대로 둔다([LabGlsl]).
         *
         * LUT를 최근접 빈으로 읽는다(빈 사이 보간을 하지 않는다) — 상류가 8비트 `L`에
         * 256엔트리 LUT를 그대로 적용하기 때문이다. 보간을 넣으면 더 매끄럽지만 상류와
         * 다른 결과가 된다([RenderArm.LAB_DEVIATION]).
         */
        val APPLY_SHADER = """
            #version 310 es
            precision highp float;
            precision highp int;
            in vec2 vTexCoord;
            out vec4 fragColor;
            uniform sampler2D uTexture;
            layout(std430, binding = $LUT_BINDING) readonly buffer AgcwdLut {
                float entries[];
            } gLut;
            ${LabGlsl.FUNCTIONS}
            void main() {
                vec3 f = ${LabGlsl.SRGB_TO_LAB_F}(texture(uTexture, vTexCoord).rgb);
                float l = 116.0 * f.y - 16.0;
                int idx = int(clamp(l * ${LabGlsl.L_TO_BIN} + 0.5, 0.0, 255.0));
                float newBin = gLut.entries[idx];
                fragColor = vec4(
                    ${LabGlsl.LAB_F_TO_SRGB}(f, newBin / ${LabGlsl.L_TO_BIN}), 1.0
                );
            }
        """.trimIndent()
    }
}
