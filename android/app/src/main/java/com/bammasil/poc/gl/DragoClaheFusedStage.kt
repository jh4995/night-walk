package com.bammasil.poc.gl

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * `drago_clahe_fused` arm — [DragoClaheChainStage]에서 **중간 표현을 없앤** 것.
 *
 * ```
 * 패스2 drago analyze   전역 통계(FBO_A)                       stage_d_analyze_ms
 * 패스3 drago build     통계 → 톤커브 계수                     stage_d_build_ms
 * 패스4 fused analyze   톤맵을 인라인한 채 타일 히스토그램      stage_d_analyze2_ms
 * 패스5 clahe build     클립+재분배+CDF → 타일 LUT             stage_d_build2_ms
 * 패스6 fused apply     톤맵 + LUT 보간 + 감마 (FBO_A→FBO_B)   stage_d_apply_ms
 * ```
 * 적용 패스가 하나뿐이라 **`stage_d_apply2_ms`는 쓰지 않는다.** 전체 7패스다.
 *
 * ### 🔴 이것은 이식 최적화가 아니라 **알고리즘 변경**이다
 * 체인은 Drago 출력을 RGBA8로 떨어뜨리고 CLAHE가 그것을 다시 읽는다 — cv2 파이프라인과 같은
 * 구조다. 융합은 그 중간을 없애므로 **상류와 다른 곡선이 된다.** 무엇이 달라지는지는
 * [RenderArm.FUSED_DEVIATION]에 세 항목으로 적었고 `session.json`에 그대로 나간다.
 * **채택 여부는 팀장/팀 판단이다**(`KICKOFF_ROLES.md`가 알고리즘을 팀장 영역으로 둔다).
 *
 * ### 교환 관계
 * 없앤 것: 패스 하나 · FBO 왕복 하나 · `pow` 인코드/디코드 왕복.
 * 대신 치른 것: **Drago 톤맵을 픽셀당 2회 평가**한다(fused analyze + fused apply).
 * 어느 쪽이 이기는지는 **측정 대상**이다.
 *
 * ### 자원
 * 프로그램·SSBO를 [DragoClaheChainStage]와도 **따로** 소유한다. 공유하면 체인 arm의 자원
 * 수명이 이 arm의 동작에 따라 바뀌고, 그러면 체인 실측의 조건이 흔들린다.
 *
 * ⚠ **프레임 간 상태가 아니다** ([RenderArm.TEMPORAL_STATE] 그대로 stateless다).
 *
 * **스레드 규약: 전부 GL 스레드에서만 부른다.**
 */
class DragoClaheFusedStage {

    var ready = false
        private set

    var status: String = "아직 준비하지 않았다 (arm != drago_clahe_fused)"
        private set

    private var dragoAnalyzeProgram = 0
    private var dragoBuildProgram = 0
    private var fusedAnalyzeProgram = 0
    private var claheBuildProgram = 0

    private var statsBuffer = 0
    private var histBuffer = 0
    private var lutBuffer = 0

    private var uDragoAnalyzeTexture = -1
    private var uDragoAnalyzeSrcGamma = -1
    private var uDragoAnalyzeSumScale = -1
    private var uDragoBuildSumScale = -1
    private var uDragoBuildPixelCount = -1
    private var uDragoBuildBias = -1
    private var uFusedAnalyzeTexture = -1
    private var uFusedAnalyzeSrcGamma = -1
    private var uFusedAnalyzeSaturation = -1
    private var uClaheBuildClipLimit = -1

    private var sumScale = 0f
    private var pixelCount = 0

    private var zeroInit: ByteBuffer? = null

    private val scratch = IntArray(1)

    // ── 컨텍스트 수명 ─────────────────────────────────────────────────────

