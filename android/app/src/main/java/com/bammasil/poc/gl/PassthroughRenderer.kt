package com.bammasil.poc.gl

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.bammasil.poc.log.CaptureClockProbe
import com.bammasil.poc.log.CaptureClockVerdict
import com.bammasil.poc.log.ClockProbeSample
import com.bammasil.poc.log.FrameLogRecorder
import com.bammasil.poc.source.FrameTarget
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicLong
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 표시 경로 2-C의 렌더러. arm에 따라 두 경로 중 하나를 탄다([RenderArm]).
 *
 * - [RenderArm.PASSTHROUGH] — 카메라가 채운 OES 텍스처를 화면에 **그대로 blit**하는 1패스.
 *   기존 승격 베이스라인을 재현하는 경로이므로 여기에 새 GL 호출을 끼워 넣지 않는다.
 * - 그 외 — ②를 얹을 자리를 만드는 **3패스 골격**:
 *   ```
 *   패스1  OES  → FBO_A (처리 해상도)      → stage_b_ms
 *   패스2  FBO_A → FBO_B (처리 해상도) ②   → stage_d_ms
 *   패스3  FBO_B → 화면 (surface 크기)     → gpu_present_ms
 *   ```
 *   **패스마다 `glBindFramebuffer` + `glClear`를 명시**한다. 타일 기반 GPU(Mali-G68)에서
 *   드라이버가 렌더패스를 병합하면 timer query 귀속이 흐려진다.
 *
 * 3패스 arm은 [GpuTimerRing]으로 패스별 GPU 시간을 잰다. **패스스루 arm에는 query를 하나도
 * 걸지 않는다** — query 자체가 GPU 동작과 드라이버 스케줄링을 바꾸므로, 승격 베이스라인을
 * 재현하는 경로에 넣으면 그 기준이 기준이 아니게 된다.
 *
 * ②의 알고리즘(CLAHE)은 아직 없다 — 자리만 만든다.
 *
 * ### 타임스탬프를 찍는 위치 (정직하게 문서화해야 하는 지점)
 * `GLSurfaceView`를 쓰면 `swapBuffers`는 프레임워크가 `onDrawFrame` 반환 **후에** 하므로
 * 우리가 잴 수 없다. 그래서:
 * - `t_render_start_ns` = `onDrawFrame` 진입 직후(`updateTexImage` 전)
 * - `t_render_end_ns` = `onDrawFrame` 반환 직전(드로우콜 제출 완료)
 *
 * 귀결 두 개를 함께 봐야 한다:
 * - `output_interval_ms`(연속 `onDrawFrame` 종료 간격)는 `swapBuffers`가 다음 사이클 앞에서
 *   블록하므로 **실제 표시 주기 = 진짜 프레임타임**이 된다.
 * - `render_latency_ms`는 `glDrawArrays`가 즉시 반환하므로 **CPU 제출 비용이고 GPU 실행
 *   시간이 아니다.** "렌더가 사실상 무료"로 읽으면 틀린다. 실제 GPU 비용은 `stage_*_ms` /
 *   `gpu_present_ms` 열에 따로 있다. 같은 문장이 `session.json`에도 남는다.
 */
