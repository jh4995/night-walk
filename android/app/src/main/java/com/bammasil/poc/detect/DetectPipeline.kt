package com.bammasil.poc.detect

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
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

    /** 이 런에서 실제로 쓴 letterbox 기하(마지막 프레임). `session.json` 기록용. */
    @Volatile
    var lastLetterbox: DetectContract.Letterbox? = null
        private set

    /**
     * 기록을 시작한다. **UI 스레드에서, 아직 아무 추론도 없는 시점에** 부른다.
     *
     * @return 시작하지 못한 사유(사람이 읽는 문장). null이면 시작했다.
     */
    fun start(): String? {
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
        session = ready
        // 🔴 해상도·채널·앵커 수는 전부 **그래프에서 읽은 값**이다. 여기에 640이나 8400을
        //    적으면 그게 곧 하드코딩이고 모델이 갱신되는 날 조용히 어긋난다.
        pre = DetectPreprocessor(ready.inputWidth, ready.inputHeight, ready.inputChannels)
        post = DetectPostprocessor(ready.outputChannels, ready.outputAnchors, conf, iou)

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
        analysisFramesReceived.set(0)
        inferencesRun.set(0)
        skippedWhileBusy.set(0)
        errors.set(0)
        lastError = null
        busy.set(false)
        inferenceEnabled.set(false)
        enabled.set(true)
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

        // t_detect_recv_ns: 탐지 스레드가 그 프레임을 받은 시각.
        // 🔴 frames.csv의 t_recv_ns와 **같은 시계**(elapsedRealtimeNanos = CLOCK_BOOTTIME).
        val tRecvNs = SystemClock.elapsedRealtimeNanos()
        // ⚠ ImageInfo.timestamp는 기준 시계가 기기마다 다르다(§시계 함정) — 원본 그대로
        //   싣고 우리 시계와 빼지 않는다.
        val tCaptureNs = image.imageInfo.timestamp

        val tensor: OnnxTensor
        val eNs: Long
        try {
            // ── E 시작: ImageProxy에서 픽셀을 꺼내는 것부터 ──
            val eStart = System.nanoTime()
            val buffer = preprocessor.convert(image)
            val t = OnnxTensor.createTensor(ready.env, buffer, ready.inputShape)
            eNs = System.nanoTime() - eStart
            // ── E 끝: 입력 텐서가 준비된 시점 ──
            tensor = t
            lastLetterbox = preprocessor.lastLetterbox
        } catch (t: Throwable) {
            busy.set(false)
            noteError("전처리 실패: ${t.javaClass.simpleName}: ${t.message}")
            return
        }

        worker.execute { infer(tensor, tRecvNs, tCaptureNs, eNs) }
    }

    // ── 워커 스레드 ──────────────────────────────────────────────────────

    private fun infer(tensor: OnnxTensor, tRecvNs: Long, tCaptureNs: Long, eNs: Long) {
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

            // ── G: 출력 읽기 + conf 필터 + xyxy + 클래스별 NMS + letterbox 역변환 ──
            val gStart = System.nanoTime()
            val outTensor = result.get(0) as OnnxTensor
            val box = lastLetterbox
                ?: throw IllegalStateException("letterbox 기하가 없다 — 전처리를 거치지 않았다")
            val out = postprocessor.run(outTensor.floatBuffer, box)
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

    private companion object {
        const val TAG = "BammasilDetectPipe"
        const val QUIESCE_POLL_MS = 5L
        const val MICRO = 1_000_000.0
    }
}
