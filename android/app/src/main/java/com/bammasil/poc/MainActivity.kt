package com.bammasil.poc

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.Surface
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.bammasil.poc.detect.DetectContract
import com.bammasil.poc.detect.DetectOverlayPublisher
import com.bammasil.poc.detect.DetectParityDumper
import com.bammasil.poc.detect.DetectPipeline
import com.bammasil.poc.detect.DetectRuntime
import com.bammasil.poc.gl.CardboardGeometry
import com.bammasil.poc.gl.PassthroughRenderer
import com.bammasil.poc.gl.DisplayMode
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
import kotlin.math.max
import kotlin.math.roundToInt

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
    private lateinit var displayModeSpinner: Spinner
    private lateinit var cardboardTuningPanel: View
    private lateinit var cardboardFovSeek: SeekBar
    private lateinit var cardboardAlignmentSeek: SeekBar
    private lateinit var cardboardFovLabel: TextView
    private lateinit var cardboardAlignmentLabel: TextView
    private lateinit var frameSource: FrameSource

    /**
     * 접을 수 있는 정보 패널(상태 텍스트 + 조명·arm 스피너). ④ 강조가 실제로 어떻게 보이는지
     * 촬영·캡처할 때 화면 절반을 가리기 때문에 **기본이 접힘**이고, 필요할 때 [hudButton]으로
     * 편다(08-29 이전에는 반대였다 — 런마다 '접기 전 몇 초'가 달라 조건이 흔들렸다).
     *
     * ⚠ 이 패널은 [glView]와 **별 서피스**라 접어도 GL 스레드의 계측 창
     * (`t_render_start_ns` ~ `t_render_end_ns`, GPU query)은 달라지지 않는다. 다만 지속 런에서
     * 컴포지션·UI 스레드 일이 줄어드는 것은 **조건 차이**이므로 [hudInfoHidden]을
     * `session.json`에 남긴다 — 조건을 기록하지 않고 넘기지 않는다.
     */
    private lateinit var infoPanel: View
    private lateinit var hudButton: Button

    /**
     * ④ 시연 토글 표식(B/L) 한 세트. **컨테이너 + 글자 둘**이 늘 함께 움직여서 묶어 둔다.
     *
     * 🔴 세 세트가 `layout/demo_hud_set.xml` **같은 파일**을 `<include>` 하므로 안쪽 글자의
     * id 가 화면에 세 벌 있다. 그래서 반드시 **세트 컨테이너를 기준으로** 찾는다 —
     * `activity.findViewById` 로 찾으면 세 세트가 전부 첫 번째 것을 가리킨다.
     */
    private class DemoHudSet(
        val container: View,
        val glyphBoxes: TextView,
        val glyphEnhance: TextView,
    )

    /** 스마트폰(NORMAL) 모드용 1세트. */
    private lateinit var demoHudSetNormal: DemoHudSet

    /** 카드보드 SBS 용 2세트. 🔴 **한쪽 눈만 켜지 않는다**(그건 표식이 아니라 결함으로 보인다). */
    private lateinit var demoHudSetLeft: DemoHudSet
    private lateinit var demoHudSetRight: DemoHudSet

    /**
     * [updateDemoHud] 전용 스크래치. HUD 갱신은 안전망(`statusTicker`) 때문에 2Hz 로도
     * 불릴 수 있어, 부를 때마다 배열을 만들면 그 쓰레기가 측정 런 내내 쌓인다.
     * ⚠ **메인 스레드 전용이다** — 렌더러의 `eyeRectScratch`(GL 스레드 전용)와 다른 물건이다.
     */
    private val hudEyeRectScratch = IntArray(4)
    private val hudAnchorScratch = IntArray(2)

    /**
     * [updateDemoHud]가 직전에 **적용한 입력**. 같은 입력이면 뷰를 아예 건드리지 않는다
     * (멱등). 🔴 이 캐시가 없으면 안전망이 측정 런 내내 초당 두 번 레이아웃을 요청해
     * UI 스레드 일이 늘고, 그건 지속 런의 **조건 차이**가 된다.
     *
     * ⚠ 사각형이 아니라 **사각형을 정하는 입력 전부**를 담는다 — 사각형만 담으면 글자 크기·
     * 여백을 가르는 값(눈 높이)이 빠져 조용히 옛 크기가 남는다.
     */
    private var hudAppliedOnce = false
    private var hudLastShow = false
    private var hudLastCardboard = false
    private var hudLastBoxes = false
    private var hudLastEnhance = false
    private var hudLastSurfaceWidth = 0
    private var hudLastSurfaceHeight = 0
    private var hudLastViewWidth = 0
    private var hudLastViewHeight = 0
    private var hudLastProcessWidth = 0
    private var hudLastProcessHeight = 0
    private var hudLastImageScale = 0f
    private var hudLastEyeOffset = 0f

    /**
     * 정보 패널이 접혀 있는가. 🔴 **측정 시작 시점의 값이 아니라 현재 값이다** — 런 도중에도
     * 접을 수 있고(촬영이 그 목적이다) 그래서 `session.json`에는 **정지 시점의 값**이 실린다.
     * 런 내내 한 상태였는지는 이 값이 말해 주지 않는다.
     *
     * 🔎 **기본값이 `true`다(08-29).** 촬영이 이 앱의 상시 용도라 매번 접는 것이 실수를 부르고,
     * 접기 전 몇 초가 런마다 달라 **조건이 흔들렸다.** 🔴 **초기 UI 반영을 잊지 말 것** —
     * 이 값만 바꾸면 변수는 '숨김'인데 화면은 펼쳐진 자기모순이 된다([applyHudVisibility]).
     * ⚠ `baseline_diff`의 조건키는 **아니다.** 즉 이 변경은 승격본과의 비교를 끊지 않지만,
     * 기존 승격본은 전부 `false` 상태에서 잰 것이다.
     */
    private var hudInfoHidden = true

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

    /**
     * **실제 경로에 반영이 끝난 arm.** 스피너의 선택([selectedArm])이 아니라 `renderer.setArm`
     * 까지 태운 값이다. 🔴 [applyArmSelection]을 버튼 경로와 스피너 리스너가 **둘 다** 부를 수
     * 있어(정보 패널이 펴져 있으면 리스너도 온다) 중복 실행을 여기서 막는다 — 막지 않으면
     * 카메라를 두 번 재바인딩하고 ③ 준비를 두 번 건다.
     */
    private var appliedArm: RenderArm? = null

    /**
     * 시연용 ②·④ 토글의 **현재** 상태. 볼륨다운이 ②(적응형 조도개선), 볼륨업이 ④(bbox)다.
     *
     * 🔴 **정지 시점의 값이 `session.json`에 나간다 — 런 내내 그랬다는 뜻이 아니다**
     * ([hudInfoHidden]과 같은 규약이다). 런 도중 몇 번 뒤집혔는지는 아래 계수가 말한다.
     *
     * ⚠ 런 **시작과 정지 양쪽**에서 `true`로 되돌린다. 시작에서 되돌리는 것은 런 단위 상태의
     * 관행이고(`PassthroughRenderer.resetRenderCounters`), 정지에서도 되돌리는 것은 참가자가
     * ② OFF로 런을 끝내면 다음 런까지 원본 프리뷰만 보여 시연 2회차가 망가지기 때문이다.
     */
    private var stage2Enabled = true
    private var overlayEnabled = true

    /** 이 런에서 토글이 뒤집힌 횟수. **런 단위 사실**이라 시작·정지 양쪽에서 0으로 내린다. */
    private var stage2ToggleCount = 0
    private var overlayToggleCount = 0

    private var displayModeAtStart: DisplayMode = DisplayMode.DEFAULT
    private var cardboardImageScaleAtStart = 0.90f
    private var cardboardEyeOffsetAtStart = -0.08f

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
        detectRuntime = DetectRuntime(assets, externalDir, File(externalDir, DETECT_PROFILE_DIR))
        detectParityDumper = DetectParityDumper(File(externalDir, DETECT_PARITY_STAGING_DIR))
        detectPipeline = DetectPipeline(
            detectRuntime, detectRecorder, detectParityDumper, detectOverlayPublisher
        )

        statusText = findViewById(R.id.status_text)
        toggleButton = findViewById(R.id.toggle_button)
        lightingSpinner = findViewById(R.id.lighting_spinner)
        armSpinner = findViewById(R.id.arm_spinner)
        displayModeSpinner = findViewById(R.id.display_mode_spinner)
        cardboardTuningPanel = findViewById(R.id.cardboard_tuning_panel)
        cardboardFovSeek = findViewById(R.id.cardboard_fov_seek)
        cardboardAlignmentSeek = findViewById(R.id.cardboard_alignment_seek)
        cardboardFovLabel = findViewById(R.id.cardboard_fov_label)
        cardboardAlignmentLabel = findViewById(R.id.cardboard_alignment_label)
        glView = findViewById(R.id.gl_view)
        infoPanel = findViewById(R.id.info_panel)
        hudButton = findViewById(R.id.hud_button)
        demoHudSetNormal = bindDemoHudSet(R.id.demo_hud_set_normal)
        demoHudSetLeft = bindDemoHudSet(R.id.demo_hud_set_left)
        demoHudSetRight = bindDemoHudSet(R.id.demo_hud_set_right)

        // 🔴 컨트롤 바를 시스템 내비게이션 바 **위로** 밀어 올린다.
        //    이걸 안 하면 3버튼 내비 기기에서 정지 버튼이 홈·뒤로 버튼과 겹치고, 정지를
        //    누르려다 뒤로가가 눌리면 **flush 가 버려진다**(알려진 이슈 10). 실기기에서
        //    실제로 그렇게 눌리는 것을 확인하고 넣었다.
        //    ⚠ 인셋을 루트에 주지 않는다 — GLSurfaceView 가 같이 줄어들면 present 기하가
        //      바뀌어 승격 베이스라인과 비교가 끊긴다. 이 바 하나에만 준다.
        //    ⚠ dp 를 박지 않는다. 내비 방식(3버튼/제스처)과 기기마다 값이 다르다.
        val controlBar = findViewById<View>(R.id.control_bar)
        val controlBarBasePadding = controlBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(controlBar) { view, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                controlBarBasePadding + bottom,
            )
            insets
        }

        // 정보 패널 접기/펴기. 측정 중에도 눌릴 수 있게 잠그지 않는다 — ④ 강조가 도는 모습을
        // 🔴 **초기 상태를 여기서 반영한다.** 레이아웃에는 visibility 선언이 없어 XML 기본이
        //    `visible`이고, [hudInfoHidden]의 기본값은 `true`다 — 반영하지 않으면 변수와 화면이
        //    어긋난 채 시작하고 버튼 글자도 거꾸로 뜬다.
        applyHudVisibility()

        // 찍는 것이 이 버튼의 목적이고, 렌더 경로를 건드리지 않으므로 잠글 이유가 없다.
        // 🔴 초기 반영과 **같은 함수**를 부른다 — 두 곳에 같은 식을 적으면 한쪽만 고쳐지는 날
        //    화면과 버튼 글자가 갈린다.
        hudButton.setOnClickListener {
            hudInfoHidden = !hudInfoHidden
            applyHudVisibility()
        }

        val thread = HandlerThread("frame-signal").apply { start() }
        signalThread = thread
        renderer = PassthroughRenderer(
            recorder, Handler(thread.looper), detectOverlayPublisher
        )
        renderer.onFrameSignal = { glView.requestRender() }
        renderer.onGlReady = { uiHandler.post { onGlReady() } }
        // 🔴 **GL 스레드에서 온다** — 반드시 메인 루퍼로 넘긴다(위 onGlReady 와 같은 관행).
        //    서피스 크기가 확정되기 전에는 [updateDemoHud]가 표식을 숨기고 있으므로, 이 훅이
        //    없으면 앱을 켠 뒤 표식이 영영 안 나온다.
        renderer.onSurfaceResized = { _, _ -> uiHandler.post { updateDemoHud() } }
        // 🔴 협상 전에는 눈 사각형이 16:9 폴백으로 계산된다([CardboardGeometry.eyeViewport]).
        //    이 훅이 없으면 켠 직후 카드보드 표식이 **엉뚱한 자리**에 붙은 채 남는다.
        //    ⚠ `acquireSurface` 는 이미 메인 스레드지만 관행대로 post 한다 — 카메라 바인딩
        //      한복판에서 레이아웃을 요청하지 않는다.
        renderer.onProcessSizeChanged = { _, _ -> uiHandler.post { updateDemoHud() } }

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
                // 🔴 반영은 **[applyArmSelection] 한 곳**이다 — on/off 버튼도 같은 함수를
                //    부른다(왜 버튼이 이 리스너에 기댈 수 없는지는 그 KDoc에 있다).
                applyArmSelection(
                    RenderArm.fromId(armSpinner.getItemAtPosition(position)?.toString())
                )
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        // 🔴 **기본 arm을 통합 arm으로 세운다.** 시연에서 참가자가 앱을 켜자마자 보는 것이
        //    ②③④가 다 도는 화면이어야 하고, 볼륨키 토글(dispatchKeyEvent)의 대상도 이
        //    arm의 두 자리(② mix uniform · ④ 오버레이)다.
        //    🔴 **`setSelection`만으로는 반영되지 않는다** — arm 스피너는 기본이 `GONE`인
        //      정보 패널 안이라 `onItemSelected`가 오지 않는다([applyArmSelection] KDoc).
        //      그래서 반영을 콜백에 맡기지 않고 여기서 직접 부른다.
        //    ⚠ 순서 전제: [detectRuntime] 생성 뒤 · `glView.setRenderer` 뒤 ·
        //      `armSpinner.adapter` 대입 뒤여야 한다.
        val defaultArmIndex = RenderArm.CHOICES.indexOf(RenderArm.DETECT_CPU_CHAIN_HIGHLIGHT.id)
        if (defaultArmIndex < 0) {
            // 🔴 조용히 0번 arm을 고르지 않는다 — 어느 arm이 도는지 모르는 채로 돌게 된다.
            showMessage(
                "arm 목록에서 ${RenderArm.DETECT_CPU_CHAIN_HIGHLIGHT.id}를 찾지 못했다 " +
                    "— 아무것도 바꾸지 않았다"
            )
        } else {
            armSpinner.setSelection(defaultArmIndex)
            applyArmSelection(RenderArm.DETECT_CPU_CHAIN_HIGHLIGHT)
        }

        displayModeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            DisplayMode.CHOICES,
        )
        displayModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                val mode = DisplayMode.fromId(
                    displayModeSpinner.getItemAtPosition(position)?.toString()
                )
                glView.queueEvent { renderer.setDisplayMode(mode) }
                cardboardTuningPanel.visibility = if (mode == DisplayMode.CARDBOARD_SBS) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
                requestedOrientation = if (mode == DisplayMode.CARDBOARD_SBS) {
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
                // ④ 시연 표식은 모드마다 세트 수가 다르다(1세트 ↔ 눈마다 1세트).
                updateDemoHud()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val tuningListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                applyCardboardTuning()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
        cardboardFovSeek.setOnSeekBarChangeListener(tuningListener)
        cardboardAlignmentSeek.setOnSeekBarChangeListener(tuningListener)
        applyCardboardTuning()

        toggleButton.setOnClickListener {
            if (recording) stopRecording() else startRecording()
        }

        frameSource = CameraFrameSource(this, this, detectPipeline)
        frameSource.updateTargetRotation(glView.display?.rotation ?: Surface.ROTATION_0)
        ensureCameraPermission()
        uiHandler.post(statusTicker)
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::frameSource.isInitialized) {
            frameSource.updateTargetRotation(glView.display?.rotation ?: Surface.ROTATION_0)
        }
        if (::glView.isInitialized) glView.requestRender()
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
                // 🔴 **성공 토스트를 없애지 않는다** — 시연 진행자가 이 신호를 기다렸다 폰을
                //    넘긴다. 대신 [showMessage]의 두 청중 규약을 쓴다: 토스트는 참가자가 읽는
                //    짧은 문구, 로그는 report.oneLine() **원문 그대로**(측정자가 읽을 정보는
                //    하나도 잃지 않는다).
                //    기본 arm이 통합 arm이 되면서 이 콜백이 **앱 실행마다** 도는데, 예전처럼
                //    oneLine()을 토스트에 그대로 띄우면 참가자에게 arm·ep·노드·sha256이
                //    LENGTH_LONG으로 뜬다 — showMessage KDoc이 토스트 인자에 금지한 어휘다.
                // 🔴 **실패는 가르지 않는다** — 준비 실패는 진행자가 즉시 알아야 하고, 그때는
                //    기술 문구가 나가는 편이 낫다(showMessage KDoc의 "실패·거부 경로는 가르지
                //    않는다"). 여기서 안 보이면 측정자는 런을 시작하려다 거부당하고 나서야
                //    이유를 알게 된다.
                if (report.ok) {
                    showMessage(getString(R.string.detect_ready), report.oneLine())
                } else {
                    showMessage(report.oneLine())
                }
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
     * 스피너 선택을 **실제 경로에 반영한다.** 스피너 리스너와 [onCreate]의 기본 arm 반영이
     * **같은 함수**를 부른다.
     *
     * 🔴 **왜 `setSelection`에만 기댈 수 없나:** `armSpinner`는 정보 패널 안에 있고 그 패널은
     * 기본이 `View.GONE`이다([hudInfoHidden]의 기본값이 true다). `AdapterView.setSelection`의
     * `onItemSelected`는 **레이아웃 패스에서 전달**되므로 `GONE`인 부모 아래에서는 오지
     * 않는다 — 그래서 (지금은 없어진) on/off 버튼을 눌러도 arm이 반영되지 않았고, 정보 보기로
     * 패널을 펴는 순간 밀려 있던 선택이 그제야 발화했다(측정자가 "버튼이 안 먹는다"로 겪은
     * 것이 이것이다). [onCreate]의 기본 arm 반영이 `setSelection` 다음에 이 함수를 직접
     * 부르는 이유가 그것이다.
     * ⚠ 그때도 `getSelectedItemPosition()`은 새 값을 돌려주므로 **HUD와 [selectedArm]은 이미
     * 새 arm을 가리켰다** — 화면만 옛 arm이었다. [startRecording]의 `renderer.setArm` 안전망이
     * 런의 arm은 지켜 왔다(그래서 기록된 런은 오염되지 않았다).
     *
     * 🔴 **선택은 여전히 스피너가 쥔다**(`setSelection`) — 스피너 표시와 [selectedArm]
     * (= [startRecording]이 읽는 값)이 갈리면 `session.json`이 실제로 돈 arm과 다른 arm을
     * 적는다. 이 함수가 하는 것은 **반영**뿐이다.
     */
    private fun applyArmSelection(arm: RenderArm) {
        // 같은 arm을 두 번 반영하지 않는다(위 [appliedArm] KDoc).
        if (appliedArm == arm) return
        appliedArm = arm
        // GL 자원을 만지므로 GL 스레드에서 바꾼다.
        glView.queueEvent { renderer.setArm(arm) }
        prepareDetectIfNeeded(arm)
        rebindSourceIfAnalysisChanged(arm)
        // 🔴 `RENDERMODE_WHEN_DIRTY`라 **새 카메라 프레임이 오기 전에는 그리지 않는다.** arm이
        //    바뀌면 ③ 분석 use case의 유무가 달라져 카메라를 다시 바인딩하는데, 그 사이에는
        //    프레임이 없어 화면이 **옛 arm의 마지막 그림에 멈춘다.** 한 장을 강제로 다시 그려
        //    바뀐 결과가 바로 보이게 한다([applyCardboardTuning]·[onConfigurationChanged]와
        //    같은 관행이다).
        //    ⚠ 측정 중에는 이 경로가 닫혀 있다(측정 중에는 arm 스피너가 잠긴다) — 그래서
        //      이 강제 드로우가 런의 `draws_without_new_frame`에 섞이지 않는다.
        glView.requestRender()
        // ④ 시연 표식은 통합 arm 에서만 뜬다 — arm 이 바뀌면 세트를 통째로 접거나 편다.
        updateDemoHud()
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
            showMessage(
                getString(R.string.camera_not_ready),
                "카메라가 아직 준비되지 않았다 — 권한과 프리뷰를 먼저 확인할 것",
            )
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
        // 🔴 시연 토글은 **런 단위 상태**다 — 직전 런의 상태와 계수가 이 런에 누적되지
        //    않게 여기서 내린다(`PassthroughRenderer.resetRenderCounters`의 관행).
        resetDemoToggles()
        toggleButton.setText(R.string.stop)
        // 런 도중 조건이 바뀌면 그 분포는 오염된 것이다 → 조건 입력을 전부 잠근다.
        lightingSpinner.isEnabled = false
        armSpinner.isEnabled = false
        displayModeSpinner.isEnabled = false
        cardboardFovSeek.isEnabled = false
        cardboardAlignmentSeek.isEnabled = false
        armAtStart = arm
        displayModeAtStart = selectedDisplayMode()
        cardboardImageScaleAtStart = 1f - cardboardFovSeek.progress / 100f
        cardboardEyeOffsetAtStart = (cardboardAlignmentSeek.progress - 60) / 200f
        // 🔴 위 [resetDemoToggles]의 갱신은 `armAtStart`·`displayModeAtStart` 가 **잠기기 전**에
        //    일어났다(그 시점에는 옛 런의 값이었다). 잠근 직후 한 번 더 불러 시작하는 순간의
        //    표식이 맞게 한다 — 안 하면 안전망(statusTicker)이 고칠 때까지 최대 500ms 동안
        //    옛 arm 기준의 표식이 남는다.
        updateDemoHud()
        // 🔴 **조명 기본값이 `unknown`이 아니게 된 것의 짝이다**
        //    ([LightingCondition.CHOICES] 참고). 예전에는 스피너를 안 만지면 하네스가
        //    "비교 대상이 못 된다"고 소리 내어 거부했는데, 이제 안 만진 런이 **정상적인
        //    야간 런으로 조용히 통과**한다. 그 신호를 여기서 되살린다 — 실내에서 재면서
        //    조명을 안 바꾼 것을 **시작하는 순간** 알아채야 한다.
        //    ⚠ HUD(statusText)가 아니라 Toast인 이유: 촬영 중에는 HUD를 접어 두므로
        //      (hudButton) 거기 적으면 정작 필요한 때 보이지 않는다.
        //    ⚠ 앱은 조명을 알 수 없다 — 이 값은 **사람의 신고**이고 여기서 하는 것은
        //      검증이 아니라 **확인**이다. 틀린 신고를 앱이 잡아낼 방법은 없다.
        val lightingNow = lightingSpinner.selectedItem?.toString() ?: LightingCondition.UNKNOWN
        // 🔴 **틀렸을 때만 시끄럽게 한다.** 매번 "실내면 바꿔라"를 띄우면 참가자에게는
        //    뜻 없는 말이고 측정자에게는 곧 배경이 되어 진짜 실수 때도 안 읽힌다.
        //    야외 야간이 아니면(= 실내거나 unknown이면) 그때만 경고를 붙인다.
        val lightingLooksIndoor = !lightingNow.startsWith("outdoor_")
        showMessage(
            getString(R.string.rec_start, lightingNow) +
                if (lightingLooksIndoor) {
                    "\n" + getString(R.string.rec_start_lighting_warn, lightingNow)
                } else {
                    ""
                },
            "측정 시작 — lighting_condition=$lightingNow arm=${arm.id}" +
                if (lightingLooksIndoor) " 🔴 야외 야간이 아니다 — 실내에서 재는 것이 맞는지 확인할 것" else "",
        )
        runDirName = newRunDirName()
        val startedNs = SystemClock.elapsedRealtimeNanos()
        glView.queueEvent {
            // 스피너 콜백을 놓쳤을 가능성을 여기서 닫는다. 이 시점 이후로 arm은 고정이다.
            renderer.setArm(arm)
            renderer.setDisplayMode(displayModeAtStart)
            renderer.setCardboardTuning(
                cardboardImageScaleAtStart,
                cardboardEyeOffsetAtStart,
            )
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
        // 정지 시점의 토글 상태는 아래 writeLogs가 인자로 들고 가고, 화면은 여기서 바로
        // 기본(둘 다 ON)으로 되돌린다 — 참가자가 ② OFF로 런을 끝내면 다음 런 시작까지
        // 원본 프리뷰만 보이고 시연 2회차가 망가진다.
        val stage2AtStop = stage2Enabled
        val overlayAtStop = overlayEnabled
        val stage2Toggles = stage2ToggleCount
        val overlayToggles = overlayToggleCount
        resetDemoToggles()
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
            val message = writeLogs(
                outDir, runName, lighting, arm, displayModeAtStart,
                cardboardImageScaleAtStart, cardboardEyeOffsetAtStart,
                negotiated, analysis, sourceKind,
                // 🔴 **위에서 리셋하기 전에 붙잡아 둔 값**이다 — 필드를 여기서 읽으면
                //    resetDemoToggles가 이미 기본값으로 되돌린 뒤라 전부 ON·0이 나간다.
                stage2AtStop, overlayAtStop, stage2Toggles, overlayToggles,
            )
            uiHandler.post {
                toggleButton.isEnabled = true
                lightingSpinner.isEnabled = true
                armSpinner.isEnabled = true
                displayModeSpinner.isEnabled = true
                cardboardFovSeek.isEnabled = true
                cardboardAlignmentSeek.isEnabled = true
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
        displayMode: DisplayMode,
        cardboardImageScale: Float,
        cardboardEyeOffset: Float,
        negotiated: NegotiatedConfig?,
        analysis: AnalysisConfig?,
        sourceKind: String,
        /** 시연 토글의 **정지 시점** 상태와 이 런의 뒤집힘 횟수. 호출부가 붙잡아 넘긴다. */
        stage2EnabledAtStop: Boolean,
        overlayEnabledAtStop: Boolean,
        stage2Toggles: Int,
        overlayToggles: Int,
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
                    // 정지 시점의 값이다. 런 내내 한 상태였다는 뜻이 아니다.
                    hudInfoHidden = hudInfoHidden,
                    // 🔴 시연 토글도 **정지 시점의 값**이다(위 hudInfoHidden과 같은 규약).
                    //    런 내내 그랬는지는 아래 계수가 0인지로만 말할 수 있다.
                    stage2EnabledAtStop = stage2EnabledAtStop,
                    overlayEnabledAtStop = overlayEnabledAtStop,
                    stage2ToggleCount = stage2Toggles,
                    overlayToggleCount = overlayToggles,
                    arm = arm,
                    displayMode = displayMode,
                    cardboardImageScale = cardboardImageScale,
                    cardboardEyeOffset = cardboardEyeOffset,
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
                    // 🔴 표시 경로가 **실제로 건** 회전. ④ 오버레이 좌표계는 이 회전을 타지
                    //    않으므로 두 축이 어긋날 수 있다 — 그 사실을 남기는 유일한 기록이다
                    //    (session.json의 render.preview_transform).
                    previewRotationApplied = renderer.previewRotationApplied,
                    previewMirrorApplied = renderer.previewMirrorApplied,
                    previewRotationApplyCount = renderer.previewRotationApplyCount,
                    previewRotationAppliedAtRecordedFrame =
                        renderer.previewRotationAppliedAtRecordedFrame,
                    previewTransformArrivals = renderer.previewTransformArrivals,
                    previewTransformNote = renderer.previewTransformNote,
                    // 🔴 회전 예산 불변식(render.rotation_budget)이 읽는 **원값**이다 —
                    //    사람이 읽는 previewTransformNote에서 숫자를 긁어내지 않는다.
                    cameraTransformRotationDegrees = renderer.cameraTransformRotationDegrees,
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
            "야간 보조가 종료되었습니다"
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

    private fun applyCardboardTuning() {
        val imageScale = 1f - cardboardFovSeek.progress / 100f
        val eyeOffset = (cardboardAlignmentSeek.progress - 60) / 200f
        cardboardFovLabel.text = getString(
            R.string.cardboard_fov_format,
            (imageScale * 100f).toInt(),
        )
        cardboardAlignmentLabel.text = getString(
            R.string.cardboard_alignment_format,
            eyeOffset * 100f,
        )
        glView.queueEvent { renderer.setCardboardTuning(imageScale, eyeOffset) }
        glView.requestRender()
        // 슬라이더가 눈 사각형을 움직이므로 그 위에 얹힌 ④ 시연 표식도 따라가야 한다.
        // ⚠ 여기 넘긴 것과 **같은 원값**을 [updateDemoHud]도 읽는다 — 클램프는 양쪽 다
        //   [CardboardGeometry] 안에서 일어나므로 두 사각형이 갈릴 수 없다.
        updateDemoHud()
    }

    private fun selectedArm(): RenderArm =
        RenderArm.fromId(armSpinner.selectedItem?.toString())

    private fun selectedDisplayMode(): DisplayMode =
        DisplayMode.fromId(displayModeSpinner.selectedItem?.toString())

    // ── 시연용 볼륨키 토글 ───────────────────────────────────────────────

    /**
     * 시연 토글을 **런 단위 기본**(②·④ 둘 다 ON, 계수 0)으로 내린다. 런 시작과 정지가
     * 둘 다 부른다 — 이유는 [stage2Enabled] KDoc.
     *
     * 🔴 렌더러에는 **GL 스레드로 넘겨서만** 반영한다([PassthroughRenderer.setDemoToggles]).
     */
    private fun resetDemoToggles() {
        stage2Enabled = true
        overlayEnabled = true
        stage2ToggleCount = 0
        overlayToggleCount = 0
        glView.queueEvent { renderer.setDemoToggles(true, true) }
        // 🔴 [startRecording]·[stopRecording] **둘 다** `recording` 을 세운 **뒤** 이 함수를
        //    부르므로 이 한 자리가 두 사건을 덮는다.
        //    ⚠ 다만 시작 경로에서는 `armAtStart`·`displayModeAtStart` 가 **아직 옛 런의 값**이다
        //      — 그래서 [startRecording]이 그 둘을 잠근 직후에 한 번 더 부른다.
        updateDemoHud()
    }

    /**
     * 볼륨키로 ②(밝기 보정)와 ④(위험물 표시)를 즉시 켜고 끈다. **시연에서 참가자가 체감으로
     * 비교하는 것이 목적이고 성능 기록이 아니다.**
     *
     * - 볼륨**업** = ④ 위험물 표시 / 볼륨**다운** = ② 밝기 보정
     * - 🔴 `ACTION_DOWN`·`ACTION_UP`을 **둘 다 소비한다.** `ACTION_UP`을 흘리면 시스템
     *   볼륨 패널이 화면에 뜬다(누른 사람이 보는 것은 밤길이지 볼륨 슬라이더가 아니다).
     * - 상태를 바꾸는 것은 `ACTION_DOWN && repeatCount == 0`일 때뿐이다 — 길게 누르면
     *   자동 반복이 초당 수십 번 들어온다.
     * - 측정 중이 아니면 아무것도 바꾸지 않는다. 🔴 **잠금 창은 [recording] 하나로 덮인다** —
     *   [stopRecording]이 가장 먼저 `recording = false`를 세우고 `writeLogs`는 그 뒤에
     *   `queueEvent`로 돈다. 추가 플래그를 만들지 않는다.
     * - 🔴 **arm이 [RenderArm.DETECT_CPU_CHAIN_HIGHLIGHT]가 아니면 아무것도 바꾸지 않는다.**
     *   토글이 사는 렌더 경로(`drawChainedHighlight` + 복제 프로그램)를 `_1q`·`_nofill`
     *   계측 arm이 **함께 쓰기** 때문이다 — 게이트가 없으면 볼륨키 한 번이 그 계측 런의 렌더
     *   구성을 바꾼다. 거부 문구는 정지 상태 거부와 **다른 문자열**이다(사유가 다르다).
     * - 🔴 **`glView.requestRender()`를 부르지 않는다.** 새 프레임 없는 강제 드로우는
     *   `recorder.noteDrawWithoutNewFrame()`을 태워 측정 중에 `draws_without_new_frame`을
     *   오염시킨다. 반영이 최대 한 프레임(30fps에서 33ms) 늦지만 토스트와 햅틱이 이미
     *   즉시 피드백을 준다.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isVolumeUp = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
        val isVolumeDown = event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (!isVolumeUp && !isVolumeDown) return super.dispatchKeyEvent(event)
        // 여기부터는 어떤 경로로 빠지든 **소비한다**(위 KDoc의 볼륨 패널 이유).
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return true
        if (!recording) {
            showMessage(
                getString(R.string.demo_toggle_idle),
                "볼륨키 토글은 측정 중에만 먹는다 — 아무것도 바꾸지 않았다",
            )
            return true
        }
        // 🔴 **arm 게이트.** 토글이 가르는 두 자리(패스4·7의 mix(uEnhance) · 패스8의 오버레이)는
        //    drawChainedHighlight와 그 복제 프로그램에 있는데, 그 경로를
        //    **RenderArm.usesChainedHighlight인 셋이 공유한다**(통합 arm · `_1q` · `_nofill`).
        //    게이트가 없으면 `_1q`/`_nofill` **계측 런 도중 볼륨키 한 번이 그 런의 렌더 구성을
        //    바꾼다** — 이 줄을 지우려는 사람은 그 사실을 먼저 알아야 한다.
        //    ⚠ 측정 중에는 arm 스피너가 잠겨 있으므로 selectedArm()은 armAtStart와 같다.
        //    ⚠ 사후 자진 신고(session.json의 demo_toggles.*_toggle_count)는 이 게이트와
        //      **무관하게 모든 런에 그대로 나간다** — 예방과 사후 배제는 둘 다 있어야 한다.
        val armNow = selectedArm()
        if (armNow != RenderArm.DETECT_CPU_CHAIN_HIGHLIGHT) {
            showMessage(
                getString(R.string.demo_toggle_wrong_arm),
                "볼륨키 토글은 ${RenderArm.DETECT_CPU_CHAIN_HIGHLIGHT.id}에서만 먹는다 " +
                    "(지금 arm=${armNow.id}) — 계측 arm의 런을 오염시키지 않는다",
            )
            return true
        }
        val messageRes = if (isVolumeUp) {
            overlayEnabled = !overlayEnabled
            overlayToggleCount++
            if (overlayEnabled) R.string.demo_boxes_on else R.string.demo_boxes_off
        } else {
            stage2Enabled = !stage2Enabled
            stage2ToggleCount++
            if (stage2Enabled) R.string.demo_enhance_on else R.string.demo_enhance_off
        }
        // 🔴 렌더러에는 GL 스레드로 넘긴다 — 프레임 중간에 값이 갈리면 그 프레임의
        //    overlay_boxes와 화면이 어긋난다([PassthroughRenderer.setDemoToggles]).
        val stage2 = stage2Enabled
        val overlay = overlayEnabled
        glView.queueEvent { renderer.setDemoToggles(stage2, overlay) }
        // ④ 시연 표식은 토스트가 사라진 뒤에도 남는 유일한 상태 표시다.
        // ⚠ 뷰 오버레이라 `glView.requestRender()` 를 부르지 않는다 — 위 KDoc 대로
        //   강제 드로우는 `draws_without_new_frame` 을 오염시킨다.
        updateDemoHud()
        showMessage(
            getString(messageRes),
            "볼륨키 토글 — stage2=$stage2(누적 $stage2ToggleCount) " +
                "overlay=$overlay(누적 $overlayToggleCount)",
        )
        // ⚠ CONFIRM은 API 30부터다(minSdk는 26). 상수는 컴파일 시점에 인라인되므로 옛
        //   기기에서도 죽지 않고 **햅틱만 안 온다** — 측정 기기는 API 36이라 실제로는 온다.
        window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        return true
    }

    // ── 화면 표시 (진행 확인용) ──────────────────────────────────────────

    private fun updateStatus() {
        // ④ 시연 표식의 **안전망**이다(훅 대체가 아니다). 갱신 지점을 하나 놓쳐도 0.5초 안에
        // 맞아 든다. 🔴 [updateDemoHud]가 멱등이라 실제 뷰 쓰기는 값이 바뀔 때만 일어난다 —
        // 그렇지 않으면 측정 런 내내 2Hz 로 레이아웃을 요청하게 되고 그건 조건 차이가 된다.
        updateDemoHud()
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

    /**
     * [hudInfoHidden]을 화면에 반영한다. **초기화와 버튼이 같이 부른다.**
     *
     * ⚠ `GONE`을 쓴다(`INVISIBLE`이 아니다) — `INVISIBLE`은 자리를 그대로 차지해 화면이 안 열린다.
     */
    private fun applyHudVisibility() {
        infoPanel.visibility = if (hudInfoHidden) View.GONE else View.VISIBLE
        hudButton.setText(if (hudInfoHidden) R.string.hud_show else R.string.hud_hide)
    }

    // ── ④ 시연 토글 표식 (B/L) ──────────────────────────────────────────

    /**
     * 세트 하나를 묶는다. 🔴 **컨테이너를 기준으로** 글자를 찾는다 — 세 세트가 같은 레이아웃을
     * `<include>` 해서 안쪽 id 가 화면에 세 벌 있고, `activity.findViewById` 로 찾으면 세
     * 세트가 전부 첫 번째 글자를 가리킨다.
     */
    private fun bindDemoHudSet(setId: Int): DemoHudSet {
        val container = findViewById<View>(setId)
        return DemoHudSet(
            container,
            container.findViewById(R.id.demo_hud_glyph_boxes),
            container.findViewById(R.id.demo_hud_glyph_enhance),
        )
    }

    /**
     * ④ 시연 토글(볼륨키) 상태를 화면 **우측 상단**에 B/L 로 낸다.
     *
     * - `B`(노랑) = 위험물 표시가 켜짐 / `L`(초록) = 밝기 보정이 켜짐. 둘 다 **측정 중일 때만**
     *   뜬다 — 정지 상태에서는 토글이 먹지 않으므로([dispatchKeyEvent]) 켜진 것처럼 보이면
     *   그건 거짓말이다.
     * - arm 이 [RenderArm.DETECT_CPU_CHAIN_HIGHLIGHT] 가 아니면 **세트를 통째로 `GONE`** 한다.
     *   토글 자체가 그 arm 에서만 먹기 때문이다(`_1q`·`_nofill` 계측 arm 은 게이트로 막혀 있다).
     * - 표시 모드가 카드보드면 **양쪽 눈에 각각** 낸다. 한쪽만 넣으면 표식이 아니라 결함이다.
     *
     * 🔴 **이 함수는 멱등이다.** 같은 입력이면 뷰를 한 번도 건드리지 않고 돌아간다 —
     * 안전망이 [updateStatus](0.5초)에 걸려 있어서, 캐시가 없으면 측정 런 내내 2Hz 로
     * 레이아웃을 요청하게 되고 그건 지속 런의 **조건 차이**가 된다.
     *
     * 🔴 **GL 을 쓰지 않는다.** 통합 arm 의 present 패스에 드로우콜이 하나라도 붙으면
     * `detect_cpu_chain_highlight_1q` 와의 렌더 동일성이 거짓이 되고 ④ 오버레이 비용 하한의
     * 근거가 무너진다. 표식은 끝까지 안드로이드 View 다.
     */
    private fun updateDemoHud() {
        // 측정 중에는 **잠긴 값**을 읽는다(스피너가 아니라). [updateStatus]와 같은 규약이다.
        val armNow = if (recording) armAtStart else selectedArm()
        val show = armNow == RenderArm.DETECT_CPU_CHAIN_HIGHLIGHT
        val modeNow = if (recording) displayModeAtStart else selectedDisplayMode()
        val cardboard = modeNow == DisplayMode.CARDBOARD_SBS
        val surfaceWidth = renderer.surfaceWidth
        val surfaceHeight = renderer.surfaceHeight
        // 🔴 `frameSource.negotiated` 로 우회하지 않는다 — 값의 출처가 둘이 되면 렌더러가 그린
        //    사각형과 표식이 붙은 사각형이 갈릴 수 있다. 렌더러가 실제로 쓰는 값만 읽는다.
        val processWidth = renderer.processWidth
        val processHeight = renderer.processHeight
        // ⚠ 슬라이더 **원값**을 그대로 쓴다([applyCardboardTuning]이 렌더러에 넘기는 것과 같은
        //   식이다). 클램프는 [CardboardGeometry] 안에서만 일어나므로 두 사각형이 갈릴 수 없다.
        val imageScale = 1f - cardboardFovSeek.progress / 100f
        val eyeOffset = (cardboardAlignmentSeek.progress - 60) / 200f
        val boxesOn = recording && overlayEnabled
        val enhanceOn = recording && stage2Enabled
        val viewWidth = glView.width
        val viewHeight = glView.height

        if (hudAppliedOnce &&
            show == hudLastShow &&
            cardboard == hudLastCardboard &&
            boxesOn == hudLastBoxes &&
            enhanceOn == hudLastEnhance &&
            surfaceWidth == hudLastSurfaceWidth &&
            surfaceHeight == hudLastSurfaceHeight &&
            viewWidth == hudLastViewWidth &&
            viewHeight == hudLastViewHeight &&
            processWidth == hudLastProcessWidth &&
            processHeight == hudLastProcessHeight &&
            imageScale == hudLastImageScale &&
            eyeOffset == hudLastEyeOffset
        ) {
            return
        }
        hudAppliedOnce = true
        hudLastShow = show
        hudLastCardboard = cardboard
        hudLastBoxes = boxesOn
        hudLastEnhance = enhanceOn
        hudLastSurfaceWidth = surfaceWidth
        hudLastSurfaceHeight = surfaceHeight
        hudLastViewWidth = viewWidth
        hudLastViewHeight = viewHeight
        hudLastProcessWidth = processWidth
        hudLastProcessHeight = processHeight
        hudLastImageScale = imageScale
        hudLastEyeOffset = eyeOffset

        if (!show || surfaceWidth <= 0 || surfaceHeight <= 0) {
            hideDemoHud()
            return
        }
        // 🔴 GL 서피스 픽셀과 뷰 픽셀이 1:1 이라는 것을 **가정하지 않고 확인한다.** 지금은
        //    `setFixedSize`·`SurfaceHolder` 사용이 저장소에 없고 GLSurfaceView 가
        //    `match_parent` 라 같지만, 어긋난 날 스케일을 조용히 곱해 맞춘 척하면 표식이
        //    미묘하게 틀린 자리에 서고 아무도 모른다. **조용한 성공을 만들지 않는다.**
        if (viewWidth != surfaceWidth || viewHeight != surfaceHeight) {
            Log.w(
                TAG,
                "④ 시연 표식을 숨긴다 — GL 서피스와 뷰의 픽셀이 다르다 " +
                    "(surface=${surfaceWidth}x$surfaceHeight, view=${viewWidth}x$viewHeight). " +
                    "1:1 전제가 깨졌으니 스케일을 정하기 전에는 표식을 그리지 않는다",
            )
            hideDemoHud()
            return
        }

        applyDemoHudGlyphs(demoHudSetNormal, boxesOn, enhanceOn)
        applyDemoHudGlyphs(demoHudSetLeft, boxesOn, enhanceOn)
        applyDemoHudGlyphs(demoHudSetRight, boxesOn, enhanceOn)

        if (!cardboard) {
            demoHudSetLeft.container.visibility = View.GONE
            demoHudSetRight.container.visibility = View.GONE
            // 🔴 NORMAL 의 present 는 `FRAGMENT_SHADER_BLIT` 이라 렌즈 왜곡이 없다 →
            //    화면 전체가 곧 보이는 영상이고 모서리 비율은 1 이다.
            hudEyeRectScratch[0] = 0
            hudEyeRectScratch[1] = 0
            hudEyeRectScratch[2] = surfaceWidth
            hudEyeRectScratch[3] = surfaceHeight
            placeDemoHudSet(
                demoHudSetNormal,
                hudEyeRectScratch,
                CardboardGeometry.NORMAL_CORNER_FRACTION,
                NORMAL_HUD_TEXT_SP,
                surfaceWidth,
                surfaceHeight,
            )
            return
        }

        demoHudSetNormal.container.visibility = View.GONE
        // 🔴 눈 사각형은 렌더러가 뷰포트를 세울 때와 **같은 함수**에서 온다. 여기에 식을 다시
        //    적으면 영상과 표식이 서로 다른 사각형을 믿게 된다.
        val leftWidth = CardboardGeometry.leftEyeWidth(surfaceWidth)
        val cornerFraction = CardboardGeometry.visibleCornerFraction()
        CardboardGeometry.eyeViewport(
            0,
            leftWidth,
            -1,
            surfaceHeight,
            processWidth,
            processHeight,
            imageScale,
            eyeOffset,
            hudEyeRectScratch,
        )
        placeDemoHudSet(
            demoHudSetLeft,
            hudEyeRectScratch,
            cornerFraction,
            cardboardHudTextSp(hudEyeRectScratch[3]),
            surfaceWidth,
            surfaceHeight,
        )
        CardboardGeometry.eyeViewport(
            leftWidth,
            surfaceWidth - leftWidth,
            1,
            surfaceHeight,
            processWidth,
            processHeight,
            imageScale,
            eyeOffset,
            hudEyeRectScratch,
        )
        placeDemoHudSet(
            demoHudSetRight,
            hudEyeRectScratch,
            cornerFraction,
            cardboardHudTextSp(hudEyeRectScratch[3]),
            surfaceWidth,
            surfaceHeight,
        )
    }

    private fun hideDemoHud() {
        demoHudSetNormal.container.visibility = View.GONE
        demoHudSetLeft.container.visibility = View.GONE
        demoHudSetRight.container.visibility = View.GONE
    }

    /**
     * 🔴 꺼진 글자는 `GONE` 이 아니라 `INVISIBLE` 이다 — **자리가 유지돼야** 참가자가 왼쪽/오른쪽
     * 위치로 무엇이 켜졌는지 안다(위치를 학습하는 앱이다). `GONE` 이면 한쪽만 켰을 때 남은
     * 글자가 옆으로 튄다.
     * ⚠ `INVISIBLE` 은 TalkBack 도 건너뛰므로 켜진 글자의 설명만 읽힌다.
     */
    private fun applyDemoHudGlyphs(set: DemoHudSet, boxesOn: Boolean, enhanceOn: Boolean) {
        set.glyphBoxes.visibility = if (boxesOn) View.VISIBLE else View.INVISIBLE
        set.glyphEnhance.visibility = if (enhanceOn) View.VISIBLE else View.INVISIBLE
    }

    /**
     * 세트 하나를 [rect](GL 좌표의 눈 사각형)의 **보이는 영상 우상단**에 붙인다.
     *
     * 좌표 변환은 두 걸음뿐이다: [CardboardGeometry.hudAnchor] 가 왜곡을 반영한 앵커를 GL
     * 좌표로 주고, 여기서 **Y 만 뒤집어** 안드로이드 뷰 좌표로 바꾼다. 픽셀 스케일이 1:1
     * 이라는 것은 부르는 쪽이 이미 확인했다([updateDemoHud]).
     */
    private fun placeDemoHudSet(
        set: DemoHudSet,
        rect: IntArray,
        cornerFraction: Float,
        textSizeSp: Float,
        containerWidth: Int,
        containerHeight: Int,
    ) {
        CardboardGeometry.hudAnchor(rect, cornerFraction, hudAnchorScratch)
        val anchorRight = hudAnchorScratch[0]
        // GL 은 좌하단 원점, 뷰는 좌상단 원점이다.
        val anchorTop = containerHeight - hudAnchorScratch[1]
        val metrics = resources.displayMetrics
        val minInsetPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, HUD_MIN_INSET_DP, metrics
        )
        val insetPx = max(minInsetPx, rect[3] * HUD_INSET_HEIGHT_FRACTION).roundToInt()
        set.glyphBoxes.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        set.glyphEnhance.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        val params = set.container.layoutParams as FrameLayout.LayoutParams
        // 🔴 `START|TOP` + marginStart 를 쓰지 않는다 — 그러려면 세트의 `measuredWidth` 가
        //    필요한데 첫 레이아웃 패스에서는 0 이라 한 프레임 동안 어긋난 자리에 뜬다.
        //    END 앵커는 그 값을 아예 쓰지 않아 그 문제가 없다.
        params.gravity = Gravity.TOP or Gravity.END
        params.marginEnd = (containerWidth - anchorRight) + insetPx
        params.topMargin = anchorTop + insetPx
        set.container.layoutParams = params
        set.container.visibility = View.VISIBLE
    }

    /**
     * 카드보드 표식의 글자 크기(sp). **눈 사각형 높이에 비례**시킨다 — FOV 슬라이더로 눈이
     * 작아지면 고정 크기 글자가 영상을 덮어 버린다. 위아래는 클램프한다: 너무 작으면 저시력
     * 참가자가 못 읽고, 너무 크면 글자 자체가 가림막이 된다.
     */
    private fun cardboardHudTextSp(eyeHeightPx: Int): Float {
        val pxPerSp = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics
        )
        // 실기기에서 0 이 될 일은 없지만, 0 이면 나눗셈이 무한대가 된다 — 값을 지어내는 대신
        // 하한으로 떨어뜨린다.
        if (pxPerSp <= 0f) return CARDBOARD_HUD_MIN_TEXT_SP
        val raw = eyeHeightPx * CARDBOARD_HUD_TEXT_HEIGHT_FRACTION / pxPerSp
        return raw.coerceIn(CARDBOARD_HUD_MIN_TEXT_SP, CARDBOARD_HUD_MAX_TEXT_SP)
    }

    /**
     * 화면(Toast)과 로그에 알린다. 🔴 **두 청중이 다르다.**
     *
     * - [toast] — **사용자 테스트 참가자**가 읽는 말. 기술어(arm·use case·바인딩·프리뷰)를
     *   쓰지 않는다. 문구는 `strings.xml`의 "참가자가 읽는 문구" 절에 둔다.
     * - [log] — **측정자**용 정밀 문장. 기본값은 [toast]와 같고, 다르게 주면 토스트만
     *   쉬워지고 추적 정보는 그대로 남는다.
     *
     * ⚠ **실패·거부 경로는 가르지 않는다** — 런이 왜 안 도는지를 나르는 문장은 측정자가
     * 현장에서 바로 봐야 하므로 토스트에 그대로 띄운다(③ 게이트·탐지 기록 실패 등).
     */
    private fun showMessage(toast: String, log: String = toast) {
        Log.i(TAG, log)
        Toast.makeText(this, toast, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val TAG = "BammasilPoc"
        const val STATUS_INTERVAL_MS = 500L

        /** 스마트폰 모드의 ④ 시연 표식 글자 크기. 화면이 크고 고정이라 비례시킬 이유가 없다. */
        const val NORMAL_HUD_TEXT_SP = 32f

        /** 카드보드는 눈 사각형이 슬라이더로 변하므로 **눈 높이에 비례**시키고 클램프한다. */
        const val CARDBOARD_HUD_TEXT_HEIGHT_FRACTION = 0.09f
        const val CARDBOARD_HUD_MIN_TEXT_SP = 20f
        const val CARDBOARD_HUD_MAX_TEXT_SP = 40f

        /** 표식 여백 = `max(6dp, 눈 높이 × 0.03)`. 작은 눈에서도 모서리에 딱 붙지 않게. */
        const val HUD_MIN_INSET_DP = 6f
        const val HUD_INSET_HEIGHT_FRACTION = 0.03f

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
