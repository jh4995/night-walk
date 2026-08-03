package com.bammasil.poc.gl

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * `drago_clahe_chain` arm(상류 조합 `D1A1`)의 **통계 부분 두 벌**. ② 자리에서
 * `analyze → build → apply`를 **두 번** 돈다:
 * ```
 * 패스2 drago analyze  전역 통계(Σlog 휘도, 최대 휘도)  stage_d_analyze_ms
 * 패스3 drago build    통계 → 톤커브 계수               stage_d_build_ms
 * 패스4 drago apply    FBO_A → FBO_B                    stage_d_apply_ms   (프래그먼트)
 * 패스5 clahe analyze  타일별 256빈 히스토그램(LAB L)   stage_d_analyze2_ms
 * 패스6 clahe build    클립+재분배+CDF → 타일 LUT       stage_d_build2_ms
 * 패스7 clahe apply    FBO_B → FBO_A                    stage_d_apply2_ms  (프래그먼트)
 * ```
 *
 * ### 왜 [Stage2ComputeStage]를 구현하지 않는가
 * 그 인터페이스는 **3슬롯 한 벌**의 모양이고 [PassthroughRenderer.drawComputeStage2]의 5패스
 * 시퀀스와 짝이다. 조합은 슬롯이 6개라 그 모양에 넣을 수 없고, 억지로 넣으면 이미 실측이 끝난
 * 세 arm(`drago`·`clahe_gamma`·`agcwd`)의 GL 호출 시퀀스를 건드리게 된다.
 *
 * ### 셰이더는 복사하지 않았다 — **binding만 갈랐다**
 * 산식은 [DragoStage]·[ClaheStage]에 그대로 있고 이 클래스는 그 소스 함수를 **다른 binding**
 * 으로 부를 뿐이다. 조합에서는 SSBO 셋이 동시에 살아 있어야 해서 번호가 겹치면 안 된다:
 * ```
 * drago stats = 0   clahe hist = 1   clahe lut = 2
 * ```
 * 단품 arm은 지금까지 쓰던 번호(drago stats=0 / clahe hist=0, lut=1)로 계속 부르므로
 * **그쪽 셰이더 생성 문자열은 바이트 단위로 그대로**다.
 *
 * ### 자원을 단품 스테이지와 공유하지 않는다
 * 프로그램·SSBO를 따로 소유한다. 공유하면 `releaseGl` 소유권이 둘로 갈리고, 무엇보다 단품
 * arm의 자원 수명이 조합 arm의 동작에 따라 바뀐다 — 실측이 끝난 arm의 조건이 흔들린다.
 *
 * ⚠ **프레임 간 상태가 아니다.** 통계 두 벌 모두 같은 프레임 안에서 만들고 쓰고 지운다
 * ([RenderArm.TEMPORAL_STATE] 그대로 stateless다).
 *
 * **스레드 규약: 전부 GL 스레드에서만 부른다.**
 */
class DragoClaheChainStage {

    /** 프로그램·버퍼가 모두 준비됐는가. false면 이 arm은 그릴 수 없다. */
    var ready = false
        private set

    /** 왜 준비됐는지/왜 못 됐는지. `session.json`에 그대로 나간다. */
    var status: String = "아직 준비하지 않았다 (arm != drago_clahe_chain)"
        private set

    private var dragoAnalyzeProgram = 0
    private var dragoBuildProgram = 0
    private var claheAnalyzeProgram = 0
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
    private var uClaheAnalyzeTexture = -1
    private var uClaheBuildClipLimit = -1

    /** 처리 해상도에서 계산한 고정소수 스케일. [DragoStage.computeSumScale] 참고. */
    private var sumScale = 0f
    private var pixelCount = 0

    /** 32바이트 0으로 초기화용. 한 번만 만든다(프레임 경로에서는 쓰지 않는다). */
    private var zeroInit: ByteBuffer? = null

    private val scratch = IntArray(1)

    // ── 컨텍스트 수명 ─────────────────────────────────────────────────────

