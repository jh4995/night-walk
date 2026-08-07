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
import com.bammasil.poc.detect.DetectContract
import com.bammasil.poc.detect.DetectOverlayPublisher
import com.bammasil.poc.detect.DetectParityDumper
import com.bammasil.poc.detect.DetectPipeline
import com.bammasil.poc.detect.DetectRuntime
import com.bammasil.poc.gl.PassthroughRenderer
import com.bammasil.poc.gl.RenderArm
import com.bammasil.poc.log.DetectLogRecorder
import com.bammasil.poc.log.DetectRunFacts
import com.bammasil.poc.log.FrameLogRecorder
import com.bammasil.poc.log.LightingCondition
import com.bammasil.poc.log.SessionFacts
import com.bammasil.poc.log.SessionWriter
import com.bammasil.poc.source.AnalysisConfig
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

    /**
     * ③ 탐지를 프레임 경로에 붙이는 쪽. `ImageAnalysis` 프레임을 받아 E·F·G를 재고
     * `detect.csv`를 채운다. **③ arm이 아니면 `ImageAnalysis` 자체가 안 붙으므로** 이
     * 객체는 있어도 아무 프레임도 받지 않는다.
     */
    private lateinit var detectPipeline: DetectPipeline

    /** 🔴 [FrameLogRecorder]와 **다른 인스턴스다** — 그쪽 규약은 GL 스레드 전용이다. */
    private val detectRecorder = DetectLogRecorder()

    /**
     * ③ 이식 정확성 대조 덤프. **`detect_parity_*` arm에서만** 파일을 남긴다.
     * 런 도중에는 staging에 쌓고, 정지 시점에 런 디렉토리의 `parity/`로 옮긴다 —
     * 런 디렉토리 이름은 [resolveRunDir]가 정지 시점에야 확정하기 때문이다.
     */
    private lateinit var detectParityDumper: DetectParityDumper

    /**
     * ③ → ④ 게시자. **탐지 워커가 쓰고 GL 스레드가 읽는다**(`AtomicReference` 슬롯 하나).
     * 🔴 오버레이 arm이 아니면 꺼져 있어 두 스레드 중 어느 쪽에도 일이 늘지 않는다.
     * ⚠ [detectPipeline]과 [renderer] **둘 다** 이 객체를 받으므로 여기서 먼저 만든다.
     */
    private val detectOverlayPublisher =
        DetectOverlayPublisher(RenderArm.OVERLAY_BOX_CAP_MEASUREMENT_VALUE)

    /** `onFrameAvailable` 전용 스레드. 메인 루퍼를 쓰면 UI 지연이 `t_recv_ns`에 섞인다. */
    private var signalThread: HandlerThread? = null

    private var glReady = false
    private var sourceStarted = false
    private var recording = false

    /**
     * 지금 바인딩돼 있는 소스에 **분석 use case가 붙어 있는가.** arm이 ③ 계열로 바뀌면
     * 이 값이 desired와 어긋나고, 그때만 카메라를 다시 바인딩한다 — 매번 다시 바인딩하면
     * arm을 고를 때마다 프리뷰가 끊기고, 안 하면 use case가 조용히 안 붙는다.
     */
    private var sourceAnalysisBound = false

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
        // 🔴 프로파일 JSON은 **외부 파일 디렉토리**에 쓴다. 예전엔 cacheDir(내부)라
        //    `/data/user/0/...`에 떨어져 **루트 없이 못 꺼냈다** — `_prof` arm의 유일한
        //    산출물인데 회수가 안 되면 그 arm이 아무 답도 못 낸다. 런 정지 시점에
        //    `DetectRuntime.collectProfiles`가 런 디렉토리로 옮긴다.
        // ⚠ **arm 스피너 리스너보다 먼저** 만든다. 스피너는 붙는 즉시 선택 콜백을 내고,
        //   그 콜백이 ③ arm이면 여기를 만진다.
        val externalDir = getExternalFilesDir(null)
        detectRuntime = DetectRuntime(externalDir, File(externalDir, DETECT_PROFILE_DIR))
        detectParityDumper = DetectParityDumper(File(externalDir, DETECT_PARITY_STAGING_DIR))
        detectPipeline = DetectPipeline(
            detectRuntime, detectRecorder, detectParityDumper, detectOverlayPublisher
        )

        statusText = findViewById(R.id.status_text)
        toggleButton = findViewById(R.id.toggle_button)
        lightingSpinner = findViewById(R.id.lighting_spinner)
        armSpinner = findViewById(R.id.arm_spinner)
        glView = findViewById(R.id.gl_view)

        val thread = HandlerThread("frame-signal").apply { start() }
        signalThread = thread
        renderer = PassthroughRenderer(
            recorder, Handler(thread.looper), detectOverlayPublisher
        )
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
                rebindSourceIfAnalysisChanged(arm)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        toggleButton.setOnClickListener {
            if (recording) stopRecording() else startRecording()
        }

        frameSource = CameraFrameSource(this, this, detectPipeline)
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
        detectPipeline.shutdown()
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
            // 분모 arm이다 — ORT 세션을 열지 않으므로 준비 게이트가 없다. 다만
            // ImageAnalysis가 실제로 붙어 있어야 라벨이 참이 된다.
            return if (sourceAnalysisBound) {
                null
            } else {
                "🔴 분석 use case가 아직 안 붙었다 — arm을 다시 고른 뒤 프리뷰가 " +
                    "돌아오면 시작할 것(라벨만 detect_bind_only인 런을 만들지 않는다)"
            }
        }
        if (!sourceAnalysisBound) {
            return "🔴 분석 use case가 아직 안 붙었다 — 탐지가 프레임을 받지 못한다. " +
                "arm을 다시 고른 뒤 프리뷰가 돌아오면 시작할 것"
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
        // 🔴 후처리 임계를 숫자로 못 읽으면 시작하지 않는다. 0으로 뭉개면 **전량 통과하는
        //    임계**가 되어 그 런의 G는 다른 것을 잰 숫자가 된다.
        DetectContract.thresholdFailure?.let { return it }
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
        // 🔴 ③ arm일 때만 분석 use case를 붙인다. 다른 arm에 붙여 두면 그 arm의 프레임타임에
        //    use case 하나의 비용이 섞여 승격본 45건과의 비교가 끊긴다.
        val bindAnalysis = selectedArm().isDetectArm
        sourceAnalysisBound = bindAnalysis
        frameSource.start(FRAME_REQUEST, renderer, bindAnalysis) { message ->
            uiHandler.post { showMessage(message) }
        }
    }

    /**
     * arm이 바뀌어 분석 use case의 유무가 달라지면 소스를 다시 바인딩한다.
     * **측정 중에는 부르지 않는다** — 스피너가 잠겨 있으므로 실제로 그럴 일이 없다.
     */
    private fun rebindSourceIfAnalysisChanged(arm: RenderArm) {
        if (!sourceStarted) return
        if (recording) return
        if (arm.isDetectArm == sourceAnalysisBound) return
        frameSource.stop()
        sourceStarted = false
        startSourceIfReady()
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
        // ③ 추론을 돌리는 arm이면 탐지 기록도 함께 켠다. 🔴 **여기서 실패하면 런을 시작하지
        //    않는다** — 라벨은 detect_cpu인데 detect.csv가 비는 런을 만들지 않기 위해서다.
        if (arm.usesDetectSession) {
            detectPipeline.start(arm)?.let {
                showMessage("🔴 ③ 탐지 기록을 시작하지 못했다:\n$it")
                return
            }
        } else if (arm == RenderArm.DETECT_BIND_ONLY) {
            // 분모 arm — 추론은 안 하고 **분석 프레임이 실제로 오고 있다는 사실만** 센다.
            detectPipeline.startBindOnly()
        } else {
            // 🔴 **세 번째 경로.** ③도 분모도 아닌 arm(highlight_boxes · blit_2pass ·
            //    passthrough …)에서는 위 두 함수가 불리지 않아 직전 detect 런의 게시자가
            //    켜진 채 남는다. 지금은 GL 읽기가 arm으로 게이트돼 관측되지 않지만,
            //    런 단위 상태는 런 시작에서 내린다는 관행을 이 경로에도 적용한다.
            detectPipeline.disableOverlayPublish()
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
            // ④ 오버레이 열 3개도 같은 규약이다 — 🔴 **③ 결과를 그리는 arm에서만** 싣는다
            // (정적 더미 arm에 -1로 채워 내면 "쟀는데 못 얻었다"가 된다).
            recorder.start(
                startedNs,
                gpuColumns = arm.gpuColumns,
                overlayColumns = arm.usesDynamicHighlightBoxes,
            )
        }
        Log.i(TAG, "측정 시작 (arm=${arm.id}, run=${runDirName})")
    }

    /**
     * A12 정지 순서. 🔴 **순서를 바꾸면 마지막 행이 찢기거나 회계가 안 닫힌다.**
     *
     * ```
     * (1) 기록 플래그 off  ← 여기(UI 스레드)
     * (2) 탐지 스레드 quiesce (진행 중 추론 완료 대기, 타임아웃)  ┐
     * (3) frames.csv(GL) + detect.csv 쓰기                        ├ writeLogs (GL 스레드)
     * (4) session.json                                            ┘
     * ```
     */
    private fun stopRecording() {
        if (!recording) return
        recording = false
        // 기록을 먼저 멈춘 뒤 파일 쓰기를 GL 스레드에 태운다. 측정 중 파일 I/O를 피하려고
        // 메모리에 모아 둔 것이므로, 쓰는 시점은 반드시 기록이 끝난 다음이어야 한다.
        recorder.stop(SystemClock.elapsedRealtimeNanos())
        // (1) 탐지 쪽도 같은 시점에 내린다. 여기 이후로 새 추론은 시작되지 않는다.
        detectPipeline.stopRecording()
        toggleButton.setText(R.string.start)
        toggleButton.isEnabled = false

        val outDir = getExternalFilesDir(null)
        val lighting = lightingSpinner.selectedItem?.toString() ?: LightingCondition.UNKNOWN
        val negotiated = frameSource.negotiated
        val analysis = frameSource.analysisConfig
        val sourceKind = frameSource.kind
        val arm = armAtStart
        val runName = runDirName ?: newRunDirName()
        glView.queueEvent {
            val message = writeLogs(outDir, runName, lighting, arm, negotiated, analysis, sourceKind)
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
        analysis: AnalysisConfig?,
        sourceKind: String,
    ): String {
        if (dir == null) {
            return "getExternalFilesDir(null)이 null이다 — 로그를 쓰지 못했다"
        }
        // A12 (2) — 🔴 **파일을 쓰기 전에** 탐지 스레드를 조용히 만든다. 여기서 기다리는
        //   시간은 기록이 이미 멈춘 뒤라 측정에 섞이지 않는다.
        val quiesced = if (arm.usesDetectSession) {
            detectPipeline.quiesce(DETECT_QUIESCE_TIMEOUT_MS)
        } else {
            true
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
            // A12 (3) — detect.csv도 여기서 쓴다. 행이 0개면 파일을 만들지 않는다
            // (빈 CSV는 read_detect가 죽는 입력이고, "한 번도 안 돌았다"는 사실은
            //  아래 회계 블록이 말한다).
            val detectRows = if (arm.usesDetectSession) {
                detectRecorder.writeCsv(File(runDir, "detect.csv"))
            } else {
                0
            }
            // 🔴 **보고를 먼저 붙잡는다.** 아래 collectProfiles는 `_prof` arm에서 세션을
            //    닫고 report를 비운다(프로파일은 한 번만 확정할 수 있다) — 순서를 뒤집으면
            //    그 arm의 session.json에서 detect 블록이 통째로 사라진다.
            // ⚠ **런 시작 시점에 잠근 arm의 보고여야 한다** — 다른 arm의 보고가 실리면
            //    EP·모델이 이 런의 것이 아니게 된다(armAtStart를 잠근 것과 같은 이유다).
            val detectReport = detectRuntime.report?.takeIf { it.arm == arm }
            // ③ 대조 덤프를 런 디렉토리로 옮기고 매니페스트를 확정한다. 🔴 **quiesce 뒤이자
            //    collectProfiles 앞이다** — 워커가 아직 파일을 쓰고 있으면 샘플이 반쪽이 되고,
            //    collectProfiles는 `_prof` arm에서 report를 비우므로 그 뒤에 부르면 매니페스트에
            //    실을 모델·EP 값이 사라진다. 덤프 arm이 아니면 null이다.
            // 🔴 회전 사실은 **한 곳에서만** 만든다 — 매니페스트(parity.json의 source)와
            //    session.json이 같은 객체를 쓴다. 두 곳이 각자 계산하면 갈리는 날이 온다.
            val rotationFacts = detectPipeline.rotationFacts()
            val parityResult = detectParityDumper.finish(runDir, detectReport, rotationFacts)
            // `_prof` arm의 Chrome-trace JSON을 런 디렉토리로 옮긴다 — 예전에는 내부 캐시라
            // 루트 없이 못 꺼냈다.
            // ⚠ **세션을 연 arm에서만** 한다. 분모 arm에서 부르면 직전에 다른 arm이 남긴
            //   프로파일이 이 런 디렉토리로 딸려 들어가 다른 런의 증거가 섞인다.
            val profiles = if (arm.usesDetectSession) {
                detectRuntime.collectProfiles(runDir, arm)
            } else {
                emptyList()
            }
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
                    // ③ 분석 use case가 실제로 물어온 조건. Preview 값과 **섞지 않는다**.
                    analysis = analysis,
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
                    // detectGateMessage가 시작 시점에 arm 일치를 확인했다.
                    detect = detectReport,
                    // 프레임 경로에서 실제로 무슨 일이 있었나. 🔴 회계 불변식이 여기 있다.
                    detectRun = if (arm.isDetectArm) {
                        DetectRunFacts(
                            csvRows = detectRows,
                            analysisFramesReceived = detectPipeline.analysisFramesReceived.get(),
                            inferencesRun = detectPipeline.inferencesRun.get(),
                            skippedWhileBusy = detectPipeline.skippedWhileBusy.get(),
                            errors = detectPipeline.errors.get(),
                            lastError = detectPipeline.lastError,
                            inferenceEnabled = detectPipeline.inferenceWasEnabled,
                            quiesced = quiesced,
                            quiesceTimeoutMs = DETECT_QUIESCE_TIMEOUT_MS,
                            letterbox = detectPipeline.lastLetterbox,
                            profileFiles = profiles,
                            // 🔴 **거른 개수가 아니라 센 개수다**(규약 §5-3). quiesce 뒤라
                            //    마지막 추론까지 반영돼 있다.
                            invertedBoxes = detectPipeline.invertedBoxesTotal,
                            invertedSamples = detectPipeline.invertedBoxSamples,
                        )
                    } else {
                        null
                    },
                    // ③ 대조 덤프의 사실. 덤프 arm이 아니면 null이고 그때는 블록 자체가 없다.
                    detectParity = parityResult,
                    // ③ 회전의 사실(규약 §4). 세션을 여는 arm이 아니면 null이다 —
                    // 분모 arm(detect_bind_only)은 전처리를 돌리지 않으므로 회전 사실이 없다.
                    detectRotation = if (arm.usesDetectSession) rotationFacts else null,
                    detectGeometry = if (arm.usesDetectSession) {
                        detectPipeline.geometryCheck
                    } else {
                        null
                    },
                    // ③→④ 연결 arm만 낸다(다른 블록들과 같은 규약).
                    // 🔴 게시 사실은 **quiesce 뒤에** 뜬다 — 위에서 이미 기다렸으므로 마지막
                    //    추론의 게시까지 반영돼 있다. 앞에서 뜨면 그 게시가 빠진다.
                    overlayPublish = if (arm.usesDynamicHighlightBoxes) {
                        detectOverlayPublisher.facts()
                    } else {
                        null
                    },
                    // H칸 사실은 GL 스레드가 소유한다 — 이 블록이 GL 스레드에서 돈다.
                    overlaySmoothing = if (arm.usesDynamicHighlightBoxes) {
                        renderer.overlaySmootherFacts()
                    } else {
                        null
                    },
                ),
            )
            lastRunPath = runDir.absolutePath
            Log.i(TAG, "로그 저장: ${framesFile.absolutePath} ($rows 행, arm=${arm.id})")
            if (arm.isDetectArm) {
                Log.i(
                    TAG,
                    "③ detect.csv $detectRows 행 | 받음=${detectPipeline.analysisFramesReceived.get()} " +
                        "추론=${detectPipeline.inferencesRun.get()} " +
                        "건너뜀=${detectPipeline.skippedWhileBusy.get()} " +
                        "오류=${detectPipeline.errors.get()} quiesced=$quiesced " +
                        "프로파일=${profiles.joinToString(",")}"
                )
            }
            // 스모크 확인용. 인용 가능한 것은 session.json의 detect.parity 블록이다.
            parityResult?.let {
                Log.i(
                    TAG,
                    "③ parity 덤프: 샘플 ${it.capturedSamples}/${it.requestedSamples} " +
                        "매니페스트=${it.manifestWritten} 바이트=${it.bytesWritten} " +
                        "이동=${it.moveMethod} 실패=${it.failures.size}"
                )
            }
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
        // 배선이 실제로 도는지 현장에서 봐야 한다(추론 행이 안 늘면 바로 보인다).
        val detectRunLine = if (!armNow.isDetectArm) {
            ""
        } else {
            "\n③ 분석 %d | 추론 %d | 건너뜀 %d | 오류 %d".format(
                detectPipeline.analysisFramesReceived.get(),
                detectPipeline.inferencesRun.get(),
                detectPipeline.skippedWhileBusy.get(),
                detectPipeline.errors.get(),
            )
        }
        // ⚠ 화면 숫자는 인용 근거가 아니다. 인용 가능한 숫자는 파일로 남긴 것뿐이다.
        statusText.text =
            head + detectLine + detectRunLine + saved +
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

        /** ORT 프로파일러의 작업 디렉토리. 결과는 런 정지 시점에 런 디렉토리로 옮겨진다. */
        const val DETECT_PROFILE_DIR = "detect_profiles"

        /**
         * ③ 대조 덤프의 staging 루트. 결과는 런 정지 시점에 `<run_dir>/parity/`로 옮겨진다
         * (위 프로파일과 같은 취지 — 런 디렉토리 이름이 정지 시점에야 확정된다).
         */
        const val DETECT_PARITY_STAGING_DIR = "detect_parity"

        /**
         * A12 (2)의 quiesce 상한. 🔴 **무한 대기를 하지 않는다** — 추론이 끝내 안 끝나면
         * 앱이 멈춘 것처럼 보이고, 그러면 측정자는 그 런을 통째로 버린다. 못 기다렸다는
         * 사실은 `session.json`의 `detect.run.quiesced=false`로 나간다.
         */
        const val DETECT_QUIESCE_TIMEOUT_MS = 3000L

        /** ①②는 GLES 3.x 셰이더 전제(`PIPELINE_STACK.md` §G)라 컨텍스트를 미리 맞춰 둔다. */
        const val EGL_CONTEXT_CLIENT_VERSION = 3

        /** **요청값.** 실제로 받은 값은 `session.json`의 `camera_actual`에 따로 남는다. */
        val FRAME_REQUEST = FrameRequest(width = 1280, height = 720, fps = 30)
    }
}
