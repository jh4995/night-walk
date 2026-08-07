package com.bammasil.poc.detect

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.bammasil.poc.gl.RenderArm
import com.bammasil.poc.log.DetectLogRecorder
import com.bammasil.poc.source.AnalysisSink
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * ③ 탐지를 **프레임 경로에 붙이는** 자리. A7(트리거) · A11(기록) · A12(정지 순서)가 여기 있다.
 *
 * ## A7 — idle-gated 트리거 (🔴 주기 N을 정하지 않는다)
 *
 * 탐지 주기 N은 `INTERFACES.md`에서 아직 `☐`이고 **앱이 값을 지어내지 않는다.** 대신
 * 분석 프레임이 올 때 **탐지가 유휴일 때만** 추론하고, 바쁘면 `skipped_while_busy++` 후
 * 즉시 반환한다(닫기는 `CameraFrameSource`의 `finally`가 한다). 그러면 하네스가
 * `detect_cadence_ms` 분포로 **실측 실행 주기**를 말한다 — 선언된 N이 아니라 관측값이다.
 *
 * ⚠ **이것은 상한이지 배포 구성이 아니다.** 탐지를 쉬지 않고 최대로 돌리는 조건이므로 SoC
 * 경쟁·발열이 최악이고, 그 아래에서 관측된 표시 경로 프레임타임은 **"탐지를 최대로 돌렸을
 * 때의 하한"**이다. 실제 제품은 N을 정해 훨씬 드물게 돌 것이고 그때 프레임타임은 이보다 낫다.
 *
 * ## 스레드 둘 — 왜 하나가 아닌가
 *
 * ```
 * detect-analysis (CameraFrameSource 소유)   detect-infer (여기 소유)
 *   프레임 도착 → busy? → E(전처리)            F(session.run) → G(후처리) → 행 기록
 *   ImageProxy.close()  ← finally
 * ```
 * 하나였다면 CameraX가 **콜백이 반환할 때까지 다음 프레임을 안 준다.** 그러면 추론 중에
 * 버려진 프레임이 0으로 보이고 `skipped_while_busy`가 영원히 0이 된다 — 관측하려던 것이
 * 관측 방법 때문에 사라지는 형태다. 둘로 가르면 E가 끝나는 즉시 `ImageProxy`가 닫히고
 * 분석 스레드가 다음 프레임을 받으므로 건너뛴 수가 실제로 세어진다.
 *
 * ## 회계 불변식
 *
 * `analysis_frames_received == inferences_run + skipped_while_busy + errors`
 *
 * 기록 중에만 센다. 이 식이 안 닫히면 어디선가 프레임이 조용히 사라진 것이고,
 * `session.json`이 그 사실을 그대로 낸다(앱이 맞춰 주지 않는다).
 */
