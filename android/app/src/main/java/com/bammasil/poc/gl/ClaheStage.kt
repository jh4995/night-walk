package com.bammasil.poc.gl

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import android.util.Log

/**
 * `clahe_gamma` arm(상류 `A1`)의 **타일 통계 부분**. CLAHE는 타일마다 히스토그램을 만들고
 * 클립·재분배·CDF로 타일마다 LUT를 만든 뒤, 타일 사이를 이중선형 보간해 적용한다.
 *
 * ### 슬롯 3개에 정확히 들어간다
 * ```
 * analyze  컴퓨트 dispatch(타일수)   타일별 256빈 히스토그램            stage_d_analyze_ms
 * build    컴퓨트 dispatch(타일수)   클립 + 재분배 + CDF → 타일별 LUT   stage_d_build_ms
 * apply    프래그먼트 1패스          타일 간 이중선형 보간 + 감마        stage_d_apply_ms
 * ```
 * `docs/FRAME_LOG_SCHEMA.md` §2가 하위 패스를 앱이 임의로 합치는 것을 금지하므로 셋을
 * 따로 잰다. **슬롯이 모자라지 않는다** — 히스토그램이 다단 리덕션이 아니기 때문이다.
 *
 * ### 왜 워크그룹 하나가 타일 하나인가
 * [DragoStage]는 화면 전체를 하나로 접어야 해서 SSBO atomic이 필요했다. CLAHE는 **타일마다
 * 독립**이므로 워크그룹 하나에 타일 하나를 통째로 맡기면 전역 atomic이 아예 없어진다
 * (공유메모리 atomic만 쓰고, SSBO에는 각 워크그룹이 **자기 타일 구간만** 평범하게 쓴다).
 * 그래서 히스토그램 버퍼를 매 프레임 0으로 되돌릴 필요도 없다 — 통째로 덮어쓴다.
 *
 * ⚠ 워크그룹당 스레드가 256개다(16×16). ES 3.1의 보장 하한은 128이라
 * [onContextCreated]가 `GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS`를 **실측 확인**하고,
 * 모자라면 값을 지어내지 않고 arm을 끈다.
 *
 * ### 통계를 CPU로 읽지 않는다
 * [DragoStage]와 같은 이유다 — `glMapBufferRange`는 GPU 동기화라 측정 대상 자체를 오염시킨다.
 * LUT 계산도 GPU에서 하고 적용 프래그먼트가 같은 SSBO를 읽는다.
 *
 * ⚠ **프레임 간 상태가 아니다.** 히스토그램·LUT는 같은 프레임 안에서 만들고 쓴다
 * ([RenderArm.TEMPORAL_STATE] 그대로 stateless다).
 *
 * **스레드 규약: 전부 GL 스레드에서만 부른다.**
 */
class ClaheStage : Stage2ComputeStage {

    override var ready = false
        private set

    override var status: String = "아직 준비하지 않았다 (arm != clahe_gamma)"
        private set

    private var analyzeProgram = 0
    private var buildProgram = 0
    private var histBuffer = 0
    private var lutBuffer = 0

    private var uAnalyzeTexture = -1
    private var uBuildClipLimit = -1

    private val scratch = IntArray(1)

    // ── 컨텍스트 수명 ─────────────────────────────────────────────────────

