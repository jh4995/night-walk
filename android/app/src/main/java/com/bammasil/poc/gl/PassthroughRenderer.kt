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
 * 표시 경로 2-C의 렌더러: 카메라가 채운 OES 텍스처를 화면에 **그대로 blit**한다.
 *
 * 셰이더 처리는 하나도 없다(①②는 이번 범위가 아니다). 오버레이도 없다.
 * 그래서 여기서 나오는 프레임타임은 **연산 비용이 아니라 카메라 공급 속도**이고,
 * 그 해석 단서는 하네스가 붙인다(`android-runtime` 스킬 §5).
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
 *   시간이 아니다.** "렌더가 사실상 무료"로 읽으면 틀린다. 실제 GPU 비용은 timer query가
 *   필요하고 이번 범위가 아니다. 같은 문장이 `session.json`에도 남는다.
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

    @Volatile
    private var surfaceTexture: SurfaceTexture? = null

    private var cameraSurface: Surface? = null

    private var oesTextureId = 0
    private var program = 0
    private var aPositionLoc = -1
    private var aTexCoordLoc = -1
    private var uTexMatrixLoc = -1
    private var uTextureLoc = -1

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
        // 컨텍스트가 재생성되면 이전 텍스처·SurfaceTexture는 죽은 것이다.
        releaseSurfaceTexture()
        program = buildProgram()
        if (program != 0) {
            aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
            aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
            uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
            uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture")
        }
        oesTextureId = createOesTexture()
        Matrix.setIdentityM(texMatrix, 0)
        pendingRecvNs.set(NO_FRAME)

        val created = SurfaceTexture(oesTextureId)
        created.setOnFrameAvailableListener(this, frameSignalHandler)
        surfaceTexture = created
        Log.i(TAG, "GL 준비 완료 (program=$program, oesTexture=$oesTextureId)")
        onGlReady?.invoke()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
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

        drawPassthrough()

        val tRenderEndNs = SystemClock.elapsedRealtimeNanos()
        if (hasNewFrame) {
            recorder.record(tRecvNs, tCaptureNs, tRenderStartNs, tRenderEndNs)
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

    // ── 측정 부수 정보 ───────────────────────────────────────────────────

    /** GL 스레드에서 부른다(표본을 소유한 스레드가 GL 스레드다). */
    fun clockVerdict(): CaptureClockVerdict = CaptureClockProbe.resolve(probeSamples.toList())

    /** 측정 시작 시 GL 스레드에서 부른다. */
    fun resetClockProbe() {
        probeSamples.clear()
    }

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

    private fun drawPassthrough() {
        if (program == 0) return
        // 타일 기반 GPU(Mali-G68)에서 clear를 생략하면 타일 버퍼를 이전 내용으로 채워 넣는
        // load 비용이 생긴다. 화면 전체를 덮는 quad라도 clear를 부르는 쪽이 싸다.
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glUniform1i(uTextureLoc, 0)
        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(
            aPositionLoc, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, vertexBuffer
        )
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        vertexBuffer.position(2)
        GLES20.glVertexAttribPointer(
            aTexCoordLoc, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, vertexBuffer
        )
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)

        // ⚠ glDrawArrays는 명령을 제출하고 **즉시 반환한다.** 따라서 t_render_end - t_render_start
        //   는 CPU 제출 비용이며 GPU 실행 시간이 아니다.
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun releaseSurfaceTexture() {
        // release()가 콜백까지 끊는다. setOnFrameAvailableListener(null)은 부르지 않는다 —
        // null 허용 여부를 확인하지 못한 시그니처를 쓸 이유가 없다.
        surfaceTexture?.release()
        surfaceTexture = null
        if (oesTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
            oesTextureId = 0
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
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

    private fun buildProgram(): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        if (vertexShader == 0 || fragmentShader == 0) return 0
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
            return 0
        }
        return handle
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

    companion object {
        const val TAG = "PassthroughRenderer"

        /** 새 프레임이 없음을 뜻하는 센티넬. 스키마의 "없는 값 = -1"과 같은 뜻이다. */
        private const val NO_FRAME = -1L
        private const val MISSING = -1L

        private const val STRIDE_BYTES = 4 * 4

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
         * `trimIndent()`를 쓰는 이유: `#extension`을 열 0에 놓기 위해서다. 전처리기 지시문
         * 앞의 공백을 까다롭게 보는 드라이버가 있어 들여쓰기를 남기지 않는다.
         */
        private val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        /** 패스스루 1개. 처리는 없다 — 샘플 하나를 그대로 출력한다. */
        private val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """.trimIndent()
    }
}