    /**
     * `onSurfaceCreated`에서 GL 능력 프로브 직후에 부른다. 못 쓰면 **여기서 끈다** —
     * 값을 지어내지 않고 arm이 폴백하게 둔다([DragoStage.onContextCreated]와 같은 패턴).
     */
    fun onContextCreated(capabilities: GlCapabilities?) {
        releaseGl()
        // 두 단품 스테이지가 각자 확인하는 불변식을 조합도 그대로 물려받는다 —
        // 셰이더가 같으므로 어긋나면 같은 방식으로 조용히 틀린다.
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
                    "조합의 통계 두 벌을 dispatch로 접을 수 없다"
            )
            return
        }
        drainStageGlErrors()

        // ⚠ 이 한계는 **한 프래그먼트 셰이더가 선언할 수 있는 SSBO 블록 수**의 상한이고
        //   프로그램 **개수**와는 무관하다. 체인의 적용 프래그먼트는 둘이지만 각각
        //   블록을 **하나씩만** 선언한다(drago apply = DragoStats 하나, clahe apply =
        //   ClaheLut 하나) → 필요값은 1이다. 여기서 2를 요구하면 한계가 정확히 1인 기기에서
        //   단품 arm은 도는데 조합만 **거짓 이유와 함께** 꺼진다.
        //   (융합 arm은 프래그먼트 하나가 두 블록을 선언하므로 그쪽 필요값이 진짜 2이며,
        //    상수를 공유하지 않고 스테이지마다 자기 값을 갖는다.)
        scratch[0] = 0
        GLES20.glGetIntegerv(GLES31.GL_MAX_FRAGMENT_SHADER_STORAGE_BLOCKS, scratch, 0)
        var err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR || scratch[0] < REQUIRED_FRAGMENT_SSBO_BLOCKS) {
            drainStageGlErrors()
            disable(
                "프래그먼트 SSBO 블록이 모자란다 " +
                    "(GL_MAX_FRAGMENT_SHADER_STORAGE_BLOCKS=${scratch[0]}, " +
                    "필요=$REQUIRED_FRAGMENT_SSBO_BLOCKS, " +
                    "glGetError=0x${Integer.toHexString(err)}). 체인의 적용 프래그먼트는 " +
                    "둘이지만 **각각 블록을 하나씩만** 선언하므로 필요값이 1이다 — 프래그먼트가 " +
                    "SSBO를 아예 못 읽으면 LUT·계수를 CPU 왕복 없이 넘길 수단이 없다"
            )
            return
        }
        val fragmentBlocks = scratch[0]

        // 바인딩 **인덱스**의 상한은 위 값이 아니라 이것이다. 0·1·2를 쓰므로 3 이상이 필요한데
        // ES 3.1의 보장 하한이 4라 실제로는 걸리지 않는다 — 그래도 추측하지 않고 잰다.
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

        // CLAHE의 "빈 하나에 스레드 하나"가 성립해야 한다(ES 3.1 보장 하한은 128이다).
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
        claheAnalyzeProgram = compileComputeProgram(TAG, "clahe_analyze", CLAHE_ANALYZE_SHADER)
        claheBuildProgram = compileComputeProgram(TAG, "clahe_build", CLAHE_BUILD_SHADER)
        if (dragoAnalyzeProgram == 0 || dragoBuildProgram == 0 ||
            claheAnalyzeProgram == 0 || claheBuildProgram == 0
        ) {
            releaseGl()
            disable(
                "컴퓨트 프로그램 컴파일/링크에 실패했다 — 위 로그(DragoClaheChainStage)에 " +
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
        uClaheAnalyzeTexture = GLES20.glGetUniformLocation(claheAnalyzeProgram, "uTexture")
        uClaheBuildClipLimit = GLES20.glGetUniformLocation(
            claheBuildProgram, RenderArm.CLAHE_CLIP_LIMIT_UNIFORM
        )

        val ids = IntArray(3)
        GLES20.glGenBuffers(3, ids, 0)
        statsBuffer = ids[0]
        histBuffer = ids[1]
        lutBuffer = ids[2]
        // drago 통계만 0에서 출발해야 한다(atomicAdd 누산). 이후 프레임은 build가 마지막에
        // 0으로 되돌린다. CLAHE의 히스토그램·LUT는 매 프레임 통째로 덮어쓰므로 초기값이 없다.
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
            "준비 완료 — ② 자리가 **6패스**(drago 3단 → clahe 3단 직렬)다. " +
                "drago: 리덕션 컴퓨트 dispatch 1회 + 계수 컴퓨트 1스레드 + 적용 프래그먼트 " +
                "1패스(FBO_A→FBO_B). clahe: 타일 히스토그램 dispatch 1회 + LUT dispatch 1회 + " +
                "적용 프래그먼트 1패스(FBO_B→FBO_A). " +
                "SSBO binding: drago stats=$DRAGO_STATS_BINDING / clahe hist=" +
                "$CLAHE_HIST_BINDING / clahe lut=$CLAHE_LUT_BINDING (셋이 동시에 살아 있다). " +
                "중간 표현은 RGBA8 FBO다 — 상류 cv2 파이프라인의 8비트 왕복을 그대로 옮긴 " +
                "**체인**이며 융합이 아니다. " +
                "GL_MAX_FRAGMENT_SHADER_STORAGE_BLOCKS=$fragmentBlocks(필요 " +
                "$REQUIRED_FRAGMENT_SSBO_BLOCKS), " +
                "GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS=$ssboBindings(필요 " +
                "$REQUIRED_SSBO_BINDINGS), " +
                "GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS=$maxInvocations. " +
                "통계를 CPU로 읽지 않는다(읽으면 GPU 동기화로 측정이 오염된다)"
        Log.i(TAG, status)
    }

    /**
     * 처리 해상도가 정해졌을 때(또는 바뀌었을 때). drago 쪽 고정소수 스케일만 다시 잡는다 —
     * CLAHE는 타일 경계를 셰이더가 `textureSize`에서 직접 낸다.
     */
    fun onProcessSizeChanged(width: Int, height: Int) {
        pixelCount = width * height
        sumScale = DragoStage.computeSumScale(pixelCount)
    }

    /** 컨텍스트가 사라졌다. **하나라도 빠뜨리면 재생성마다 GPU 메모리가 샌다.** */
    fun releaseGl() {
        if (dragoAnalyzeProgram != 0) GLES20.glDeleteProgram(dragoAnalyzeProgram)
        if (dragoBuildProgram != 0) GLES20.glDeleteProgram(dragoBuildProgram)
        if (claheAnalyzeProgram != 0) GLES20.glDeleteProgram(claheAnalyzeProgram)
        if (claheBuildProgram != 0) GLES20.glDeleteProgram(claheBuildProgram)
        if (statsBuffer != 0) GLES20.glDeleteBuffers(1, intArrayOf(statsBuffer), 0)
        if (histBuffer != 0) GLES20.glDeleteBuffers(1, intArrayOf(histBuffer), 0)
        if (lutBuffer != 0) GLES20.glDeleteBuffers(1, intArrayOf(lutBuffer), 0)
        dragoAnalyzeProgram = 0
        dragoBuildProgram = 0
        claheAnalyzeProgram = 0
        claheBuildProgram = 0
        statsBuffer = 0
        histBuffer = 0
        lutBuffer = 0
        ready = false
        status = "GL 컨텍스트가 사라져 자원을 반납했다"
    }

    // ── 프레임 경로 (렌더 스레드 hot path) ────────────────────────────────
    // 배리어는 전부 **소비하는 쪽 패스의 맨 앞**에 둔다(기존 규약 그대로) — 대기 비용이
    // 소비자에게 청구되도록 의도한 것이며, 실제로 그렇게 되는지는 이 기기에서 미검증이다.

    /** 패스2 — drago 전역 통계. `stage_d_analyze_ms`. */
    fun dragoAnalyze(textureId: Int, width: Int, height: Int) {
        // 직전 프레임의 drago apply 프래그먼트가 같은 SSBO를 읽었다(WAR).
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

    /** 패스3 — drago 통계 → 톤커브 계수. `stage_d_build_ms`. 스레드 1개짜리 dispatch다. */
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

    /** 패스4 앞. build가 쓴 계수를 프래그먼트가 볼 수 있게 한다. */
    fun beforeDragoApply() {
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
        GLES30.glBindBufferBase(
            GLES31.GL_SHADER_STORAGE_BUFFER, DRAGO_STATS_BINDING, statsBuffer
        )
    }

    /**
     * 패스5 — clahe 타일 히스토그램. `stage_d_analyze2_ms`.
     *
     * 입력은 **drago가 적용된 FBO_B**다. 프레임버퍼 쓰기 → 텍스처 읽기는 GL이 자동으로
     * 순서를 맞춰 주므로 그쪽에는 배리어를 걸지 않는다. `width`/`height`는 쓰지 않는다 —
     * 디스패치 크기가 해상도가 아니라 타일 격자다([ClaheStage.analyze]와 같다).
     */
    fun claheAnalyze(textureId: Int, width: Int, height: Int) {
        // 직전 프레임의 clahe build가 같은 히스토그램 버퍼를 읽었다(WAR).
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
        GLES20.glUseProgram(claheAnalyzeProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(uClaheAnalyzeTexture, 0)
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, CLAHE_HIST_BINDING, histBuffer)
        GLES31.glDispatchCompute(RenderArm.CLAHE_TILE_GRID, RenderArm.CLAHE_TILE_GRID, 1)
    }

    /** 패스6 — 클립 + 재분배 + CDF → 타일별 LUT. `stage_d_build2_ms`. */
    fun claheBuild() {
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
        GLES20.glUseProgram(claheBuildProgram)
        // 상수로 박지 않고 uniform으로 넣는다(INTERFACES.md §B-5 요청).
        GLES20.glUniform1f(uClaheBuildClipLimit, RenderArm.CLAHE_CLIP_LIMIT)
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, CLAHE_HIST_BINDING, histBuffer)
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, CLAHE_LUT_BINDING, lutBuffer)
        GLES31.glDispatchCompute(RenderArm.CLAHE_TILE_GRID, RenderArm.CLAHE_TILE_GRID, 1)
    }

    /** 패스7 앞. build가 쓴 LUT를 프래그먼트가 볼 수 있게 한다. */
    fun beforeClaheApply() {
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, CLAHE_LUT_BINDING, lutBuffer)
    }

    // ── 내부 ─────────────────────────────────────────────────────────────

    private fun groups(size: Int, local: Int): Int = (size + local - 1) / local

    private fun disable(reason: String) {
        ready = false
        status = reason
        Log.w(TAG, "drago_clahe_chain 비활성: $reason")
    }

    companion object {
        const val TAG = "DragoClaheChainStage"

        /**
         * 조합의 SSBO binding. **셋이 동시에 살아 있어야 한다** — 단품 arm의 값
         * (drago stats=0 / clahe hist=0 / lut=1)을 그대로 쓰면 drago stats와 clahe hist가
         * 충돌한다. drago는 단품과 같은 0을 유지하고 clahe만 1·2로 민다.
         */
        const val DRAGO_STATS_BINDING = DragoStage.STATS_BINDING
        const val CLAHE_HIST_BINDING = 1
        const val CLAHE_LUT_BINDING = 2

        /**
         * **한 프래그먼트가 선언하는 블록 수**에서 유도한 값이다. 체인의 적용 프래그먼트는
         * 둘이지만 각각 하나씩만 선언하므로 **1**이다 — 프로그램 개수와 무관한 한계다.
         *
         * ⚠ 융합 arm은 프래그먼트 하나가 drago stats와 clahe lut **두 블록을 동시에**
         * 선언하므로 거기서는 2가 맞다. **상수를 공유하지 않는다** — 스테이지마다 자기
         * 셰이더가 실제로 선언하는 수를 갖는다([DragoClaheFusedStage]).
         */
        const val REQUIRED_FRAGMENT_SSBO_BLOCKS = 1

        /** binding 0·1·2를 쓰므로 3개. ES 3.1 보장 하한(4)보다 작지만 추측하지 않고 잰다. */
        const val REQUIRED_SSBO_BINDINGS = 3

        // 셰이더 소스는 **전부 단품 스테이지의 것**이다. 여기서 텍스트를 복사하지 않는다 —
        // binding만 다른 값으로 부른다(그것이 조합에서 달라지는 유일한 것이다).
        private val DRAGO_ANALYZE_SHADER = DragoStage.analyzeShaderSource(DRAGO_STATS_BINDING)
        private val DRAGO_BUILD_SHADER = DragoStage.buildShaderSource(DRAGO_STATS_BINDING)
        private val CLAHE_ANALYZE_SHADER = ClaheStage.analyzeShaderSource(CLAHE_HIST_BINDING)
        private val CLAHE_BUILD_SHADER =
            ClaheStage.buildShaderSource(CLAHE_HIST_BINDING, CLAHE_LUT_BINDING)

        /** 패스4 프래그먼트. [PassthroughRenderer]가 이 소스로 프로그램을 만든다. */
        val DRAGO_APPLY_SHADER = DragoStage.applyShaderSource(DRAGO_STATS_BINDING)

        /** 패스7 프래그먼트. [PassthroughRenderer]가 이 소스로 프로그램을 만든다. */
        val CLAHE_APPLY_SHADER = ClaheStage.applyShaderSource(CLAHE_LUT_BINDING)

        /**
         * 색공간 변환 **자동 계수**의 대상이 되는 소스 — 패스 이름 → 그 패스가
         * `glShaderSource`에 넘기는 문자열들. `onSurfaceCreated`에서 1회만 훑는다
         * (hot path가 아니다). 여기 있는 것과 실제로 컴파일되는 것이 **같은 String 객체**라
         * 셰이더 텍스트와 어긋날 수 없다.
         *
         * 순서는 [RenderArm.DRAGO_CLAHE_CHAIN]의 패스 순서 그대로다.
         */
        fun shaderSourcesByPass(
            oesVertex: String,
            oesFragment: String,
            blitVertex: String,
            blitFragment: String,
        ): List<Pair<String, List<String>>> = listOf(
            "oes_to_fbo_a" to listOf(oesVertex, oesFragment),
            "stage2_drago_analyze" to listOf(DRAGO_ANALYZE_SHADER),
            "stage2_drago_build" to listOf(DRAGO_BUILD_SHADER),
            "stage2_drago_apply" to listOf(ES31_QUAD_VERTEX_SHADER, DRAGO_APPLY_SHADER),
            "stage2_clahe_analyze" to listOf(CLAHE_ANALYZE_SHADER),
            "stage2_clahe_build" to listOf(CLAHE_BUILD_SHADER),
            "stage2_clahe_apply" to listOf(ES31_QUAD_VERTEX_SHADER, CLAHE_APPLY_SHADER),
            "present" to listOf(blitVertex, blitFragment),
        )
    }
}