    override fun onContextCreated(capabilities: GlCapabilities?) {
        releaseGl()
        // ⚠ 이 arm의 불변식: **워크그룹 스레드 수 == 빈 수**(16x16 = 256).
        //   analyze는 스레드 하나가 공유 히스토그램의 빈 하나를 0으로 밀고
        //   (`sHist[gl_LocalInvocationIndex] = 0u`) 마지막에 자기 빈 하나를 SSBO에 쓴다
        //   (`gHist.bins[base + i]`). build도 "빈 하나에 스레드 하나"다. 어긋나면 남는 빈이
        //   **초기화되지 않은 쓰레기로 남는데 컴파일도 실행도 성공한다** — 히스토그램만
        //   조용히 틀린다. 아래 GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS 검사는 "256개를 쓸 수
        //   있는가"만 보고 이 항등식 자체는 보지 않으므로 여기서 따로 막는다.
        //   상수를 바꾸는 사람이 셰이더 두 개를 함께 고치도록 강제하는 것이 목적이다.
        if (LOCAL_SIZE * LOCAL_SIZE != LabGlsl.BIN_COUNT) {
            disable(
                "구현 불변식이 깨졌다 — 워크그룹 스레드 수(${LOCAL_SIZE}x$LOCAL_SIZE=" +
                    "${LOCAL_SIZE * LOCAL_SIZE})와 히스토그램 빈 수(${LabGlsl.BIN_COUNT})가 " +
                    "달라 analyze가 히스토그램 테이블 전체를 덮지 못한다(남는 빈은 초기화되지 " +
                    "않은 값). 값을 지어내지 않고 arm을 끈다 — " +
                    "ClaheStage.LOCAL_SIZE와 LabGlsl.BIN_COUNT를 함께 맞출 것"
            )
            return
        }
        val compute = capabilities?.computeShaderCapable ?: GlCapabilitiesProbe.UNKNOWN
        if (compute != GlCapabilitiesProbe.TRUE) {
            disable(
                "컴퓨트 셰이더를 쓸 수 없다(GL 능력 프로브: computeShaderCapable=$compute) — " +
                    "타일 히스토그램을 dispatch 한 번으로 접을 수 없다"
            )
            return
        }
        drainStageGlErrors()

        // 프래그먼트가 SSBO를 못 읽으면 LUT를 CPU 왕복 없이 넘길 수단이 없다.
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

        // 워크그룹 256스레드가 안 되면 "빈 하나에 스레드 하나" 구조가 성립하지 않는다.
        // ES 3.1의 보장 하한이 128이므로 추측하지 않고 실측한다.
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
            disable("컴퓨트 프로그램 컴파일/링크에 실패했다 — 위 로그(ClaheStage)에 원문이 있다")
            return
        }
        uAnalyzeTexture = GLES20.glGetUniformLocation(analyzeProgram, "uTexture")
        uBuildClipLimit =
            GLES20.glGetUniformLocation(buildProgram, RenderArm.CLAHE_CLIP_LIMIT_UNIFORM)

