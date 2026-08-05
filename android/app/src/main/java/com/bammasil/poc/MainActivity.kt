package com.bammasil.poc

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.bammasil.poc.detect.DetectRuntime
import com.bammasil.poc.gl.PassthroughRenderer
import com.bammasil.poc.gl.RenderArm
import com.bammasil.poc.log.FrameLogRecorder
import com.bammasil.poc.log.LightingCondition
import com.bammasil.poc.log.SessionFacts
import com.bammasil.poc.log.SessionWriter
import com.bammasil.poc.source.CameraFrameSource
import com.bammasil.poc.source.FrameRequest
import com.bammasil.poc.source.FrameSource
import com.bammasil.poc.source.NegotiatedConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 빈 파이프라인 PoC: 카메라 → (처리 없음) → 화면.
 *
 * 이 앱이 남기는 숫자가 전체 프레임 버짓의 **분모**다(`KICKOFF_ROLES.md`).
 * 판정은 하지 않는다 — 판정선 숫자는 `FRAME_BUDGET.md` §1과 `lib/targets.py`에만 살고,
 * PASS/FAIL은 PC 하네스가 낸다. 폰은 타임스탬프와 조건만 정직하게 남긴다.
 */
class MainActivity : ComponentActivity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: PassthroughRenderer
    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button
    private lateinit var lightingSpinner: Spinner
    private lateinit var armSpinner: Spinner
    private lateinit var frameSource: FrameSource

    private val recorder = FrameLogRecorder()
    private val uiHandler = Handler(Looper.getMainLooper())

    /**
     * ③ 탐지 세션. **③ arm을 고를 때만** 준비하고, 준비가 안 됐거나 실패했으면
     * [startRecording]이 **런을 거부한다** — 조용히 탐지 없이 도는 경로를 만들지 않는다.
     */
    private lateinit var detectRuntime: DetectRuntime

    /** `onFrameAvailable` 전용 스레드. 메인 루퍼를 쓰면 UI 지연이 `t_recv_ns`에 섞인다. */
    private var signalThread: HandlerThread? = null

    private var glReady = false
    private var sourceStarted = false
    private var recording = false

    /**
     * **측정 시작 시점에 잠근 arm.** 스피너의 현재 값을 쓰면, 어쩌다 런 도중 바뀌었을 때
     * session.json이 실제로 돈 경로와 다른 arm을 적게 된다.
     */
    private var armAtStart: RenderArm = RenderArm.DEFAULT

    /** 이 런의 출력 디렉토리 이름. 측정 **시작 시각**으로 정한다. */
    private var runDirName: String? = null

    /** 마지막으로 쓴 런 디렉토리 경로. GL 스레드가 쓰고 UI가 읽는다. */
    @Volatile
    private var lastRunPath: String? = null

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startSourceIfReady()
        } else {
            showMessage("카메라 권한이 없으면 측정할 수 없다")
        }
    }

    private val statusTicker = object : Runnable {
        override fun run() {
            updateStatus()
            uiHandler.postDelayed(this, STATUS_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // 없으면 10분 지속 런이 화면 꺼짐으로 중단된다.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // ③ 모델은 adb push로 외부 파일 디렉토리에 들어온다(APK 동봉이 아니다).
        // 프로파일 JSON은 cacheDir에 쓴다 — 런 산출물이 아니라 판별용 원문이다.
        // ⚠ **arm 스피너 리스너보다 먼저** 만든다. 스피너는 붙는 즉시 선택 콜백을 내고,
        //   그 콜백이 ③ arm이면 여기를 만진다.
        detectRuntime = DetectRuntime(getExternalFilesDir(null), cacheDir)

        statusText = findViewById(R.id.status_text)
        toggleButton = findViewById(R.id.toggle_button)
        lightingSpinner = findViewById(R.id.lighting_spinner)
        armSpinner = findViewById(R.id.arm_spinner)
        glView = findViewById(R.id.gl_view)

        val thread = HandlerThread("frame-signal").apply { start() }
        signalThread = thread
        renderer = PassthroughRenderer(recorder, Handler(thread.looper))
        renderer.onFrameSignal = { glView.requestRender() }
        renderer.onGlReady = { uiHandler.post { onGlReady() } }

        glView.setEGLContextClientVersion(EGL_CONTEXT_CLIENT_VERSION)
        glView.preserveEGLContextOnPause = true
        glView.setRenderer(renderer)
        // 카메라가 프레임을 줄 때만 그린다. vsync마다 그리면 카메라 공급 속도를 재는 것이
        // 아니라 디스플레이 주기를 재게 된다.
        glView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        lightingSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            LightingCondition.CHOICES,
        )

        // arm은 조명과 같은 급의 **측정 조건**이다 → 같은 실패 방지 패턴을 쓴다:
        // 어휘 고정 목록 + 측정 중 잠금 + session.json에 기록.
        armSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            RenderArm.CHOICES,
        )
        armSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                // GL 자원을 만지므로 GL 스레드에서 바꾼다.
                val arm = RenderArm.fromId(armSpinner.getItemAtPosition(position)?.toString())
                glView.queueEvent { renderer.setArm(arm) }
                prepareDetectIfNeeded(arm)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        toggleButton.setOnClickListener {
            if (recording) stopRecording() else startRecording()
        }

        frameSource = CameraFrameSource(this, this)
        ensureCameraPermission()
        uiHandler.post(statusTicker)
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
    }

    override fun onPause() {
        // 측정 중 앱이 내려가면 프레임 공급이 끊긴다. 그대로 이어 쓰면 공백이 섞인 분포가
        // 되므로 여기서 정지·flush한다(로그를 잃지 않는 쪽이 아니라, 거짓 분포를 만들지
        // 않는 쪽이 목적이다).
        if (recording) stopRecording()
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        frameSource.stop()
        uiHandler.removeCallbacks(statusTicker)
        signalThread?.quitSafely()
        signalThread = null
        // ORT 세션은 네이티브 메모리를 잡고 있다. 프로세스가 살아 있는 채로 Activity만
        // 재생성되면 새 세션이 또 열리므로 여기서 닫는다.
        detectRuntime.shutdown()
        super.onDestroy()
    }

    // ── ③ 탐지 준비 ─────────────────────────────────────────────────────

    /**
     * ③ arm을 골랐으면 ORT 세션을 **미리** 준비한다. 세션 생성은 수백 ms~수 초가 걸리므로
     * 런 시작 시점에 하면 그 비용이 측정 창 안으로 들어온다.
     *
     * ⚠ [DetectRuntime]이 자기 워커 스레드에서 돈다 — 여기서 블록하지 않는다.
     */
    private fun prepareDetectIfNeeded(arm: RenderArm) {
        if (!arm.usesDetectSession) return
        detectRuntime.prepareAsync(arm) { report ->
            uiHandler.post {
                // 🔴 실패를 조용히 넘기지 않는다. 여기서 안 보이면 측정자는 런을 시작하려다
                //    거부당하고 나서야 이유를 알게 된다.
                showMessage(report.oneLine())
                updateStatus()
            }
        }
    }

    /**
     * ③ arm의 런을 **거부해야 하는 이유**. 없으면 null.
     *
     * 🔴 이 게이트가 이 라운드의 안전장치다. 모델을 안 밀었거나 sha256이 어긋났거나 EP 준비가
     * 실패한 상태로 런이 돌면, `render_arm=detect_*`라는 라벨만 붙은 **탐지 없는 런**이
     * 남는다 — 이 저장소가 실제로 당한 실패 양식이다(조용한 폴백으로 11분 런 하나를 날렸다).
     */
    private fun detectGateMessage(arm: RenderArm): String? {
        if (!arm.isDetectArm) return null
        if (arm == RenderArm.DETECT_BIND_ONLY) {
            return "🔴 detect_bind_only는 아직 못 쓴다 — ImageAnalysis가 이번 라운드에 " +
                "붙지 않았다. 지금 재면 라벨은 detect_bind_only인데 실제로 도는 것은 " +
                "3패스 골격이라 그 런은 거짓이다(다음 라운드)"
        }
        if (detectRuntime.preparing) {
            return "③ ONNX Runtime 세션을 준비 중이다 — 끝난 뒤 다시 시작할 것"
        }
        val report = detectRuntime.report
            ?: return "③ 세션이 준비되지 않았다 — arm 스피너에서 이 arm을 다시 고를 것"
        if (report.arm != arm) {
            return "③ 준비된 세션의 arm이 다르다 (준비=${report.arm.id}, 선택=${arm.id}) " +
                "— arm을 다시 고를 것"
        }
        if (!report.ok) {
            return "🔴 ③ 준비 실패라 런을 시작하지 않는다:\n${report.failure}"
        }
        return null
    }

    // ── 소스 준비 ────────────────────────────────────────────────────────

    private fun onGlReady() {
        glReady = true
        // GL 컨텍스트가 재생성되면 이전 SurfaceTexture는 죽은 것이다 → 소스를 다시 바인딩한다.
        if (sourceStarted) {
            frameSource.stop()
            sourceStarted = false
        }
        startSourceIfReady()
    }

    private fun startSourceIfReady() {
        if (!glReady || sourceStarted) return
        if (!hasCameraPermission()) return
        sourceStarted = true
        frameSource.start(FRAME_REQUEST, renderer) { message ->
            uiHandler.post { showMessage(message) }
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureCameraPermission() {
        if (hasCameraPermission()) return
        requestCameraPermission.launch(Manifest.permission.CAMERA)
    }

    // ── 측정 시작/정지 ───────────────────────────────────────────────────

    private fun startRecording() {
        if (!sourceStarted) {
            showMessage("카메라가 아직 준비되지 않았다 — 권한과 프리뷰를 먼저 확인할 것")
            return
        }
        val arm = selectedArm()
        // 🔴 ③ arm의 전제가 안 서면 **런을 시작하지 않는다.** 라벨만 detect_*인 탐지 없는
        //    런을 만드는 것이 이 라운드가 막으려는 실패다.
        detectGateMessage(arm)?.let {
            showMessage(it)
            return
        }
        recording = true
        toggleButton.setText(R.string.stop)
        // 런 도중 조건이 바뀌면 그 분포는 오염된 것이다 → 둘 다 잠근다.
        lightingSpinner.isEnabled = false
        armSpinner.isEnabled = false
        armAtStart = arm
        runDirName = newRunDirName()
        val startedNs = SystemClock.elapsedRealtimeNanos()
        glView.queueEvent {
            // 스피너 콜백을 놓쳤을 가능성을 여기서 닫는다. 이 시점 이후로 arm은 고정이다.
            renderer.setArm(arm)
            renderer.resetClockProbe()
            renderer.resetRenderCounters()
            // GPU 패스 시간 칸의 개수와 이름은 **arm이 정한다**(RenderArm.gpuColumns).
            // 패스스루는 계측하지 않으므로 목록이 비어 있고 CSV 열도 없다. 실제로 열을
            // 실을지는 정지 시점에 timer 실적을 보고 정한다(프로브가 실패했으면 안 싣는다).
            recorder.start(startedNs, gpuColumns = arm.gpuColumns)
        }
        Log.i(TAG, "측정 시작 (arm=${arm.id}, run=${runDirName})")
    }

    private fun stopRecording() {
        if (!recording) return
        recording = false
        // 기록을 먼저 멈춘 뒤 파일 쓰기를 GL 스레드에 태운다. 측정 중 파일 I/O를 피하려고
        // 메모리에 모아 둔 것이므로, 쓰는 시점은 반드시 기록이 끝난 다음이어야 한다.
        recorder.stop(SystemClock.elapsedRealtimeNanos())
        toggleButton.setText(R.string.start)
        toggleButton.isEnabled = false

        val outDir = getExternalFilesDir(null)
        val lighting = lightingSpinner.selectedItem?.toString() ?: LightingCondition.UNKNOWN
        val negotiated = frameSource.negotiated
        val sourceKind = frameSource.kind
        val arm = armAtStart
        val runName = runDirName ?: newRunDirName()
        glView.queueEvent {
            val message = writeLogs(outDir, runName, lighting, arm, negotiated, sourceKind)
            uiHandler.post {
                toggleButton.isEnabled = true
                lightingSpinner.isEnabled = true
                armSpinner.isEnabled = true
                showMessage(message)
                updateStatus()
            }
        }
    }

    /** GL 스레드에서 실행된다(로그 버퍼와 시계 표본을 소유한 스레드가 GL 스레드다). */
    private fun writeLogs(
        dir: File?,
        runName: String,
        lighting: String,
        arm: RenderArm,
        negotiated: NegotiatedConfig?,
        sourceKind: String,
    ): String {
        if (dir == null) {
            return "getExternalFilesDir(null)이 null이다 — 로그를 쓰지 못했다"
        }
        return try {
            // ⚠ 런별 디렉토리. 예전처럼 파일명이 고정이면 FileWriter가 truncate라
            //   **두 번째 런이 첫 번째를 지운다** — PC 없이 연속 2런을 못 찍던 원인이다.
            val runDir = resolveRunDir(dir, runName)
            if (!runDir.isDirectory && !runDir.mkdirs()) {
                return "런 디렉토리를 만들지 못했다: ${runDir.absolutePath}"
            }
            val framesFile = File(runDir, "frames.csv")
            val sessionFile = File(runDir, "session.json")
            // ⚠ CSV를 쓰기 **전에** 링을 마감한다. 여기서 마지막 비차단 폴링이 돌아
            //   회수 가능한 tail을 행에 채우고, 남은 미해소 개수를 확정한다.
            val gpuTimer = renderer.finishGpuTimerRun()
            val rows = recorder.writeCsv(framesFile, gpuTimer.instrumented)
            SessionWriter.write(
                sessionFile,
                SessionFacts(
                    // ⚠ 문자열 리터럴 "release"를 쓰지 않는다. debug 빌드가 release라고
                    //   거짓말할 경로를 만들면 그 숫자는 근거로 쓸 수 없게 된다.
                    buildType = BuildConfig.BUILD_TYPE,
                    versionName = BuildConfig.VERSION_NAME,
                    // 로그 → APK 연결고리. versionName은 커밋이 바뀌어도 안 변한다.
                    gitCommit = BuildConfig.GIT_COMMIT,
                    gitDirty = BuildConfig.GIT_DIRTY,
                    lightingCondition = lighting,
                    arm = arm,
                    request = FRAME_REQUEST,
                    negotiated = negotiated,
                    sourceKind = sourceKind,
                    framesEmitted = rows,
                    surfaceFramesAvailable = recorder.surfaceFramesAvailable.get(),
                    drawsWithoutNewFrame = recorder.drawsWithoutNewFrame,
                    startedElapsedNs = recorder.startedElapsedNs,
                    stoppedElapsedNs = recorder.stoppedElapsedNs,
                    clock = renderer.clockVerdict(),
                    glSurfaceWidth = renderer.surfaceWidth,
                    glSurfaceHeight = renderer.surfaceHeight,
                    eglContextClientVersion = EGL_CONTEXT_CLIENT_VERSION,
                    gl = renderer.capabilities,
                    processWidth = renderer.processWidth,
                    processHeight = renderer.processHeight,
                    offscreenStatus = renderer.offscreenStatus,
                    offscreenFallbackDraws = renderer.offscreenFallbackDraws,
                    stage2Status = renderer.stage2Status,
                    // ④ 오버레이 자원 상태는 ②와 **따로** 싣는다(한 문장에 섞으면 실패가 묻힌다).
                    overlayStatus = renderer.overlayStatus,
                    colorTransformSites = renderer.colorTransformSites,
                    gpuTimer = gpuTimer,
                    // ③ arm이 아니면 null이고 그때는 detect 블록 자체가 안 나간다.
                    // ⚠ **런 시작 시점에 잠근 arm의 보고여야 한다** — 다른 arm의 보고가
                    //   실리면 EP·모델이 이 런의 것이 아니게 된다(armAtStart를 잠근 것과
                    //   같은 이유다). detectGateMessage가 시작 시점에 그 일치를 확인했다.
                    detect = detectRuntime.report?.takeIf { it.arm == arm },
                ),
            )
            lastRunPath = runDir.absolutePath
            Log.i(TAG, "로그 저장: ${framesFile.absolutePath} ($rows 행, arm=${arm.id})")
            // 스모크 확인용. 인용 가능한 숫자는 여전히 파일에 남은 것뿐이다.
            Log.i(
                TAG,
                "GPU timer: instrumented=${gpuTimer.instrumented} " +
                    "supported=${gpuTimer.supported} " +
                    "frames=${gpuTimer.instrumentedFrames} resolved=${gpuTimer.resolvedFrames} " +
                    "positive=${gpuTimer.positiveSamples} zero=${gpuTimer.zeroResultQueries} " +
                    "disjoint=${gpuTimer.disjointFrames} " +
                    "unresolved=${gpuTimer.unresolvedQueries} " +
                    "ringFull=${gpuTimer.skippedRingFullFrames} " +
                    "beginErr=${gpuTimer.beginQueryError} off=${gpuTimer.disabledReason}"
            )
            "저장 완료: $rows 행 (arm=${arm.id})\n→ ${runDir.absolutePath}"
        } catch (t: Throwable) {
            Log.e(TAG, "로그 저장 실패", t)
            "로그 저장 실패: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    /**
     * `<외부 파일 디렉토리>/runs/<YYYYMMDD_HHMMSS>/`.
     * 이름은 **측정 시작 시각**에서 온다(정지 시각이 아니다). 같은 초에 두 번 시작하면
     * 뒤 런이 앞 런을 덮어쓰므로 접미사를 붙인다 — 이게 없으면 PC 없이 연속 2런을 못 찍는다.
     */
    private fun resolveRunDir(baseDir: File, runName: String): File {
        val runsRoot = File(baseDir, RUNS_DIR)
        var candidate = File(runsRoot, runName)
        var suffix = 2
        while (candidate.exists()) {
            candidate = File(runsRoot, "${runName}_$suffix")
            suffix++
        }
        return candidate
    }

    /**
     * 로컬 벽시계 기준 타임스탬프. **사전순 = 시간순**이 되는 형식이라 "가장 최근"이
     * 모호하지 않다(하네스의 `run_ts`와 같은 형식이다).
     */
    private fun newRunDirName(): String =
        SimpleDateFormat(RUN_DIR_PATTERN, Locale.US).format(Date())

    private fun selectedArm(): RenderArm =
        RenderArm.fromId(armSpinner.selectedItem?.toString())

    // ── 화면 표시 (진행 확인용) ──────────────────────────────────────────

    private fun updateStatus() {
        val negotiated = frameSource.negotiated
        val actual = if (negotiated == null) {
            "미확정"
        } else {
            "${negotiated.width}x${negotiated.height}"
        }
        val armNow = if (recording) armAtStart else selectedArm()
        val arm = armNow.id
        val head = if (recording) {
            val elapsedSec =
                (SystemClock.elapsedRealtimeNanos() - recorder.startedElapsedNs) / 1e9
            "측정 중 %.1fs | 프레임 %d | arm %s | 실제 해상도 %s".format(
                elapsedSec, recorder.recordedFrames, arm, actual
            )
        } else {
            "대기 중 | 마지막 기록 %d 프레임 | arm %s | 실제 해상도 %s".format(
                recorder.recordedFrames, arm, actual
            )
        }
        // 어느 런을 찍었는지 사용자가 알아야 한다(런별 디렉토리라 이름이 매번 다르다).
        val saved = lastRunPath?.let { "\n마지막 저장: $it" } ?: ""
        // ③ arm이면 EP 판별 결과를 화면에도 낸다 — PC 없이 현장에서 "NNAPI가 잡혔나"를
        // 바로 봐야 한다. ⚠ 이것도 진행 확인용이며 인용 근거는 session.json이다.
        val detectLine = when {
            !armNow.isDetectArm -> ""
            detectRuntime.preparing -> "\n③ 준비 중…"
            else -> detectRuntime.report?.let { "\n${it.oneLine()}" } ?: "\n③ 준비 안 됨"
        }
        // ⚠ 화면 숫자는 인용 근거가 아니다. 인용 가능한 숫자는 파일로 남긴 것뿐이다.
        statusText.text =
            head + detectLine + saved +
                "\n(진행 확인용 — 인용은 frames.csv / session.json 으로만)"
    }

    private fun showMessage(message: String) {
        Log.i(TAG, message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val TAG = "BammasilPoc"
        const val STATUS_INTERVAL_MS = 500L

        /** 런별 출력 레이아웃: `.../files/runs/<YYYYMMDD_HHMMSS>/{frames.csv,session.json}` */
        const val RUNS_DIR = "runs"
        const val RUN_DIR_PATTERN = "yyyyMMdd_HHmmss"

        /** ①②는 GLES 3.x 셰이더 전제(`PIPELINE_STACK.md` §G)라 컨텍스트를 미리 맞춰 둔다. */
        const val EGL_CONTEXT_CLIENT_VERSION = 3

        /** **요청값.** 실제로 받은 값은 `session.json`의 `camera_actual`에 따로 남는다. */
        val FRAME_REQUEST = FrameRequest(width = 1280, height = 720, fps = 30)
    }
}