    fun onContextCreated(capabilities: GlCapabilities?) {
        releaseGl()
        if (ClaheStage.LOCAL_SIZE * ClaheStage.LOCAL_SIZE != LabGlsl.BIN_COUNT) {
            disable(
                "구현 불변식이 깨졌다 — CLAHE 워크그룹 스레드 수(${ClaheStage.LOCAL_SIZE}x" +
                    "${ClaheStage.LOCAL_SIZE}=${ClaheStage.LOCAL_SIZE * ClaheStage.LOCAL_SIZE})와 " +
                    "히스토그램 빈 수(${LabGlsl.BIN_COUNT})가 달라 analyze가 히스토그램 " +
                    "테이블 전체를 덮지 못한다. 값을 지어내지 않고 arm을 끈다"
            )
            return
        }
        val compute = capabilities?.computeShaderCapable ?: GlCapabilitiesProbe.UNKNOWN
        if (compute != GlCapabilitiesProbe.TRUE) {
            disable(
                "컴퓨트 셰이더를 쓸 수 없다(GL 능력 프로브: computeShaderCapable=$compute) — " +
                    "융합의 통계 두 벌을 dispatch로 접을 수 없다"
            )
            return
        }
        drainStageGlErrors()

        // ⚠ 체인과 **여기가 다르다.** 융합의 적용 프래그먼트는 **하나가 두 블록을 동시에**
        //   선언한다(DragoStats + ClaheLut) — 톤맵과 LUT를 한 패스에서 쓰기 때문이다.
        //   그래서 이 arm의 필요값은 진짜로 2다(체인은 프래그먼트마다 1개씩이라 1이었다).
        //   ES 3.1의 보장 하한이 0이므로 추측하지 않고 실측한다.
        scratch[0] = 0
        GLES20.glGetIntegerv(GLES31.GL_MAX_FRAGMENT_SHADER_STORAGE_BLOCKS, scratch, 0)
        var err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR || scratch[0] < REQUIRED_FRAGMENT_SSBO_BLOCKS) {
            drainStageGlErrors()
            disable(
                "프래그먼트 SSBO 블록이 모자란다 " +
                    "(GL_MAX_FRAGMENT_SHADER_STORAGE_BLOCKS=${scratch[0]}, " +
                    "필요=$REQUIRED_FRAGMENT_SSBO_BLOCKS, " +
                    "glGetError=0x${Integer.toHexString(err)}). 융합의 적용 프래그먼트는 " +
                    "**하나가** DragoStats와 ClaheLut **두 블록을 선언한다** — 체인처럼 " +
                    "패스를 나누면 1이면 되지만 융합은 그 나눔을 없앤 것이다"
            )
            return
        }
        val fragmentBlocks = scratch[0]