        val ids = IntArray(2)
        GLES20.glGenBuffers(2, ids, 0)
        histBuffer = ids[0]
        lutBuffer = ids[1]
        // 초기값을 밀어 넣지 않는다 — analyze가 자기 타일 구간을 통째로 덮어쓰고,
        // build가 그 뒤에만 읽는다. 프레임 경로에 CPU→GPU 갱신을 만들지 않기 위해서다.
        GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, histBuffer)
        GLES20.glBufferData(
            GLES31.GL_SHADER_STORAGE_BUFFER, TABLE_BYTES, null, GLES30.GL_DYNAMIC_COPY
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
            "준비 완료 — 히스토그램 = 컴퓨트 dispatch 1회(워크그룹 하나가 타일 하나, " +
                "${LOCAL_SIZE}x$LOCAL_SIZE 공유메모리 atomic, 전역 atomic 없음), " +
                "LUT = 컴퓨트 dispatch 1회(빈 하나에 스레드 하나 + 공유메모리 prefix-scan), " +
                "적용 = 프래그먼트 1패스(타일 이중선형 보간 + 감마). " +
                "타일 격자 ${RenderArm.CLAHE_TILE_GRID}x${RenderArm.CLAHE_TILE_GRID}, " +
                "빈 ${LabGlsl.BIN_COUNT}, 테이블 ${TABLE_BYTES}B x2. " +
                "GL_MAX_FRAGMENT_SHADER_STORAGE_BLOCKS=$fragmentBlocks, " +
                "GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS=$maxInvocations. " +
                "통계를 CPU로 읽지 않는다(읽으면 GPU 동기화로 측정이 오염된다)"
        Log.i(TAG, status)
    }

    /**
     * 타일 경계를 셰이더가 `textureSize`와 `gl_NumWorkGroups`에서 직접 낸다 —
     * 해상도에 의존하는 상수가 없다. 그래서 여기서 할 일이 없다.
     */
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
     * 패스2 — 타일별 히스토그램. `stage_d_analyze_ms`.
     *
     * 선행 배리어가 필요한 이유는 [DragoStage.analyze]와 같다 — **직전 프레임의 build가 같은
     * 히스토그램 버퍼를 읽었다**(WAR). 인자 `width`/`height`는 쓰지 않는다: 디스패치 크기가
     * 해상도가 아니라 **타일 격자**이고, 타일 경계는 셰이더가 `textureSize`에서 낸다.
     */
    override fun analyze(textureId: Int, width: Int, height: Int) {
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
        GLES20.glUseProgram(analyzeProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(uAnalyzeTexture, 0)
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, HIST_BINDING, histBuffer)
        GLES31.glDispatchCompute(RenderArm.CLAHE_TILE_GRID, RenderArm.CLAHE_TILE_GRID, 1)
    }

    /** 패스3 — 클립 + 재분배 + CDF → 타일별 LUT. `stage_d_build_ms`. */
    override fun build() {
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
        GLES20.glUseProgram(buildProgram)
        // 상수로 박지 않고 uniform으로 넣는다(INTERFACES.md §B-5 요청).
        GLES20.glUniform1f(uBuildClipLimit, RenderArm.CLAHE_CLIP_LIMIT)
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, HIST_BINDING, histBuffer)
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, LUT_BINDING, lutBuffer)
        GLES31.glDispatchCompute(RenderArm.CLAHE_TILE_GRID, RenderArm.CLAHE_TILE_GRID, 1)
    }

    /** 패스4 앞. build가 쓴 LUT를 프래그먼트가 볼 수 있게 한다. */
    override fun beforeApply() {
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, LUT_BINDING, lutBuffer)
    }

    // ── 내부 ─────────────────────────────────────────────────────────────

    private fun disable(reason: String) {
        ready = false
        status = reason
        Log.w(TAG, "clahe_gamma 비활성: $reason")
    }

    companion object {
        const val TAG = "ClaheStage"

        /** 워크그룹 한 변. 셰이더의 `local_size_x/y`와 **같아야 한다.** 16×16 = 256빈. */
        const val LOCAL_SIZE = 16

        /** 셰이더의 `binding` 값과 같아야 한다. */
        const val HIST_BINDING = 0
        const val LUT_BINDING = 1

        /** 타일수 × 빈수 × 4바이트. 히스토그램(uint)과 LUT(float)가 같은 크기다. */
        val TABLE_BYTES =
            RenderArm.CLAHE_TILE_GRID * RenderArm.CLAHE_TILE_GRID * LabGlsl.BIN_COUNT * 4

        /**
         * 패스2 — 타일별 히스토그램. **워크그룹 하나가 타일 하나**를 통째로 맡는다.
         *
         * 타일 경계는 `round(i * size / tiles)`로 잡는다. OpenCV CLAHE는 해상도가 격자로
         * 나눠떨어지지 않으면 `BORDER_REFLECT_101`로 패딩한 뒤 균등 분할하는데, 이 이식은
         * 패딩 없이 실재 픽셀만 균등 분할한다([RenderArm.CLAHE_DEVIATION]).
         * 720p(1280x720)는 8로 나눠떨어져 이 프레임에서는 차이가 없다.
         */
        internal fun analyzeShaderSource(histBinding: Int): String = """
            #version 310 es
            layout(local_size_x = $LOCAL_SIZE, local_size_y = $LOCAL_SIZE) in;
            precision highp float;
            precision highp int;
            uniform sampler2D uTexture;
            layout(std430, binding = $histBinding) writeonly buffer ClaheHist {
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
                ivec2 tiles = ivec2(gl_NumWorkGroups.xy);
                ivec2 t = ivec2(gl_WorkGroupID.xy);
                int x0 = (size.x * t.x) / tiles.x;
                int x1 = (size.x * (t.x + 1)) / tiles.x;
                int y0 = (size.y * t.y) / tiles.y;
                int y1 = (size.y * (t.y + 1)) / tiles.y;
                for (int y = y0 + int(gl_LocalInvocationID.y); y < y1; y += $LOCAL_SIZE) {
                    for (int x = x0 + int(gl_LocalInvocationID.x); x < x1; x += $LOCAL_SIZE) {
                        float l = ${LabGlsl.SRGB_TO_L}(texelFetch(uTexture, ivec2(x, y), 0).rgb);
                        uint bin = uint(clamp(l * ${LabGlsl.L_TO_BIN} + 0.5, 0.0, 255.0));
                        atomicAdd(sHist[bin], 1u);
                    }
                }
                memoryBarrierShared();
                barrier();
                uint base = (uint(t.y) * uint(tiles.x) + uint(t.x)) * ${LabGlsl.BIN_COUNT}u;
                gHist.bins[base + i] = sHist[i];
            }
        """.trimIndent()

        /** 단품 `clahe_gamma` arm의 패스2. **binding은 지금까지 쓰던 값 그대로**다. */
        private val ANALYZE_SHADER = analyzeShaderSource(HIST_BINDING)

        /**
         * 패스3 — 클립 + 재분배 + CDF. **빈 하나에 스레드 하나**(256스레드 = 16×16).
         *
         * OpenCV `CLAHE_CalcLut_Body`와 같은 식이다:
         * ```
         * clip       = max(1, clipLimit * tileArea / histSize)          // 정수 절단
         * excess     = Σ max(0, h[i] - clip);  h[i] = min(h[i], clip)
         * batch      = excess / histSize;  residual = excess - batch*histSize
         * h[i]      += batch
         * step       = max(histSize / residual, 1)
         * h[i]      += 1   (i가 step의 배수이고 i/step < residual일 때)
         * lut[i]     = CDF(i) * 255 / tileArea
         * ```
         * `residual` 분배는 OpenCV가 `for (i=0; i<histSize && residual>0; i+=step, residual--)`
         * 로 도는데, 그건 "step의 배수 중 앞에서 `residual`개"와 같으므로 그대로 병렬화된다.
         *
         * ⚠ 타일 픽셀 수를 uniform으로 넘기지 않고 **히스토그램 합에서 낸다.** 경계 반올림
         * 때문에 타일마다 픽셀 수가 1씩 다를 수 있고, 그때 넘겨받은 값을 쓰면 CDF 정규화가
         * 실제로 센 픽셀 수와 어긋난다.
         *
         * ⚠ **`uClipLimit <= 0`의 의미가 OpenCV와 다르다.** OpenCV는 `clipLimit <= 0`을
         * "클립하지 않음"으로 처리하는데(`CLAHE_CalcLut_Body`가 클립 분기를 통째로 건너뛴다)
         * 이 셰이더는 `clip = uint(max(clipI, 1))`이라 **빈당 1픽셀이라는 극단 클립**이 된다
         * — 정반대다. 지금 쓰는 값이 2.0이라 이번 측정에서는 걸리지 않지만, `uClipLimit`은
         * uniform이라 값을 바꾸는 것만으로 그 경로에 들어갈 수 있다. 상수로 막지 않고 기록만
         * 남기는 이유: 이번 라운드에서 검증된 셰이더 산식을 건드리지 않기 위해서다
         * ([RenderArm.LAB_DEVIATION]에도 같은 문장이 있다).
         */
        internal fun buildShaderSource(histBinding: Int, lutBinding: Int): String = """
            #version 310 es
            layout(local_size_x = $LOCAL_SIZE, local_size_y = $LOCAL_SIZE) in;
            precision highp float;
            precision highp int;
            uniform float ${RenderArm.CLAHE_CLIP_LIMIT_UNIFORM};
            layout(std430, binding = $histBinding) readonly buffer ClaheHist {
                uint bins[];
            } gHist;
            layout(std430, binding = $lutBinding) writeonly buffer ClaheLut {
                float entries[];
            } gLut;
            shared uint sTotal;
            shared uint sExcess;
            shared uint sScan[${LabGlsl.BIN_COUNT}];
            void main() {
                uint i = gl_LocalInvocationIndex;
                uint tile = gl_WorkGroupID.y * gl_NumWorkGroups.x + gl_WorkGroupID.x;
                uint base = tile * ${LabGlsl.BIN_COUNT}u;
                uint h = gHist.bins[base + i];
                if (i == 0u) {
                    sTotal = 0u;
                    sExcess = 0u;
                }
                memoryBarrierShared();
                barrier();
                atomicAdd(sTotal, h);
                memoryBarrierShared();
                barrier();
                uint total = max(sTotal, 1u);
                int clipI = int(${RenderArm.CLAHE_CLIP_LIMIT_UNIFORM} * float(total)
                                / float(${LabGlsl.BIN_COUNT}));
                uint clip = uint(max(clipI, 1));
                if (h > clip) {
                    atomicAdd(sExcess, h - clip);
                    h = clip;
                }
                memoryBarrierShared();
                barrier();
                uint batch = sExcess / ${LabGlsl.BIN_COUNT}u;
                uint residual = sExcess - batch * ${LabGlsl.BIN_COUNT}u;
                h += batch;
                if (residual > 0u) {
                    uint stride = max(${LabGlsl.BIN_COUNT}u / residual, 1u);
                    if (i % stride == 0u && (i / stride) < residual) {
                        h += 1u;
                    }
                }
                sScan[i] = h;
                memoryBarrierShared();
                barrier();
                for (uint off = 1u; off < ${LabGlsl.BIN_COUNT}u; off <<= 1u) {
                    uint v = (i >= off) ? sScan[i - off] : 0u;
                    memoryBarrierShared();
                    barrier();
                    sScan[i] += v;
                    memoryBarrierShared();
                    barrier();
                }
                gLut.entries[base + i] =
                    clamp(float(sScan[i]) * 255.0 / float(total), 0.0, 255.0);
            }
        """.trimIndent()

        /** 단품 `clahe_gamma` arm의 패스3. **binding은 지금까지 쓰던 값 그대로**다. */
        private val BUILD_SHADER = buildShaderSource(HIST_BINDING, LUT_BINDING)

        /**
         * 패스4 프래그먼트 — 타일 간 이중선형 보간 + 감마. **LAB의 `L`만** 바꾸고 `a`,`b`는
         * 그대로 둔다([LabGlsl]).
         *
         * 감마는 **보간 뒤**에 온다. 상류 A1이 `CLAHE(L) → 감마`이므로 타일 LUT 각각에 감마를
         * 미리 구워 넣으면(pow는 비선형이라) 보간 결과가 달라진다.
         *
         * 타일 중심은 `((t + 0.5) / tiles)`이고 가장자리 반 타일은 클램프한다.
         *
         * ⚠ **보간 좌표의 원점이 OpenCV와 반 픽셀 다르다.** 여기서는
         * `p = vTexCoord * uTiles - 0.5`인데 `vTexCoord`가 **픽셀 중심**을 가리키므로
         * 실질적으로 `(x + 0.5)/size * tiles - 0.5`이고, OpenCV `CLAHE_Interpolation_Body`는
         * `x / tileSize - 0.5`로 **픽셀 좌상단**을 쓴다. 독립 검증 실측 이탈:
         * **max 1.86 LSB, 픽셀의 1.03%가 >1 LSB**(원점을 OpenCV와 맞추면 max 0.993 LSB).
         * **고치지 않는다** — 픽셀 중심이 표본 위치의 표준 정의라 이쪽이 오히려 옳고,
         * 검증이 끝난 셰이더 산식을 이번 라운드에서 건드리지 않는다.
         * 대신 [RenderArm.LAB_DEVIATION]에 이탈 항목으로 싣는다(§B-6 골든 대조 때 이 항목이
         * 없으면 원인 추적이 어렵다).
         */
        internal fun applyShaderSource(lutBinding: Int): String = """
            #version 310 es
            precision highp float;
            precision highp int;
            in vec2 vTexCoord;
            out vec4 fragColor;
            uniform sampler2D uTexture;
            uniform vec2 ${RenderArm.CLAHE_TILES_UNIFORM};
            uniform float ${RenderArm.CLAHE_GAMMA_UNIFORM};
            layout(std430, binding = $lutBinding) readonly buffer ClaheLut {
                float entries[];
            } gLut;
            ${LabGlsl.FUNCTIONS}
            void main() {
                vec3 f = ${LabGlsl.SRGB_TO_LAB_F}(texture(uTexture, vTexCoord).rgb);
                float l = 116.0 * f.y - 16.0;
                int idx = int(clamp(l * ${LabGlsl.L_TO_BIN} + 0.5, 0.0, 255.0));

                vec2 p = vTexCoord * ${RenderArm.CLAHE_TILES_UNIFORM} - 0.5;
                vec2 b = floor(p);
                vec2 w = p - b;
                ivec2 maxT = ivec2(${RenderArm.CLAHE_TILES_UNIFORM}) - 1;
                ivec2 t0 = clamp(ivec2(b), ivec2(0), maxT);
                ivec2 t1 = clamp(ivec2(b) + 1, ivec2(0), maxT);
                int tx = int(${RenderArm.CLAHE_TILES_UNIFORM}.x);
                int n = ${LabGlsl.BIN_COUNT};
                float v00 = gLut.entries[(t0.y * tx + t0.x) * n + idx];
                float v10 = gLut.entries[(t0.y * tx + t1.x) * n + idx];
                float v01 = gLut.entries[(t1.y * tx + t0.x) * n + idx];
                float v11 = gLut.entries[(t1.y * tx + t1.x) * n + idx];
                float lut8 = mix(mix(v00, v10, w.x), mix(v01, v11, w.x), w.y);

                float newBin = pow(clamp(lut8 / 255.0, 0.0, 1.0),
                                   ${RenderArm.CLAHE_GAMMA_UNIFORM}) * 255.0;
                fragColor = vec4(
                    ${LabGlsl.LAB_F_TO_SRGB}(f, newBin / ${LabGlsl.L_TO_BIN}), 1.0
                );
            }
        """.trimIndent()

        /**
         * 단품 `clahe_gamma` arm의 패스4. **binding은 지금까지 쓰던 값 그대로**다.
         *
         * ⚠ 위 세 소스가 **함수**인 이유: 조합 arm(`drago_clahe_chain`)이 drago의 SSBO와
         * 번호가 겹치지 않게 hist=1 / lut=2로 다시 컴파일해야 하기 때문이다. 셰이더 텍스트를
         * 복사하지 않고 **binding만 인자로 갈랐으므로** 산식은 한 곳에만 있고, 단품 arm은
         * 지금 값으로 부르므로 **생성 문자열이 바이트 단위로 그대로**다.
         */
        val APPLY_SHADER = applyShaderSource(LUT_BINDING)
    }
}