class DetectPipeline(
    private val runtime: DetectRuntime,
    val recorder: DetectLogRecorder,
    /**
     * ③ 이식 정확성 대조 덤프. **덤프 arm이 아니면 아무 일도 하지 않는다**
     * ([DetectParityDumper.capture]가 즉시 null을 낸다) — 다른 arm의 프레임 경로에 이 객체가
     * 있다는 사실이 비용으로 새면 안 되기 때문이다.
     */
    private val parity: DetectParityDumper,
    /**
     * ③ → ④ 게시자. **오버레이 arm이 아니면 아무 일도 하지 않는다**
     * ([DetectOverlayPublisher.enabled]가 false이고 호출자도 박스 복사를 건너뛴다) —
     * [parity]와 **같은 취지·같은 자리**다. 그래야 `detect_cpu` 경로가 바이트 단위로 유지된다.
     */
    private val publisher: DetectOverlayPublisher,
) : AnalysisSink {

    private val worker: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "detect-infer") }

    /** 🔴 트리거의 전부다. 분석 스레드가 세우고 워커가 내린다. */
    private val busy = AtomicBoolean(false)

    /** 회계를 세는 중인가. 분모 arm([startBindOnly])에서도 true다. */
    private val enabled = AtomicBoolean(false)

    /**
     * 추론을 돌리는가. 🔴 분모 arm(`detect_bind_only`)에서는 **false**다 — 그 arm은
     * `ImageAnalysis` 하나를 더 붙인 비용만 재는 자리이고, 여기서 추론을 돌리면 분모가
     * 분자와 같아져 차분이 0이 된다.
     */
    private val inferenceEnabled = AtomicBoolean(false)

    // ── 회계 (기록 중에만 증가) ───────────────────────────────────────────
    val analysisFramesReceived = AtomicLong(0)
    val inferencesRun = AtomicLong(0)
    val skippedWhileBusy = AtomicLong(0)
    val errors = AtomicLong(0)

    /** 마지막 오류 문장. 있으면 `session.json`에 그대로 나간다. */
    @Volatile
    var lastError: String? = null
        private set

    /** ③ 준비 상태에서 만든 것들. [start]에서 세우고 [stop] 이후에도 남는다(재사용). */
    private var pre: DetectPreprocessor? = null
    private var post: DetectPostprocessor? = null
    private var session: DetectRuntime.ReadySession? = null

    /**
     * 이 런에서 실제로 쓴 letterbox 기하(마지막 프레임). `session.json` 기록용.
     *
     * 🔴 **런 시작마다 내린다.** 이 객체는 Activity 수명인데 값은 **런 단위 사실**이라,
     * 안 내리면 detect 런 뒤에 분모 arm(`detect_bind_only`)을 돌렸을 때 **직전 런의
     * letterbox가 그 런의 `detect.run.letterbox`로 그대로 나간다.**
     * ⚠ 회전이 붙은 뒤로는 더 나쁘다 — `src`가 **회전 후 치수**라(규약 §5) 남의 런에서 온
     * 회전 후 치수를 이 런의 사실로 읽게 된다.
     *
     * 🔎 **누수 셋의 출처를 갈라 적는다**(2026-08-07 정정): 이 필드의 누수는 **이번 변경
     * 이전부터 있었고** 이번 변경이 **오해를 키웠다**(`src`의 뜻이 바뀌었다). 반면
     * `invertedBoxesTotal`·`invertedSamples`와 회전 상태의 누수는 **이번 변경이 스스로
     * 만든 것**이다 — 런보다 수명이 긴 [post]와 이 객체에 **런 단위 누계**를 새로 얹었다.
     * 그러므로 그 둘의 리셋은 "범위 밖 보너스"가 아니라 **이번 변경에 필요한 처리**다.
     */
    @Volatile
    var lastLetterbox: DetectContract.Letterbox? = null
        private set

    // ── 회전 (규약 §4) ────────────────────────────────────────────────────
    // 🔴 **회전각은 첫 분석 프레임에서 잠근다**(§4-3). 런 도중에 바뀌면 letterbox 기하가
    //    중간에 갈려 **E와 박스 좌표가 한 런 안에서 두 뜻을 갖는다** — 그건 그 런의 숫자를
    //    통째로 못 쓰게 만든다.
    //    ⚠ **조용히 따라가지도, 런을 죽이지도 않는다.** 따라가면 위 문제가 그대로 일어나고
    //      거부하면 측정이 통째로 날아간다. **잠근 값을 계속 쓰면서 세는 쪽**을 택했다 —
    //      rotation_changed_frames != 0인 런은 PC가 경고를 내고 승격 대상에서 뺀다.

    /** 첫 분석 프레임에서 잠근 `rotationDegrees`. [ROTATION_UNLOCKED]면 아직 안 잠갔다. */
    @Volatile
    var lockedRotationDegrees: Int = ROTATION_UNLOCKED
        private set

    /** 잠근 뒤 **다른 값이 온** 프레임 수. 0이 아니면 그 런의 기하 조건이 흔들린 것이다. */
    val rotationChangedFrames = AtomicLong(0)

    /**
     * 이 런의 arm이 회전을 **적용하는가**. [start]에서 잠근다.
     * false면 대조군 arm(`detect_cpu_norot`)이고 아래 [resolveRotation]이 항등을 낸다 —
     * 🔴 **별도 코드 경로가 아니다.** 짝 arm과 같은 함수에 회전각 0을 넣어 태운다.
     */
    @Volatile
    private var appliesRotation = false

    /** 이번 런이 실제로 쓰는 회전 기하. 분석 스레드가 만들고 워커·GL 스레드가 읽는다. */
    @Volatile
    private var effectiveRotation: DetectContract.Rotation? = null

    /**
     * 기하 왕복 자체검사의 결과. [start]가 채우고 `session.json`이 싣는다.
     * 🔴 실패하면 [start]가 **런을 시작하지 않는다.**
     */
    @Volatile
    var geometryCheck: DetectGeometryCheck.Result? = null
        private set

    /**
     * 기록을 시작한다. **UI 스레드에서, 아직 아무 추론도 없는 시점에** 부른다.
     *
     * @param arm 이 런의 arm. 🔴 **덤프 arm인지 여기서 갈린다** — 스피너의 현재 값이 아니라
     *   `MainActivity`가 시작 시점에 잠근 arm이어야 한다(런 도중 바뀐 값으로 덤프를 켜면
     *   session.json이 실제로 돈 경로와 다른 것을 적게 된다).
     * @return 시작하지 못한 사유(사람이 읽는 문장). null이면 시작했다.
     */
    fun start(arm: RenderArm): String? {
        val ready = runtime.ready
            ?: return "③ 세션이 준비되지 않았다 — arm 스피너에서 이 arm을 다시 고를 것"
        // 🔴 임계를 숫자로 못 읽으면 **후처리를 시작하지 않는다.** 0으로 뭉개면 전 앵커가
        //    통과해 그 런의 G는 다른 것을 잰 숫자가 된다.
        DetectContract.thresholdFailure?.let { return it }
        val conf = DetectContract.confThreshold ?: return DetectContract.thresholdFailure
        val iou = DetectContract.iouThreshold ?: return DetectContract.thresholdFailure

        if (ready.inputShape.size != 4 || ready.outputShape.size != 3) {
            return "그래프 shape이 예상과 다르다: 입력 ${ready.inputShape.size}축 / " +
                "출력 ${ready.outputShape.size}축 — 값을 지어내지 않는다"
        }
        // 🔴 **기하 왕복 자체검사.** 임계를 못 읽으면 후처리를 시작하지 않는 것과 **같은
        //    자리·같은 취지**다 — 회전/letterbox의 정·역변환이 서로의 역함수가 아니면
        //    E는 엉뚱한 픽셀을 읽고 G의 박스는 통째로 어긋나는데 **둘 다 그럴듯한 숫자로
        //    나온다.** 11분을 찍고 나서 아는 것보다 시작 전에 거부하는 쪽이 싸다.
        //    ⚠ 목적지 치수는 **그래프에서 읽은 값**을 넘긴다(640을 적으면 그게 하드코딩이다).
        val geometry = DetectGeometryCheck.run(ready.inputWidth, ready.inputHeight)
        geometryCheck = geometry
        if (!geometry.passed) return geometry.failureText()

        session = ready
        // 🔴 해상도·채널·앵커 수는 전부 **그래프에서 읽은 값**이다. 여기에 640이나 8400을
        //    적으면 그게 곧 하드코딩이고 모델이 갱신되는 날 조용히 어긋난다.
        pre = DetectPreprocessor(ready.inputWidth, ready.inputHeight, ready.inputChannels)
        post = DetectPostprocessor(ready.outputChannels, ready.outputAnchors, conf, iou)

        // ③ 대조 덤프. 🔴 **덤프를 시작하지 못하면 런을 시작하지 않는다** — 라벨만
        //    detect_parity_*이고 parity/가 없는 런은 이 라운드가 막으려는 실패다.
        //    ⚠ 클래스 이름은 **모델이 낸 것**을 넘긴다(계약 문서의 순서가 아니다).
        if (arm.usesDetectParityDump) {
            parity.start(runtime.report?.classNames ?: emptyList())?.let { return it }
        } else {
            parity.disable()
        }

        // ③ → ④ 게시자. 🔴 **클래스 이름의 출처는 모델 임베드 메타 하나뿐이다**(계약 문서의
        //    순서가 아니다) — 오버레이 색이 이 이름으로 정해지므로, 여기서 계약 순서를 넘기면
        //    사람과 계단의 색이 뒤바뀐다. parity 덤프가 이름을 받는 자리와 같은 논거다.
        // 🔴 런 단위 리셋도 여기서 한다(start가 reset을 먼저 부른다) — 안 내리면 직전 런의
        //    게시가 이 런 첫 프레임에 그려지고 t_overlay_source_ns가 남의 런 시각이 된다.
        if (arm.usesDynamicHighlightBoxes) {
            publisher.start(runtime.report?.classNames ?: emptyList())
        } else {
            publisher.disable()
        }

        // 🔴 회전을 적용하는가는 **arm이 정한다.** 대조군(`detect_cpu_norot`)만 false다.
        appliesRotation = arm.appliesDetectRotation
        lockedRotationDegrees = ROTATION_UNLOCKED
        rotationChangedFrames.set(0)
        effectiveRotation = null
        // 🔴 런 단위 사실은 전부 여기서 내린다(위 lastLetterbox KDoc).
        lastLetterbox = null

        analysisFramesReceived.set(0)
        inferencesRun.set(0)
        skippedWhileBusy.set(0)
        errors.set(0)
        lastError = null
        busy.set(false)
        recorder.start()
        inferenceEnabled.set(true)
        enabled.set(true)
        return null
    }

    /**
     * 분모 arm(`detect_bind_only`)용. **회계만 켠다** — 분석 프레임이 몇 장 왔는지는 재고
     * 추론은 하지 않는다. 그래야 "use case 하나를 더 붙인 값"이 무엇을 받고 있었는지
     * `session.json`에 남는다.
     */
    fun startBindOnly() {
        // 이 arm은 추론이 없으므로 덤프도 없다. 이전 런의 상태가 남지 않게 내린다.
        parity.disable()
        // 게시자도 같다 — 남겨 두면 직전 런의 스냅샷이 이 런의 GL 스레드에 보인다.
        publisher.disable()
        // 🔴 런 단위 상태를 전부 내린다. 남겨 두면 **직전 detect 런의 사실이 이 런의
        //    session.json으로 샌다** — arm을 바꿔 연속 측정할 수 있으므로 실제로 일어난다.
        //    이 arm은 전처리를 돌리지 않으므로 여기서 내린 값이 그대로 기록된다.
        appliesRotation = false
        lockedRotationDegrees = ROTATION_UNLOCKED
        rotationChangedFrames.set(0)
        effectiveRotation = null
        geometryCheck = null
        lastLetterbox = null
        analysisFramesReceived.set(0)
        inferencesRun.set(0)
        skippedWhileBusy.set(0)
        errors.set(0)
        lastError = null
        busy.set(false)
        inferenceEnabled.set(false)
        enabled.set(true)
    }

    /**
     * ③ arm도 분모 arm도 아닌 arm(`highlight_boxes` · `blit_2pass` · `passthrough` …)으로 런을
     * 시작할 때. **UI 스레드에서, [start]/[startBindOnly]와 같은 자리에서 부른다.**
     *
     * 🔴 **게시자를 내리는 것이 전부다.** 그 두 함수 어느 쪽도 불리지 않는 **세 번째 경로**라,
     * 그대로 두면 직전 detect 런의 `enabled=true`와 스냅샷이 살아남는다. 지금은 GL 읽기가
     * `RenderArm.usesDynamicHighlightBoxes`로 게이트돼 **관측되지 않지만**, 남의 런 시각이
     * `t_overlay_source_ns`에 실릴 수 있는 부류의 결함이고 이 저장소의 방어 관행은
     * **"런 단위 상태는 런 시작에서 내린다"**다(`lastLetterbox` 누수와 같은 논거).
     */
    fun disableOverlayPublish() {
        publisher.disable()
    }

    /** 이 런에서 추론을 돌렸는가. `session.json`이 회계 불변식을 적용할지의 판별식이다. */
    val inferenceWasEnabled: Boolean get() = inferenceEnabled.get()

    /** A12 (1) — 기록 플래그를 내린다. **UI 스레드에서 먼저 부른다.** */
    fun stopRecording() {
        enabled.set(false)
        recorder.stop()
    }

    /**
     * A12 (2) — **탐지 스레드 quiesce.** 진행 중인 추론이 끝날 때까지 기다린다(타임아웃).
     *
     * 🔴 이걸 빼면 워커가 마지막 행을 쓰는 중에 [DetectLogRecorder.writeCsv]가 돌아 행이
     * 찢기거나 회계가 안 닫힌다. 기록은 이미 멈춰 있으므로 여기서 기다리는 시간은 측정에
     * 섞이지 않는다.
     *
     * @return 시간 안에 조용해졌는가. false면 그 사실이 `session.json`에 나간다.
     */
    fun quiesce(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (busy.get()) {
            if (SystemClock.elapsedRealtime() >= deadline) return false
            try {
                Thread.sleep(QUIESCE_POLL_MS)
            } catch (t: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return true
    }

    fun shutdown() {
        enabled.set(false)
        worker.shutdown()
    }

    // ── AnalysisSink (분석 스레드) ────────────────────────────────────────

    /**
     * 🔴 **[ImageProxy]를 보관하지 않는다.** 반환하면 `CameraFrameSource`의 `finally`가
     * 닫는다 — 그래서 E가 끝나기 전에 이 함수가 반환해서는 안 되고, 반환한 뒤에 픽셀을
     * 읽어서도 안 된다.
     */
    override fun onAnalysisImage(image: ImageProxy) {
        if (!enabled.get()) return
        analysisFramesReceived.incrementAndGet()
        // 분모 arm — 프레임이 왔다는 사실만 세고 아무것도 하지 않는다.
        if (!inferenceEnabled.get()) return

        // 🔴 규약 §4-3 — **첫 분석 프레임의 값으로 잠근다.** 이후 다른 값이 오면 잠근 값을
        //    계속 쓰면서 센다(조용히 따라가지 않는다). 🔴 **바쁜 프레임도 센다** —
        //    회전이 바뀌었다는 사실은 그 프레임을 처리했는지와 무관한 런의 조건이다.
        //    ⚠ 분석 스레드 전용이라 두 필드에 경합이 없다.
        val observed = image.imageInfo.rotationDegrees
        if (lockedRotationDegrees == ROTATION_UNLOCKED) {
            lockedRotationDegrees = observed
        } else if (observed != lockedRotationDegrees) {
            rotationChangedFrames.incrementAndGet()
        }

        // A7 — 탐지가 바쁘면 **아무것도 하지 않는다.** 여기서 큐에 쌓으면 프레임타임이
        // 실제보다 좋아 보이고 지연만 늘어난다.
        if (!busy.compareAndSet(false, true)) {
            skippedWhileBusy.incrementAndGet()
            return
        }

        val preprocessor = pre
        val ready = session
        if (preprocessor == null || ready == null) {
            busy.set(false)
            noteError("전처리기/세션이 없다 — start()를 거치지 않았다")
            return
        }
        // 🔴 각이 {0,90,180,270} 밖이면 **이 프레임을 처리하지 않는다** — 그럴듯한 근사를
        //    만들지 않는다. errors로 계상되므로 회계 불변식은 그대로 닫힌다.
        val rotation = resolveRotation(image.width, image.height)
        if (rotation == null) {
            busy.set(false)
            noteError(
                DetectContract.rotationFailure(
                    if (appliesRotation) lockedRotationDegrees else 0,
                    image.width,
                    image.height,
                )
            )
            return
        }

        // t_detect_recv_ns: 탐지 스레드가 그 프레임을 받은 시각.
        // 🔴 frames.csv의 t_recv_ns와 **같은 시계**(elapsedRealtimeNanos = CLOCK_BOOTTIME).
        val tRecvNs = SystemClock.elapsedRealtimeNanos()
        // ⚠ ImageInfo.timestamp는 기준 시계가 기기마다 다르다(§시계 함정) — 원본 그대로
        //   싣고 우리 시계와 빼지 않는다.
        val tCaptureNs = image.imageInfo.timestamp

        val tensor: OnnxTensor
        val eNs: Long
        val box: DetectContract.Letterbox?
        try {
            // ── E 시작: ImageProxy에서 픽셀을 꺼내는 것부터 ──
            // 🔴 **이 두 줄(eStart / eNs)이 E의 정의다.** 회전이 붙어도 자리는 그대로다 —
            //    바뀌는 것은 E의 **값**이지 정의가 아니다(DetectContract.ROTATION_APPLIED_*).
            val eStart = System.nanoTime()
            val buffer = preprocessor.convert(image, rotation)
            val t = OnnxTensor.createTensor(ready.env, buffer, ready.inputShape)
            eNs = System.nanoTime() - eStart
            // ── E 끝: 입력 텐서가 준비된 시점 ──
            tensor = t
            box = preprocessor.lastLetterbox
            lastLetterbox = box
        } catch (t: Throwable) {
            busy.set(false)
            noteError("전처리 실패: ${t.javaClass.simpleName}: ${t.message}")
            return
        }

        // ③ 대조 덤프의 재료를 **여기서** 복사한다(덤프 arm에서 K개까지, 아니면 null).
        // 🔴 **E 구간 밖이다** — 위에서 eNs를 찍은 뒤이므로 이 복사가 stage_e_ms에 섞이지 않는다.
        // 🔴 **여기서만 복사할 수 있다**: ImageProxy는 이 함수가 반환하면 닫히고,
        //    DetectPreprocessor는 평면 배열과 inputBuffer를 **재사용**한다(참조만 넘기면
        //    다음 프레임이 덮어쓴다). letterbox도 **그 프레임의 것**을 그대로 넘긴다.
        val capture = parity.capture(image, preprocessor.inputBuffer, box, tRecvNs)

        worker.execute { infer(tensor, tRecvNs, tCaptureNs, eNs, rotation, capture) }
    }

    /**
     * 이번 런이 실제로 쓸 회전 기하. 소스 치수와 각이 그대로면 **재사용한다**(프레임당 객체
     * 0개 규약). 각이 {0,90,180,270} 밖이면 null이고 호출자가 그 프레임을 버린다.
     *
     * 🔴 회전을 적용하지 않는 arm에서는 **각 0**으로 만든다 — 별도 코드 경로가 아니라
     * 같은 함수에 항등을 넣는 것이다(그래야 짝 arm과의 차분이 "회전 여부"만 남는다).
     */
    private fun resolveRotation(width: Int, height: Int): DetectContract.Rotation? {
        val degrees = if (appliesRotation) lockedRotationDegrees else 0
        val cached = effectiveRotation
        if (cached != null &&
            cached.degrees == degrees && cached.srcW == width && cached.srcH == height
        ) {
            return cached
        }
        val made = DetectContract.rotationOf(degrees, width, height) ?: return null
        effectiveRotation = made
        return made
    }

    /**
     * 이 런의 회전 사실. **정지 시점(GL 스레드)에서 부른다** — 매니페스트(`parity.json`의
     * `source`)와 `session.json`이 같은 값을 쓴다.
     *
     * 🔴 `rotatedWidth`/`rotatedHeight`는 **letterbox의 소스로 실제로 쓴 치수**다. 회전을
     * 적용하지 않은 arm에서는 센서 치수와 같고, 그래야 `letterbox.src_w/src_h`와 어긋나지
     * 않는다(규약 §5의 사슬은 그 둘이 같기를 요구한다).
     */
    fun rotationFacts(): DetectRotationFacts {
        val rot = effectiveRotation
        return DetectRotationFacts(
            degrees = lockedRotationDegrees,
            applied = appliesRotation,
            site = if (appliesRotation) {
                DetectContract.ROTATION_SITE_SAMPLE_MAP
            } else {
                DetectContract.ROTATION_SITE_NONE
            },
            locked = lockedRotationDegrees != ROTATION_UNLOCKED,
            changedFrames = rotationChangedFrames.get(),
            rotatedWidth = rot?.rotatedW ?: -1,
            rotatedHeight = rot?.rotatedH ?: -1,
            note = if (appliesRotation) {
                DetectContract.ROTATION_APPLIED_SAMPLE_MAP
            } else {
                DetectContract.ROTATION_NOT_APPLIED_CONTROL
            },
        )
    }

    // ── 워커 스레드 ──────────────────────────────────────────────────────

    private fun infer(
        tensor: OnnxTensor,
        tRecvNs: Long,
        tCaptureNs: Long,
        eNs: Long,
        /** 🔴 **그 프레임에 실제로 쓴** 회전 기하. 후처리가 같은 것으로 되돌려야 한다. */
        rotation: DetectContract.Rotation,
        /** ③ 대조 덤프의 재료. 덤프 arm이 아니거나 K개를 이미 잡았으면 null이다. */
        capture: DetectParityDumper.Capture?,
    ) {
        val ready = session
        val postprocessor = post
        if (ready == null || postprocessor == null) {
            closeQuietly(tensor)
            busy.set(false)
            noteError("워커가 세션/후처리기를 잃었다")
            return
        }
        var result: OrtSession.Result? = null
        try {
            // ── F: session.run() **1회**. 이 호출 하나뿐이다 ──
            val fStart = System.nanoTime()
            result = ready.session.run(mapOf(ready.inputName to tensor))
            val fNs = System.nanoTime() - fStart

            // ── G: 출력 읽기 + conf 필터 + xyxy + 클래스별 NMS + letterbox 역변환 +
            //       **회전 역변환**(규약 §5-1의 다섯 칸째) ──
            val gStart = System.nanoTime()
            val outTensor = result.get(0) as OnnxTensor
            val box = lastLetterbox
                ?: throw IllegalStateException("letterbox 기하가 없다 — 전처리를 거치지 않았다")
            // ⚠ 버퍼를 지역 변수로 잡는 것은 **덤프가 같은 것을 다시 읽기 위해서**다.
            //   호출 순서·횟수는 그대로이므로 G의 뜻은 바뀌지 않는다(floatBuffer 호출 1회).
            val outBuffer = outTensor.floatBuffer
            val out = postprocessor.run(outBuffer, box, rotation)
            val gNs = System.nanoTime() - gStart

            val tEndNs = SystemClock.elapsedRealtimeNanos()
            recorder.record(
                tRecvNs = tRecvNs,
                tEndNs = tEndNs,
                tImageCaptureNs = tCaptureNs,
                maxConfMicro = Math.round(out.maxConf.toDouble() * MICRO),
                stageENs = eNs,
                stageFNs = fNs,
                stageGNs = gNs,
                boxesPreNms = out.boxesPreNms.toLong(),
                boxesOut = out.boxesOut.toLong(),
                // 그 시점까지의 **누적**이다(스키마 §2-D).
                skippedWhileBusy = skippedWhileBusy.get(),
            )
            inferencesRun.incrementAndGet()

            // ── ③ 대조 덤프 ──────────────────────────────────────────────
            // 🔴 **E·F·G 구간 밖이고 행 기록도 끝난 뒤다.** 구간 안에 넣으면 디스크 I/O가
            //    F·G에 섞여 그 값이 그대로 detect.csv로 나간다(그래도 이 arm의 시간은
            //    인용 금지다 — RenderArm.DETECT_PARITY_NOT_QUOTABLE).
            // ⚠ 박스는 **여기서** 복사한다. 후처리기의 내부 배열은 다음 프레임이 덮어쓴다.
            if (capture != null) {
                parity.write(
                    capture,
                    outBuffer,
                    out.boxesPreNms,
                    postprocessor.copyBoxes(out.boxesOut),
                    // 🔴 **그 샘플에서 센 개수**다(거른 개수가 아니다 — 규약 §5-3).
                    //    런 전체의 총계는 session.json이 따로 들고, 두 곳은 **다른 것을
                    //    센다**(전자는 덤프 K개, 후자는 런 전체).
                    out.boxesInverted,
                )
            }

            // ── ③ → ④ 게시 ───────────────────────────────────────────────
            // 🔴 **E·F·G 구간 밖이고 행 기록도 끝난 뒤다** — parity 덤프와 **같은 자리·같은
            //    논거**다. 구간 안에 넣으면 게시당 할당(스냅샷 1개 + 배열 3개 + 박스 복사)이
            //    G에 섞이고, 승격된 F 실측과의 비교가 끊긴다.
            // 🔴 **게시자가 꺼져 있으면 박스 복사조차 하지 않는다** — 그래야 오버레이가 아닌
            //    arm의 워커 경로에 늘어나는 것이 volatile 읽기 하나뿐이다.
            // ⚠ 박스는 **여기서** 복사한다(후처리기의 내부 배열은 다음 프레임이 덮어쓴다).
            //    역전 박스를 세고 그리지 않는 것은 게시자가 한다(규약 §5-3) — 그 수는
            //    session.json에서 detect.run.inverted_boxes와 **교차 대조**된다.
            if (publisher.enabled) {
                publisher.publish(
                    postprocessor.copyBoxes(out.boxesOut),
                    rotation,
                    appliesRotation,
                    box,
                )
            }
        } catch (t: Throwable) {
            noteError("추론/후처리 실패: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            try {
                result?.close()
            } catch (t: Throwable) {
                Log.w(TAG, "Result close 실패", t)
            }
            closeQuietly(tensor)
            // 🔴 **마지막에 내린다.** 먼저 내리면 다음 프레임의 E가 이 정리와 겹친다.
            busy.set(false)
        }
    }

    private fun closeQuietly(tensor: OnnxTensor) {
        try {
            tensor.close()
        } catch (t: Throwable) {
            Log.w(TAG, "OnnxTensor close 실패", t)
        }
    }

    private fun noteError(message: String) {
        errors.incrementAndGet()
        lastError = message
        Log.e(TAG, message)
    }

    // ── 역전 박스 (규약 §5-3) ─────────────────────────────────────────────
    // 🔴 **거른 개수가 아니라 센 개수다.** 후처리기가 세고 여기서 꺼내 준다.
    //    ⚠ **quiesce 뒤에 읽는다**(A12 (2)) — 워커가 `busy`를 내리면서 happens-before가
    //      서므로 그 뒤의 읽기는 마지막 추론까지 반영한다.

    // 🔴 **추론을 돌리지 않은 런에서는 0/빈 목록이다.** [post]는 [start]에서만 다시 만들어져
    //    분모 arm(`detect_bind_only`)에서는 **직전 detect 런의 것이 그대로 남아 있다** —
    //    그대로 읽으면 남의 런 숫자가 이 런의 session.json으로 샌다(arm을 바꿔 연속 측정할
    //    수 있으므로 실제로 일어난다).
    //    🔎 **이건 기존 결함이 아니라 이번 변경이 만든 것이다** — 예전 [post]에는 런 단위
    //      누계가 없었고(프레임 단위 배열만 있었다) 읽는 코드도 없었다. 런보다 수명이 긴
    //      객체에 런 누계를 얹은 것이 이번 변경이므로, 이 가드는 그 처리의 일부다.

    /** 이 런에서 관측한 역전 박스 총계. 추론이 없었으면 0. */
    val invertedBoxesTotal: Long
        get() = if (inferenceEnabled.get()) post?.invertedBoxesTotal ?: 0L else 0L

    /** 처음 몇 개의 역전 박스 **실제 좌표**. 개수만으로는 못 고친다(이슈 34). */
    val invertedBoxSamples: List<DetectPostprocessor.InvertedBox>
        get() = if (inferenceEnabled.get()) post?.invertedSamples ?: emptyList() else emptyList()

    private companion object {
        const val TAG = "BammasilDetectPipe"
        const val QUIESCE_POLL_MS = 5L
        const val MICRO = 1_000_000.0

        /** [lockedRotationDegrees]의 "아직 안 잠갔다". 0°는 유효한 값이라 쓰지 않는다. */
        const val ROTATION_UNLOCKED = -1
    }
}

/**
 * 이 런의 **회전 사실**. `parity.json`의 `source` 블록(규약 §3·§4)과 `session.json`의
 * `detect.input`이 **같은 값**을 쓴다 — 두 곳이 각자 계산하면 갈리는 날이 온다.
 *
 * 🔴 [applied]와 [degrees]는 **다른 사실이다**(규약 §4-2). 기기가 0°를 주면 회전은
 * **적용됐는데 항등**이고 그때도 [applied]는 true다. [applied]가 false인데 [site]가
 * `preprocess_*`이면 그것은 **모순**이고 PC는 그 매니페스트를 읽으면 죽는다.
 */
class DetectRotationFacts(
    /** 첫 분석 프레임에서 잠근 `rotationDegrees`. 못 잠갔으면 **-1**([locked]가 false다). */
    val degrees: Int,
    /** 회전을 실제로 적용했는가. 🔴 대조군 arm(`detect_cpu_norot`)에서만 false다. */
    val applied: Boolean,
    /** 규약 §4-1의 어휘 중 하나(`preprocess_sample_map` / `none`). */
    val site: String,
    val locked: Boolean,
    /** 잠근 뒤 다른 값이 온 프레임 수. 0이 아니면 PC가 경고를 내고 승격에서 뺀다. */
    val changedFrames: Long,
    /**
     * 🔴 **letterbox의 소스로 실제로 쓴 치수.** 회전을 적용하지 않은 arm에서는 센서 치수와
     * 같다 — 그래야 `letterbox.src_w/src_h`와 어긋나지 않는다(규약 §5). 모르면 -1.
     */
    val rotatedWidth: Int,
    val rotatedHeight: Int,
    /** 사람이 읽는 문장. `DetectContract`의 두 상수 중 하나다. */
    val note: String,
)