        // 융합 analyze 컴퓨트도 두 블록(DragoStats 읽기 + ClaheHist 쓰기)을 선언한다.
        // ES 3.1 보장 하한이 4라 실제로는 걸리지 않지만 추측하지 않고 잰다.
        scratch[0] = 0
        GLES20.glGetIntegerv(GLES31.GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS, scratch, 0)
        err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR || scratch[0] < REQUIRED_COMPUTE_SSBO_BLOCKS) {
            drainStageGlErrors()
            disable(
                "컴퓨트 SSBO 블록이 모자란다 " +
                    "(GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS=${scratch[0]}, " +
                    "필요=$REQUIRED_COMPUTE_SSBO_BLOCKS, " +
                    "glGetError=0x${Integer.toHexString(err)}). 융합 analyze는 통계를 읽으면서 " +
                    "히스토그램을 쓴다"
            )
            return
        }
        val computeBlocks = scratch[0]

        // 바인딩 **인덱스**의 상한. 0·1·2를 쓴다.
        scratch[0] = 0
        GLES20.glGetIntegerv(GLES31.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS, scratch, 0)
        err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR || scratch[0] < REQUIRED_SSBO_BINDINGS) {
            drainStageGlErrors()
            disable(
                "SSBO 바인딩 포인트가 모자란다 " +
                    "(GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS=${scratch[0]}, " +
                    "필요=$REQUIRED_SSBO_BINDINGS, glGetError=0x${Integer.toHexString(err)})"
            )
            return
        }
        val ssboBindings = scratch[0]

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

        dragoAnalyzeProgram = compileComputeProgram(TAG, "drago_analyze", DRAGO_ANALYZE_SHADER)
        dragoBuildProgram = compileComputeProgram(TAG, "drago_build", DRAGO_BUILD_SHADER)
        fusedAnalyzeProgram = compileComputeProgram(TAG, "fused_analyze", FUSED_ANALYZE_SHADER)
        claheBuildProgram = compileComputeProgram(TAG, "clahe_build", CLAHE_BUILD_SHADER)
        if (dragoAnalyzeProgram == 0 || dragoBuildProgram == 0 ||
            fusedAnalyzeProgram == 0 || claheBuildProgram == 0
        ) {
            releaseGl()
            disable(
                "컴퓨트 프로그램 컴파일/링크에 실패했다 — 위 로그(DragoClaheFusedStage)에 " +
                    "원문이 있다"
            )
            return
        }
        uDragoAnalyzeTexture = GLES20.glGetUniformLocation(dragoAnalyzeProgram, "uTexture")
        uDragoAnalyzeSrcGamma = GLES20.glGetUniformLocation(
            dragoAnalyzeProgram, RenderArm.DRAGO_SRC_GAMMA_UNIFORM
        )
        uDragoAnalyzeSumScale = GLES20.glGetUniformLocation(dragoAnalyzeProgram, "uSumScale")
        uDragoBuildSumScale = GLES20.glGetUniformLocation(dragoBuildProgram, "uSumScale")
        uDragoBuildPixelCount = GLES20.glGetUniformLocation(dragoBuildProgram, "uPixelCount")
        uDragoBuildBias = GLES20.glGetUniformLocation(
            dragoBuildProgram, RenderArm.DRAGO_BIAS_UNIFORM
        )
        uFusedAnalyzeTexture = GLES20.glGetUniformLocation(fusedAnalyzeProgram, "uTexture")
        uFusedAnalyzeSrcGamma = GLES20.glGetUniformLocation(
            fusedAnalyzeProgram, RenderArm.DRAGO_SRC_GAMMA_UNIFORM
        )
        uFusedAnalyzeSaturation = GLES20.glGetUniformLocation(
            fusedAnalyzeProgram, RenderArm.DRAGO_SATURATION_UNIFORM
        )
        uClaheBuildClipLimit = GLES20.glGetUniformLocation(
            claheBuildProgram, RenderArm.CLAHE_CLIP_LIMIT_UNIFORM
        )

        val ids = IntArray(3)
        GLES20.glGenBuffers(3, ids, 0)
        statsBuffer = ids[0]
        histBuffer = ids[1]
        lutBuffer = ids[2]
        val zeros = zeroInit ?: ByteBuffer
            .allocateDirect(DragoStage.STATS_BYTES)
            .order(ByteOrder.nativeOrder())
            .also { zeroInit = it }
        zeros.position(0)
        GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, statsBuffer)
        GLES20.glBufferData(
            GLES31.GL_SHADER_STORAGE_BUFFER, DragoStage.STATS_BYTES, zeros,
            GLES30.GL_DYNAMIC_COPY
        )
        GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, histBuffer)
        GLES20.glBufferData(
            GLES31.GL_SHADER_STORAGE_BUFFER, ClaheStage.TABLE_BYTES, null,
            GLES30.GL_DYNAMIC_COPY
        )
        GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, lutBuffer)
        GLES20.glBufferData(
            GLES31.GL_SHADER_STORAGE_BUFFER, ClaheStage.TABLE_BYTES, null,
            GLES30.GL_DYNAMIC_COPY
        )
        GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)
        val bufErr = GLES20.glGetError()
        if (bufErr != GLES20.GL_NO_ERROR) {
            releaseGl()
            disable("SSBO 3종 생성에 실패했다 (glGetError=0x${Integer.toHexString(bufErr)})")
            return
        }

        ready = true
        status =
            "준비 완료 — ② 자리가 **5패스**(drago 통계 2단 + 융합 통계 2단 + 융합 적용 1)다. " +
                "중간 RGBA8 materialize가 없다: Drago 톤맵을 융합 analyze와 융합 apply에 " +
                "**인라인**해 선형 상태로 CLAHE에 넘긴다(픽셀당 톤맵 2회). " +
                "적용 프래그먼트 **하나가** DragoStats와 ClaheLut 두 블록을 함께 읽는다. " +
                "SSBO binding: drago stats=$DRAGO_STATS_BINDING / clahe hist=" +
                "$CLAHE_HIST_BINDING / clahe lut=$CLAHE_LUT_BINDING. " +
                "🔴 **이식 최적화가 아니라 알고리즘 변경이다** — 상류와 곡선이 달라진다" +
                "(upstream_deviation 참고). " +
                "GL_MAX_FRAGMENT_SHADER_STORAGE_BLOCKS=$fragmentBlocks(필요 " +
                "$REQUIRED_FRAGMENT_SSBO_BLOCKS), " +
                "GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS=$computeBlocks(필요 " +
                "$REQUIRED_COMPUTE_SSBO_BLOCKS), " +
                "GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS=$ssboBindings(필요 " +
                "$REQUIRED_SSBO_BINDINGS), " +
                "GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS=$maxInvocations. " +
                "통계를 CPU로 읽지 않는다(읽으면 GPU 동기화로 측정이 오염된다)"
        Log.i(TAG, status)
    }

    fun onProcessSizeChanged(width: Int, height: Int) {
        pixelCount = width * height
        sumScale = DragoStage.computeSumScale(pixelCount)
    }

    /** 컨텍스트가 사라졌다. **하나라도 빠뜨리면 재생성마다 GPU 메모리가 샌다.** */
    fun releaseGl() {
        if (dragoAnalyzeProgram != 0) GLES20.glDeleteProgram(dragoAnalyzeProgram)
        if (dragoBuildProgram != 0) GLES20.glDeleteProgram(dragoBuildProgram)
        if (fusedAnalyzeProgram != 0) GLES20.glDeleteProgram(fusedAnalyzeProgram)
        if (claheBuildProgram != 0) GLES20.glDeleteProgram(claheBuildProgram)
        if (statsBuffer != 0) GLES20.glDeleteBuffers(1, intArrayOf(statsBuffer), 0)
        if (histBuffer != 0) GLES20.glDeleteBuffers(1, intArrayOf(histBuffer), 0)
        if (lutBuffer != 0) GLES20.glDeleteBuffers(1, intArrayOf(lutBuffer), 0)
        dragoAnalyzeProgram = 0
        dragoBuildProgram = 0
        fusedAnalyzeProgram = 0
        claheBuildProgram = 0
        statsBuffer = 0
        histBuffer = 0
        lutBuffer = 0
        ready = false
        status = "GL 컨텍스트가 사라져 자원을 반납했다"
    }

    // ── 프레임 경로 (렌더 스레드 hot path) ────────────────────────────────
    // 배리어는 전부 **소비하는 쪽 패스의 맨 앞**에 둔다(기존 규약 그대로).

    /** 패스2 — drago 전역 통계. `stage_d_analyze_ms`. */
    fun dragoAnalyze(textureId: Int, width: Int, height: Int) {
        // 직전 프레임의 융합 apply 프래그먼트가 같은 SSBO를 읽었다(WAR).
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
        GLES20.glUseProgram(dragoAnalyzeProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(uDragoAnalyzeTexture, 0)
        // 상수로 박지 않고 uniform으로 넣는다(INTERFACES.md §B-5 요청).
        GLES20.glUniform1f(uDragoAnalyzeSrcGamma, RenderArm.DRAGO_SRC_GAMMA)
        GLES20.glUniform1f(uDragoAnalyzeSumScale, sumScale)
        GLES30.glBindBufferBase(
            GLES31.GL_SHADER_STORAGE_BUFFER, DRAGO_STATS_BINDING, statsBuffer
        )
        GLES31.glDispatchCompute(
            groups(width, DragoStage.LOCAL_SIZE), groups(height, DragoStage.LOCAL_SIZE), 1
        )
    }

    /** 패스3 — drago 통계 → 톤커브 계수. `stage_d_build_ms`. */
    fun dragoBuild() {
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
        GLES20.glUseProgram(dragoBuildProgram)
        GLES20.glUniform1f(uDragoBuildSumScale, sumScale)
        GLES20.glUniform1f(uDragoBuildPixelCount, pixelCount.toFloat())
        GLES20.glUniform1f(uDragoBuildBias, RenderArm.DRAGO_BIAS)
        GLES30.glBindBufferBase(
            GLES31.GL_SHADER_STORAGE_BUFFER, DRAGO_STATS_BINDING, statsBuffer
        )
        GLES31.glDispatchCompute(1, 1, 1)
    }

    /**
     * 패스4 — **톤맵을 인라인한** 타일 히스토그램. `stage_d_analyze2_ms`.
     *
     * 입력은 **FBO_A(원본)**이다 — 체인처럼 drago가 적용된 중간 이미지를 읽는 것이 아니라,
     * 여기서 픽셀마다 톤맵을 다시 계산해 **선형 상태로** L\*을 낸다. 그래서 통계 SSBO를
     * 읽어야 하고(배리어 필요) 톤맵 평가가 한 번 더 든다.
     */
    fun fusedAnalyze(textureId: Int, width: Int, height: Int) {
        // build가 쓴 계수를 이 컴퓨트가 봐야 하고, 직전 프레임 build의 히스토그램 읽기와도
        // WAR다 — 배리어 하나가 둘 다 덮는다.
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
        GLES20.glUseProgram(fusedAnalyzeProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(uFusedAnalyzeTexture, 0)
        GLES20.glUniform1f(uFusedAnalyzeSrcGamma, RenderArm.DRAGO_SRC_GAMMA)
        GLES20.glUniform1f(uFusedAnalyzeSaturation, RenderArm.DRAGO_SATURATION)
        GLES30.glBindBufferBase(
            GLES31.GL_SHADER_STORAGE_BUFFER, DRAGO_STATS_BINDING, statsBuffer
        )
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, CLAHE_HIST_BINDING, histBuffer)
        GLES31.glDispatchCompute(RenderArm.CLAHE_TILE_GRID, RenderArm.CLAHE_TILE_GRID, 1)
    }

    /** 패스5 — 클립 + 재분배 + CDF → 타일별 LUT. `stage_d_build2_ms`. */
    fun claheBuild() {
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
        GLES20.glUseProgram(claheBuildProgram)
        GLES20.glUniform1f(uClaheBuildClipLimit, RenderArm.CLAHE_CLIP_LIMIT)
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, CLAHE_HIST_BINDING, histBuffer)
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, CLAHE_LUT_BINDING, lutBuffer)
        GLES31.glDispatchCompute(RenderArm.CLAHE_TILE_GRID, RenderArm.CLAHE_TILE_GRID, 1)
    }

    /**
     * 패스6 앞. 적용 프래그먼트가 **두 블록을 함께** 읽으므로 둘 다 바인드한다 —
     * 하나라도 빠뜨리면 그 프레임의 톤커브나 LUT가 이전 바인딩을 읽는다.
     */
    fun beforeFusedApply() {
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
        GLES30.glBindBufferBase(
            GLES31.GL_SHADER_STORAGE_BUFFER, DRAGO_STATS_BINDING, statsBuffer
        )
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, CLAHE_LUT_BINDING, lutBuffer)
    }

    // ── 내부 ─────────────────────────────────────────────────────────────

    private fun groups(size: Int, local: Int): Int = (size + local - 1) / local

    private fun disable(reason: String) {
        ready = false
        status = reason
        Log.w(TAG, "drago_clahe_fused 비활성: $reason")
    }

    companion object {
        const val TAG = "DragoClaheFusedStage"

        /** 체인과 같은 배치다. 버퍼는 따로지만 번호 규약을 갈라 둘 이유가 없다. */
        const val DRAGO_STATS_BINDING = DragoStage.STATS_BINDING
        const val CLAHE_HIST_BINDING = 1
        const val CLAHE_LUT_BINDING = 2

        /**
         * 🔴 **체인의 1과 다르다.** 융합의 적용 프래그먼트는 **하나가** DragoStats와
         * ClaheLut 두 블록을 선언하므로 2다. 셰이더가 실제로 선언하는 수에서 나온 값이다.
         */
        const val REQUIRED_FRAGMENT_SSBO_BLOCKS = 2

        /** 융합 analyze 컴퓨트가 DragoStats(읽기) + ClaheHist(쓰기) 둘을 선언한다. */
        const val REQUIRED_COMPUTE_SSBO_BLOCKS = 2

        /** binding 0·1·2를 쓴다. */
        const val REQUIRED_SSBO_BINDINGS = 3

        // drago 통계 2단과 clahe build는 **단품 스테이지의 소스 그대로**다 — 융합이 바꾸는
        // 것은 "무엇을 히스토그램에 넣는가"와 "적용을 어떻게 하는가"이지 통계 산식이 아니다.
        private val DRAGO_ANALYZE_SHADER = DragoStage.analyzeShaderSource(DRAGO_STATS_BINDING)
        private val DRAGO_BUILD_SHADER = DragoStage.buildShaderSource(DRAGO_STATS_BINDING)
        private val CLAHE_BUILD_SHADER =
            ClaheStage.buildShaderSource(CLAHE_HIST_BINDING, CLAHE_LUT_BINDING)

        /**
         * 패스4 — **톤맵 인라인** 타일 히스토그램.
         *
         * [ClaheStage.analyzeShaderSource]와 타일 분할·공유메모리 구조가 같고 **텍셀을
         * 무엇으로 바꿔 세는지만 다르다**: `srgbToLabL`에 바로 넣는 대신
         * `pow(→선형) → dragoToneLinear → linearToLabL`을 태운다.
         *
         * 🔴 그 차이가 **상류와 다른 곡선**을 만든다 — 체인은 여기서 `pow(x, 1/uOutGamma)`로
         * 인코드된 8비트 값을 sRGB piecewise(지수 2.4)로 디코드해서 넣는데, 두 곡선이 다르므로
         * 왕복을 없애는 것이 상쇄가 아니다([RenderArm.FUSED_DEVIATION]).
         *
         * 통계 SSBO는 읽기만 한다. `readonly`를 붙이지 않은 이유: [DragoStage.statsBlock]의
         * 기본 한정자를 그대로 써서 블록 선언 사본을 만들지 않기 위해서다.
         */
        private val FUSED_ANALYZE_SHADER = """
            #version 310 es
            layout(local_size_x = ${ClaheStage.LOCAL_SIZE}, local_size_y = ${ClaheStage.LOCAL_SIZE}) in;
            precision highp float;
            precision highp int;
            uniform sampler2D uTexture;
            uniform float ${RenderArm.DRAGO_SRC_GAMMA_UNIFORM};
            uniform float ${RenderArm.DRAGO_SATURATION_UNIFORM};
            ${DragoStage.statsBlock(DRAGO_STATS_BINDING)}
            layout(std430, binding = $CLAHE_HIST_BINDING) writeonly buffer ClaheHist {
                uint bins[];
            } gHist;
            shared uint sHist[${LabGlsl.BIN_COUNT}];
            ${LabGlsl.FUNCTIONS}
            ${LabGlsl.LINEAR_INPUT_FUNCTIONS}
            ${DragoStage.TONEMAP_FUNCTION_GLSL}
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
                for (int y = y0 + int(gl_LocalInvocationID.y); y < y1; y += ${ClaheStage.LOCAL_SIZE}) {
                    for (int x = x0 + int(gl_LocalInvocationID.x); x < x1; x += ${ClaheStage.LOCAL_SIZE}) {
                        vec3 lin = pow(texelFetch(uTexture, ivec2(x, y), 0).rgb,
                                       vec3(${RenderArm.DRAGO_SRC_GAMMA_UNIFORM}));
                        // 여기 블록에는 `readonly`가 없어 이번 드라이버에서는 컴파일됐지만,
                        // **호출 형태가 apply와 같다.** 한정자 판정이 다른 드라이버에서
                        // 같은 거부가 나므로 apply와 같은 방식으로 미리 복사한다. 위치를
                        // 루프 밖으로 올리지 않았다 — 올리면 이번에 재는 코드가 달라진다.
                        float logAvg = gStats.logAvg;
                        float lmax = gStats.lmax;
                        float biasPow = gStats.biasPow;
                        vec3 tone = dragoToneLinear(lin, logAvg, lmax, biasPow,
                                                    ${RenderArm.DRAGO_SATURATION_UNIFORM});
                        float l = ${LabGlsl.LINEAR_TO_L}(tone);
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

        /**
         * 패스6 — **융합 적용**. 톤맵 + 타일 LUT 이중선형 보간 + 감마를 한 패스에서 한다.
         * 프래그먼트 **하나가 두 SSBO 블록**을 읽는다(그래서 이 arm만 프래그먼트 블록 2개가
         * 필요하다).
         *
         * [ClaheStage.applyShaderSource]와 다른 곳은 맨 앞의 입력 변환뿐이다 —
         * `srgbToLabF(texture(...))` 대신 `pow(→선형) → dragoToneLinear → linearToLabF`.
         * 출력 인코딩은 체인의 마지막 패스와 **같다**(`labFToSrgb` 안의 `linearToSrgb`).
         *
         * ⚠ `uOutGamma`(DRAGO_GAMMA)가 **이 arm에서는 쓰이지 않는다.** 체인에서 그 값은
         * 중간 이미지를 인코드하는 데만 쓰였고, 융합은 그 중간을 없앴다. 파라미터를 지우지
         * 않고 `session.json`에 "적용되지 않는다"로 남긴다 — 값이 사라지면 두 arm의 설정을
         * 나중에 대조할 수 없다.
         */
        val FUSED_APPLY_SHADER = """
            #version 310 es
            precision highp float;
            precision highp int;
            in vec2 vTexCoord;
            out vec4 fragColor;
            uniform sampler2D uTexture;
            uniform float ${RenderArm.DRAGO_SRC_GAMMA_UNIFORM};
            uniform float ${RenderArm.DRAGO_SATURATION_UNIFORM};
            uniform vec2 ${RenderArm.CLAHE_TILES_UNIFORM};
            uniform float ${RenderArm.CLAHE_GAMMA_UNIFORM};
            ${DragoStage.statsBlock(DRAGO_STATS_BINDING, "readonly ")}
            layout(std430, binding = $CLAHE_LUT_BINDING) readonly buffer ClaheLut {
                float entries[];
            } gLut;
            ${LabGlsl.FUNCTIONS}
            ${LabGlsl.LINEAR_INPUT_FUNCTIONS}
            ${DragoStage.TONEMAP_FUNCTION_GLSL}
            void main() {
                vec3 lin = pow(texture(uTexture, vTexCoord).rgb,
                               vec3(${RenderArm.DRAGO_SRC_GAMMA_UNIFORM}));
                // 🔴 SSBO 멤버를 함수 인자로 **직접 넘기지 않는다.** 넘기면 Mali가
                //   "Function call discards 'readonly' access qualifier"로 컴파일을 거부한다
                //   (A34/Mali-G68 실측). 지역 변수로 먼저 복사하면 한정자가 명시적으로
                //   벗겨지고 **산식은 한 글자도 바뀌지 않는다.** readonly를 떼서 회피하지
                //   않는 이유: 그건 "이 버퍼는 읽기 전용"이라는 의도를 지운다.
                float logAvg = gStats.logAvg;
                float lmax = gStats.lmax;
                float biasPow = gStats.biasPow;
                vec3 tone = dragoToneLinear(lin, logAvg, lmax, biasPow,
                                            ${RenderArm.DRAGO_SATURATION_UNIFORM});
                vec3 f = ${LabGlsl.LINEAR_TO_LAB_F}(tone);
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
         * 색공간 변환 **자동 계수**의 대상. 순서는 [RenderArm.DRAGO_CLAHE_FUSED]의 패스
         * 순서 그대로다([DragoClaheChainStage.shaderSourcesByPass]와 같은 규약).
         */
        fun shaderSourcesByPass(
            oesVertex: String,
            oesFragment: String,
            blitVertex: String,
            blitFragment: String,
            // 🔴 present 정점은 blit 정점과 **다른 문자열**이다(present에만 uPositionMatrix가
            //    있다). 하나로 묶으면 이 목록이 실제로 컴파일되는 것과 어긋나고, "같은
            //    String 객체라 텍스트와 어긋날 수 없다"는 이 함수의 근거가 무너진다.
            presentVertex: String,
        ): List<Pair<String, List<String>>> = listOf(
            "oes_to_fbo_a" to listOf(oesVertex, oesFragment),
            "stage2_drago_analyze" to listOf(DRAGO_ANALYZE_SHADER),
            "stage2_drago_build" to listOf(DRAGO_BUILD_SHADER),
            "stage2_fused_analyze" to listOf(FUSED_ANALYZE_SHADER),
            "stage2_clahe_build" to listOf(CLAHE_BUILD_SHADER),
            "stage2_fused_apply" to listOf(ES31_QUAD_VERTEX_SHADER, FUSED_APPLY_SHADER),
            "present" to listOf(presentVertex, blitFragment),
        )
    }
}