class PassthroughRenderer(
    private val recorder: FrameLogRecorder,
    /**
     * `onFrameAvailable`을 받을 **전용 스레드**의 Handler.
     * 넘기지 않으면 SurfaceTexture는 콜백을 메인 루퍼로 보내고, 그러면 UI 작업 지연이
     * `t_recv_ns`에 섞여 "프레임 도착 시각"이 도착 시각이 아니게 된다.
     */
    private val frameSignalHandler: Handler,
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener, FrameTarget {

    /** GL 스레드에서 SurfaceTexture가 준비되면 호출된다(GL 스레드에서 불린다). */
    var onGlReady: (() -> Unit)? = null

    /** 새 프레임이 왔을 때 `GLSurfaceView.requestRender()`를 부르기 위한 훅. */
    var onFrameSignal: (() -> Unit)? = null

    @Volatile
    var surfaceWidth = 0
        private set

    @Volatile
    var surfaceHeight = 0
        private set

    /**
     * **협상된 처리 해상도.** 값의 출처는 `CameraFrameSource`가 `acquireSurface`에 넘기는
     * `SurfaceRequest.resolution`이고, 그건 `NegotiatedConfig`와 같은 값이다.
     * 협상 전에는 0이며 **그 경우 값을 지어내지 않는다** — FBO를 만들지 않고 그 사실을 남긴다.
     */
    @Volatile
    var processWidth = 0
        private set

    @Volatile
    var processHeight = 0
        private set

    /** `onSurfaceCreated`에서 1회 실측한 GL 능력. 수집 전에는 null. */
    @Volatile
    var capabilities: GlCapabilities? = null
        private set

    /** 오프스크린 자원의 현재 상태(사람이 읽는 문장). `session.json`에 그대로 나간다. */
    @Volatile
    var offscreenStatus: String = "아직 필요하지 않았다 (arm=${RenderArm.DEFAULT.id})"
        private set

    /**
     * 3패스 arm인데 오프스크린을 못 써서 1패스로 되돌아간 드로우 수.
     * 0이 아니면 그 런의 `pipeline_stages` 선언과 실제 경로가 어긋난 것이다.
     */
    @Volatile
    var offscreenFallbackDraws = 0
        private set

    @Volatile
    private var arm: RenderArm = RenderArm.DEFAULT

    @Volatile
    private var surfaceTexture: SurfaceTexture? = null

    private var cameraSurface: Surface? = null

    private var oesTextureId = 0

    /** 패스1용(OES 샘플러). 패스스루 arm도 이것 하나만 쓴다. */
    private var oesProgram: QuadProgram? = null

    /** 패스2(복사)·패스3(표시) 공용. */
    private var blitProgram: QuadProgram? = null

    /** 패스2(감마). ② 자리의 비용 하한. */
    private var gammaProgram: QuadProgram? = null

    /** [0]=FBO_A, [1]=FBO_B. 0이면 미생성. GL 스레드 전용. */
    private val fbos = IntArray(FBO_COUNT)
    private val fboTextures = IntArray(FBO_COUNT)
    private var fboWidth = 0
    private var fboHeight = 0

    /**
     * 패스별 GPU 시간. **3패스 arm에서만** 쓴다. 링·query 객체는 GL 스레드 전용이고,
     * 회수된 값은 [FrameLogRecorder.setGpuTiming]으로 **그 query를 건 프레임의 행**에 간다.
     */
    private val gpuTimer = GpuTimerRing(recorder)

    private val texMatrix = FloatArray(16)
    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(VERTEX_DATA.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(VERTEX_DATA)
            position(0)
        }

    /**
     * `onFrameAvailable`이 찍은 도착 시각. 프레임 신호 스레드가 쓰고 GL 스레드가 가져간다.
     * 한 사이클에 두 번 도착하면 **최신 값이 남는다** — `updateTexImage()`가 최신 프레임을
     * 물어오므로 그게 짝이 맞는다.
     */
    private val pendingRecvNs = AtomicLong(NO_FRAME)

    /** 시계 기준 판별용 표본. GL 스레드 전용. */
    private val probeSamples = ArrayList<ClockProbeSample>(CaptureClockProbe.SAMPLE_LIMIT)

    init {
        Matrix.setIdentityM(texMatrix, 0)
    }

    // ── GLSurfaceView.Renderer ────────────────────────────────────────────

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // 컨텍스트가 재생성되면 이전 텍스처·FBO·프로그램·SurfaceTexture는 전부 죽은 것이다.
        releaseGlResources()
        // 능력 프로브는 프로그램을 만들기 전에 찍는다 — 컴파일 실패로 조기 반환하는 경로가
        // 생기더라도 GL 문자열은 남아야 원인을 되물을 수 있다.
        capabilities = GlCapabilitiesProbe.probe()
        // 확장 유무는 프로브 실측이 유일한 근거다. 링은 그 값을 받아 두고, 실제로 걸 수
        // 있는지는 자기 프로브(glBeginQuery + glGetError)로 따로 확인한다.
        gpuTimer.onContextCreated(
            capabilities?.disjointTimerQuery ?: GlCapabilitiesProbe.UNKNOWN
        )
        oesProgram = buildProgram(VERTEX_SHADER_OES, FRAGMENT_SHADER_OES)
        blitProgram = buildProgram(VERTEX_SHADER_2D, FRAGMENT_SHADER_BLIT)
        gammaProgram = buildProgram(VERTEX_SHADER_2D, FRAGMENT_SHADER_GAMMA)
        oesTextureId = createOesTexture()
        Matrix.setIdentityM(texMatrix, 0)
        pendingRecvNs.set(NO_FRAME)

        // 컨텍스트가 재생성됐는데 이미 3패스 arm이면 여기서 다시 준비한다.
        if (arm != RenderArm.PASSTHROUGH) {
            gpuTimer.prepare()
        }

        val created = SurfaceTexture(oesTextureId)
        created.setOnFrameAvailableListener(this, frameSignalHandler)
        surfaceTexture = created
        Log.i(
            TAG,
            "GL 준비 완료 (oesTexture=$oesTextureId, arm=${arm.id}, " +
                "gl=${capabilities?.version}, ctx=${capabilities?.contextVersion})"
        )
        onGlReady?.invoke()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        // ⚠ 뷰포트(화면)와 처리 해상도는 **분리 관리**한다. 화면 크기가 바뀌어도 FBO는
        //   협상된 처리 해상도 그대로여야 한다 — 여기서 fboWidth/Height를 건드리지 않는다.
        surfaceWidth = width
        surfaceHeight = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        // ① 프레임 도착 시각을 **먼저** 확정한다.
        //    순서를 뒤집으면 이 사이에 onFrameAvailable이 끼어들어 t_recv > t_render_start 가
        //    될 수 있고, 하네스 교차검사 A(render_latency <= recv_to_render)가 **거짓 위반**을
        //    낸다. AtomicLong 읽기 하나이므로 "onDrawFrame 진입 직후"라는 규약과 어긋나지 않는다.
        val tRecvNs = pendingRecvNs.getAndSet(NO_FRAME)
        val tRenderStartNs = SystemClock.elapsedRealtimeNanos()
        val hasNewFrame = tRecvNs != NO_FRAME

        var tCaptureNs = MISSING
        val texture = surfaceTexture
        if (texture != null && hasNewFrame) {
            texture.updateTexImage()
            texture.getTransformMatrix(texMatrix)
            // 원본 그대로 기록한다. 앱이 보정하면 어느 시계 기준인지 판별할 근거가 사라진다.
            tCaptureNs = texture.timestamp
            collectClockProbe(tCaptureNs)
        }

        // 계측은 **측정 중 + 새 프레임이 있는 드로우**로만 한다. 그래야 CSV 행 하나에
        // query 한 벌이 정확히 대응하고, 대기 화면에서 GPU 동작을 바꾸지 않는다.
        dispatchDraw(hasNewFrame && recorder.isRecording)

        val tRenderEndNs = SystemClock.elapsedRealtimeNanos()
        if (hasNewFrame) {
            val slot = recorder.record(tRecvNs, tCaptureNs, tRenderStartNs, tRenderEndNs)
            // 이번 프레임에 건 query가 어느 행을 채워야 하는지 여기서 확정한다.
            // 계측하지 않은 프레임에서 불러도 안전하다(링이 스스로 걸러 낸다).
            gpuTimer.commitFrame(slot)
        } else {
            recorder.noteDrawWithoutNewFrame()
        }
    }

    // ── SurfaceTexture.OnFrameAvailableListener ───────────────────────────

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        // 2-C에는 분석 콜백이 없으므로 이것이 "프레임 도착" 신호다.
        // 시계는 elapsedRealtimeNanos 하나로 통일한다 — System.nanoTime()을 섞으면
        // 하네스 교차검사가 잡아내고 그 로그의 체류시간은 지연 근거로 못 쓴다.
        pendingRecvNs.set(SystemClock.elapsedRealtimeNanos())
        if (recorder.isRecording) {
            recorder.surfaceFramesAvailable.incrementAndGet()
        }
        onFrameSignal?.invoke()
    }

    // ── FrameTarget (메인 스레드에서 불린다) ──────────────────────────────

    override fun acquireSurface(width: Int, height: Int): Surface? {
        val texture = surfaceTexture ?: return null
        texture.setDefaultBufferSize(width, height)
        // 처리 해상도의 유일한 출처. 하드코딩하지 않는다.
        processWidth = width
        processHeight = height
        val surface = Surface(texture)
        cameraSurface = surface
        return surface
    }

    override fun releaseSurface(surface: Surface, resultCode: Int) {
        Log.i(TAG, "Surface 해제 (resultCode=$resultCode)")
        if (cameraSurface === surface) {
            cameraSurface = null
        }
        surface.release()
    }

    // ── arm 전환 (GL 스레드에서 부른다: glView.queueEvent) ────────────────

    /**
     * arm을 바꾼다. **측정 중에는 부르지 않는다** — 런 도중 arm이 바뀌면 그 분포는 오염된
     * 것이다(UI에서 스피너를 잠근다).
     */
    fun setArm(next: RenderArm) {
        if (arm == next) return
        arm = next
        if (next == RenderArm.PASSTHROUGH) {
            // 1패스 경로는 뷰포트를 스스로 세우지 않는다(기존 경로 그대로 두기 위해서다).
            // 3패스에서 처리 해상도로 바꿔 둔 뷰포트를 여기서 화면 크기로 되돌린다.
            releaseOffscreen()
            if (surfaceWidth > 0 && surfaceHeight > 0) {
                GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
            }
            offscreenStatus = "arm=passthrough — 오프스크린을 만들지 않는다(기존 1패스 재현)"
        } else {
            // 일회성 프로브와 query 객체 생성을 **측정 전에** 끝내 둔다. 지연 초기화에
            // 맡기면 그 비용이 측정 첫 프레임에 얹힌다.
            gpuTimer.prepare()
        }
        Log.i(TAG, "arm 전환: ${next.id}")
    }

    // ── 측정 부수 정보 ───────────────────────────────────────────────────

    /** GL 스레드에서 부른다(표본을 소유한 스레드가 GL 스레드다). */
    fun clockVerdict(): CaptureClockVerdict = CaptureClockProbe.resolve(probeSamples.toList())

    /** 측정 시작 시 GL 스레드에서 부른다. */
    fun resetClockProbe() {
        probeSamples.clear()
    }

    /** 측정 시작 시 GL 스레드에서 부른다. 런별로 세야 의미가 있는 카운터들. */
    fun resetRenderCounters() {
        offscreenFallbackDraws = 0
        // 세대를 올려 이전 런의 in-flight query 결과가 이번 런의 행에 들어가는 것을 막는다.
        gpuTimer.beginRun()
    }

    /**
     * 측정 정지 후 `frames.csv`를 쓰기 **직전에** GL 스레드에서 부른다.
     * 반환값의 `instrumented`가 CSV에 GPU 열을 실을지를 정하고, 나머지는 `session.json`의
     * `gpu_timer` 블록이 된다.
     */
    fun finishGpuTimerRun(): GpuTimerReport = gpuTimer.finishRun()

    // ── 내부 ─────────────────────────────────────────────────────────────

    private fun collectClockProbe(tCaptureNs: Long) {
        if (!recorder.isRecording) return
        if (tCaptureNs <= 0L) return
        if (probeSamples.size >= CaptureClockProbe.SAMPLE_LIMIT) return
        // 두 시계를 나란히 읽는다. 측정 초반 몇 장에만 추가되는 비용이다.
        probeSamples.add(
            ClockProbeSample(
                tCaptureNs = tCaptureNs,
                monotonicNs = System.nanoTime(),
                boottimeNs = SystemClock.elapsedRealtimeNanos(),
            )
        )
    }

    /**
     * arm 하나로 갈린다. **passthrough는 새 코드 경로를 타지 않는다** — FBO도 만들지 않고,
     * GPU timer query도 걸지 않고, 아래 [drawPassthrough]로 바로 간다.
     */
    private fun dispatchDraw(instrument: Boolean) {
        val oes = oesProgram ?: return
        if (arm == RenderArm.PASSTHROUGH) {
            drawPassthrough(oes)
            return
        }
        val stage2 = if (arm == RenderArm.GAMMA_ONLY) gammaProgram else blitProgram
        val present = blitProgram
        if (stage2 == null || present == null || !ensureOffscreen()) {
            // 3패스를 못 돌면 조용히 넘어가지 않는다. 세어서 session.json에 남긴다 —
            // 선언한 pipeline_stages와 실제 경로가 어긋난 채로 숫자가 나가면 안 된다.
            offscreenFallbackDraws++
            drawPassthrough(oes)
            return
        }
        drawThreePass(oes, stage2, present, instrument)
    }

    /** 기존 1패스. 승격 베이스라인 재현 경로이므로 호출 순서를 바꾸지 않는다. */
    private fun drawPassthrough(oes: QuadProgram) {
        // 타일 기반 GPU(Mali-G68)에서 clear를 생략하면 타일 버퍼를 이전 내용으로 채워 넣는
        // load 비용이 생긴다. 화면 전체를 덮는 quad라도 clear를 부르는 쪽이 싸다.
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawQuad(oes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
    }

    /**
     * 3패스 골격. 패스마다 바인드·뷰포트·clear를 **명시**한다.
     * 이걸 생략하면 드라이버가 렌더패스를 병합할 수 있고, 그러면 패스별 timer query를
     * 붙여도 어느 패스의 비용인지 귀속이 흐려진다.
     *
     * ### query 경계를 어디에 두는가 (타일 GPU에서 정직해야 하는 지점)
     * 세 query가 이 시퀀스의 **모든 GL 명령을 빈틈없이** 덮도록 걸었다 —
     * `beginPass()` … `endPass()`가 연달아 붙어 있고 그 사이에 아무 명령도 없다.
     *
     * ⚠ **그러나 "명령을 덮는다"는 "그 명령이 유발한 GPU 작업을 덮는다"가 아니다.**
     * 타일 기반 GPU는 드로우콜을 즉시 실행하지 않고 타일 단위로 몰아 처리하므로, 어떤
     * 작업이 어느 query 구간에 담기는지는 드라이버가 정한다. 확실한 것 하나:
     * **패스3은 기본 프레임버퍼에 그리는데 그 타일 해결은 `eglSwapBuffers`에서 일어나고,
     * `GLSurfaceView`는 그것을 `onDrawFrame` 반환 **후에** 부른다** — 세 query 전부의
     * 바깥이다. `GLSurfaceView`를 쓰는 한 이 경계는 우리가 옮길 수 없다.
     *
     * 그래서 두 갈래가 있고 **어느 쪽이든 "합이 정확하다"고는 말할 수 없다:**
     *  - 드라이버가 `glEndQuery`에서 렌더패스를 쪼갠다 → 계측이 측정 대상 워크로드 자체를
     *    바꾸고 있다.
     *  - 쪼개지 않는다 → 온스크린 해결 비용이 `gpu_present_ms` 밖으로 떨어져 `gpu_sum_ms`가
     *    **과소**가 된다.
     *
     * **우리는 이 기기에서 둘 중 어느 쪽인지 판별하지 못했다.** 따라서 개별 열의 경계는
     * ±1패스만큼 흐리고, **합도 하한으로 읽어야 한다.** 같은 문장이 `session.json`의
     * `gpu_timer.attribution_note`에도 나간다.
     */
    private fun drawThreePass(
        oes: QuadProgram,
        stage2: QuadProgram,
        present: QuadProgram,
        instrument: Boolean,
    ) {
        val timing = instrument && gpuTimer.beginFrame()

        // 패스1: OES → FBO_A (처리 해상도). stage_b_ms.
        if (timing) gpuTimer.beginPass()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[0])
        GLES20.glViewport(0, 0, fboWidth, fboHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawQuad(oes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        // 같은 텍스처 유닛에 OES와 2D를 동시에 물려 두면 드라이버에 따라 경고·미정의가 된다.
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        if (timing) gpuTimer.endPass()

        // 패스2: FBO_A → FBO_B (처리 해상도). ②가 들어갈 자리. stage_d_ms.
        if (timing) gpuTimer.beginPass()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[1])
        GLES20.glViewport(0, 0, fboWidth, fboHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawQuad(stage2, GLES20.GL_TEXTURE_2D, fboTextures[0])
        if (timing) gpuTimer.endPass()

        // 패스3: FBO_B → 화면 (surface 크기). gpu_present_ms.
        if (timing) gpuTimer.beginPass()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawQuad(present, GLES20.GL_TEXTURE_2D, fboTextures[1])
        if (timing) gpuTimer.endPass()
    }

    /** 프레임당 객체를 만들지 않는다 — 인자는 전부 원시형이고 프로그램은 미리 만들어 둔다. */
    private fun drawQuad(program: QuadProgram, textureTarget: Int, textureId: Int) {
        GLES20.glUseProgram(program.handle)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(textureTarget, textureId)
        GLES20.glUniform1i(program.uTexture, 0)
        if (program.uTexMatrix >= 0) {
            GLES20.glUniformMatrix4fv(program.uTexMatrix, 1, false, texMatrix, 0)
        }
        if (program.uGamma >= 0) {
            // 상수로 박지 않고 uniform으로 넣는다(INTERFACES.md §B-5 요청). 실제로 쓴 값은
            // session.json의 stage2_params에 provenance와 함께 남는다.
            GLES20.glUniform1f(program.uGamma, RenderArm.GAMMA_MEASUREMENT_VALUE)
        }

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(
            program.aPosition, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, vertexBuffer
        )
        GLES20.glEnableVertexAttribArray(program.aPosition)
        vertexBuffer.position(2)
        GLES20.glVertexAttribPointer(
            program.aTexCoord, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, vertexBuffer
        )
        GLES20.glEnableVertexAttribArray(program.aTexCoord)

        // ⚠ glDrawArrays는 명령을 제출하고 **즉시 반환한다.** 따라서 t_render_end - t_render_start
        //   는 CPU 제출 비용이며 GPU 실행 시간이 아니다.
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /**
     * 처리 해상도의 FBO 2장을 준비한다. 이미 같은 크기로 있으면 아무것도 하지 않는다.
     * **협상 결과가 없으면 값을 지어내지 않고 false를 돌려준다.**
     */
    private fun ensureOffscreen(): Boolean {
        val w = processWidth
        val h = processHeight
        if (w <= 0 || h <= 0) {
            offscreenStatus =
                "협상된 처리 해상도가 없다 — 카메라가 Surface를 요청하기 전이다. " +
                    "FBO 크기를 지어내지 않고 1패스로 그린다"
            return false
        }
        if (fboWidth == w && fboHeight == h && fbos[0] != 0 && fbos[1] != 0) return true

        releaseOffscreen()
        GLES20.glGenTextures(FBO_COUNT, fboTextures, 0)
        GLES20.glGenFramebuffers(FBO_COUNT, fbos, 0)
        for (i in 0 until FBO_COUNT) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextures[i])
            // RGBA8/UNSIGNED_BYTE만 쓴다. float/half 포맷은 기기 의존성을 늘린다.
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[i])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, fboTextures[i], 0
            )
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                releaseOffscreen()
                offscreenStatus =
                    "FBO[$i] 미완성 (glCheckFramebufferStatus=$status, ${w}x$h) — 1패스로 그린다"
                Log.e(TAG, offscreenStatus)
                return false
            }
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        fboWidth = w
        fboHeight = h
        offscreenStatus = "FBO 2장 준비 완료 ${w}x$h (RGBA8, 처리 해상도 = 협상된 카메라 해상도)"
        Log.i(TAG, offscreenStatus)
        return true
    }

    private fun releaseOffscreen() {
        if (fbos[0] != 0 || fbos[1] != 0) {
            GLES20.glDeleteFramebuffers(FBO_COUNT, fbos, 0)
        }
        if (fboTextures[0] != 0 || fboTextures[1] != 0) {
            GLES20.glDeleteTextures(FBO_COUNT, fboTextures, 0)
        }
        for (i in 0 until FBO_COUNT) {
            fbos[i] = 0
            fboTextures[i] = 0
        }
        fboWidth = 0
        fboHeight = 0
    }

    /**
     * 컨텍스트 재생성 시 호출된다. FBO·텍스처·프로그램·SurfaceTexture를 모두 버린다 —
     * 하나라도 빠뜨리면 컨텍스트가 재생성될 때마다 GPU 메모리가 샌다.
     */
    private fun releaseGlResources() {
        // release()가 콜백까지 끊는다. setOnFrameAvailableListener(null)은 부르지 않는다 —
        // null 허용 여부를 확인하지 못한 시그니처를 쓸 이유가 없다.
        surfaceTexture?.release()
        surfaceTexture = null
        releaseOffscreen()
        // query 객체도 컨텍스트에 매달려 있다. 빠뜨리면 컨텍스트가 재생성될 때마다 샌다.
        gpuTimer.releaseGl()
        if (oesTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
            oesTextureId = 0
        }
        deleteProgram(oesProgram)
        deleteProgram(blitProgram)
        deleteProgram(gammaProgram)
        oesProgram = null
        blitProgram = null
        gammaProgram = null
    }

    private fun deleteProgram(program: QuadProgram?) {
        if (program != null && program.handle != 0) {
            GLES20.glDeleteProgram(program.handle)
        }
    }

    private fun createOesTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES20.glBindTexture(target, ids[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun buildProgram(vertexSource: String, fragmentSource: String): QuadProgram? {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (vertexShader == 0 || fragmentShader == 0) return null
        val handle = GLES20.glCreateProgram()
        GLES20.glAttachShader(handle, vertexShader)
        GLES20.glAttachShader(handle, fragmentShader)
        GLES20.glLinkProgram(handle)
        val status = IntArray(1)
        GLES20.glGetProgramiv(handle, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        if (status[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "프로그램 링크 실패: ${GLES20.glGetProgramInfoLog(handle)}")
            GLES20.glDeleteProgram(handle)
            return null
        }
        return QuadProgram(
            handle = handle,
            aPosition = GLES20.glGetAttribLocation(handle, "aPosition"),
            aTexCoord = GLES20.glGetAttribLocation(handle, "aTexCoord"),
            uTexture = GLES20.glGetUniformLocation(handle, "uTexture"),
            // 없는 uniform은 -1이 온다. 그 자체가 "이 프로그램에는 없다"는 뜻이라 따로
            // 플래그를 두지 않는다.
            uTexMatrix = GLES20.glGetUniformLocation(handle, "uTexMatrix"),
            uGamma = GLES20.glGetUniformLocation(handle, RenderArm.GAMMA_UNIFORM),
        )
    }

    private fun compileShader(type: Int, source: String): Int {
        val handle = GLES20.glCreateShader(type)
        GLES20.glShaderSource(handle, source)
        GLES20.glCompileShader(handle)
        val status = IntArray(1)
        GLES20.glGetShaderiv(handle, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "셰이더 컴파일 실패(type=$type): ${GLES20.glGetShaderInfoLog(handle)}")
            GLES20.glDeleteShader(handle)
            return 0
        }
        return handle
    }

    /** 링크된 프로그램 + 로케이션. 프레임당 조회를 하지 않기 위해 한 번만 담아 둔다. */
    private class QuadProgram(
        val handle: Int,
        val aPosition: Int,
        val aTexCoord: Int,
        val uTexture: Int,
        /** 이 프로그램에 없으면 -1. */
        val uTexMatrix: Int,
        /** 이 프로그램에 없으면 -1. */
        val uGamma: Int,
    )

    companion object {
        const val TAG = "PassthroughRenderer"

        /** 새 프레임이 없음을 뜻하는 센티넬. 스키마의 "없는 값 = -1"과 같은 뜻이다. */
        private const val NO_FRAME = -1L
        private const val MISSING = -1L

        private const val STRIDE_BYTES = 4 * 4

        /** FBO_A(패스1 출력) + FBO_B(패스2 출력). ②가 stateless라 2장이면 충분하다. */
        private const val FBO_COUNT = 2

        /** x, y, u, v — 화면 전체를 덮는 triangle strip 4정점. */
        private val VERTEX_DATA = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )

        /**
         * GLSL ES 1.00으로 쓴다. ES3 컨텍스트에서도 그대로 컴파일되므로, 확실히 도는 문법을
         * 쓰면서 ①②용 GLES 3.x 컨텍스트(`PIPELINE_STACK.md` §G)를 유지할 수 있다.
         *
         * ⚠ **패스1을 `#version 300 es`로 올리지 않는다.** 올리면 OES 샘플러에
         * `GL_OES_EGL_image_external_essl3`가 필요해져 기기 의존성이 는다(그 확장 유무는
         * `session.json`의 `gl` 블록에 실측으로 남는다).
         *
         * `trimIndent()`를 쓰는 이유: `#extension`을 열 0에 놓기 위해서다. 전처리기 지시문
         * 앞의 공백을 까다롭게 보는 드라이버가 있어 들여쓰기를 남기지 않는다.
         */
        private val VERTEX_SHADER_OES = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        /** 패스1(그리고 패스스루 arm) 프래그먼트. 샘플 하나를 그대로 출력한다. */
        private val FRAGMENT_SHADER_OES = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """.trimIndent()

        /**
         * 패스2·패스3용. OES 샘플러가 없으므로 `#version 300 es`로 올려도 되지만 올리지
         * 않는다 — 한 파일 안에서 셰이더 방언을 섞으면 나중에 ② 이식할 때 어느 쪽 문법인지
         * 매번 되물어야 한다. 텍스처 좌표 변환은 패스1에서 이미 끝났으므로 uTexMatrix가 없다.
         */
        private val VERTEX_SHADER_2D = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        /** 단순 복사. 패스3(표시)과 blit_2pass arm의 패스2가 같이 쓴다. */
        private val FRAGMENT_SHADER_BLIT = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """.trimIndent()

        /**
         * ② 자리의 **비용 하한**. 저조도 알고리즘이 아니다 — CLAHE는 다음 라운드다.
         * 지수는 상수로 박지 않고 uniform으로 받는다(`INTERFACES.md` §B-5).
         */
        private val FRAGMENT_SHADER_GAMMA = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            uniform float uGamma;
            void main() {
                vec4 c = texture2D(uTexture, vTexCoord);
                gl_FragColor = vec4(pow(c.rgb, vec3(uGamma)), c.a);
            }
        """.trimIndent()
    }
}
