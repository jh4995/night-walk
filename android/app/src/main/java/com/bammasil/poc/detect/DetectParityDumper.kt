package com.bammasil.poc.detect

import android.graphics.ImageFormat
import android.os.Build
import android.util.Log
import androidx.camera.core.ImageProxy
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * ③ **이식 정확성 대조 덤프**. 폰이 실제로 쓴 원본 평면 · 입력 텐서 · 출력 텐서 · 박스를
 * `<run_dir>/parity/`에 남겨 PC가 같은 입력으로 재현·대조할 수 있게 한다.
 *
 * ## 무엇에 답하는가 — 그리고 무엇에 답하지 못하는가
 *
 * `metadata.json`의 `parity_check`는 **상류가 PC에서 PyTorch 대비** 확인한 것이고, **폰 ORT
 * 출력이 그것과 같은지는 별개 문제다.** 지금까지 그 질문에 답할 수단이 없어 "미검증"이라고
 * 쓰기만 했는데, 이 클래스가 그 수단이다.
 *
 * 🔴 **그래도 이것은 "모델이 옳다"의 증거가 아니다** — 정답 라벨이 없으므로 mAP·누락률은
 * 여전히 말할 수 없다. 답하는 것은 **이식이 값을 바꾸지 않았는가**까지다.
 *
 * ## 🔴 포맷 규약이 이 코드보다 위다
 *
 * 파일 이름·바이트 순서·매니페스트 키는 전부
 * `docs/plans/20260806_detect_parity_dump_format.md`가 정한다. 소비자(`scripts/detect_parity.py`)를
 * **다른 사람이 동시에** 만들고 있고 그쪽도 같은 문서를 본다 — 어긋나면 대조 결과가
 * "이식 결함인지 포맷 어긋남인지" 구분할 수 없게 된다. **고칠 이유를 찾으면 문서를 먼저
 * 고치고 양쪽을 맞춘다.**
 *
 * ## 스레드 규약 (어기면 텐서가 섞인다)
 *
 * ```
 * detect-analysis            detect-infer                 GL 스레드(정지 후)
 *   start() ← UI 스레드         write()                      finish()
 *   capture()
 * ```
 * - [start]는 런 시작 직전(UI 스레드), 아직 아무 추론도 없는 시점.
 * - [capture]는 **분석 스레드 전용**이다. [ImageProxy]가 살아 있는 동안에만 평면을 읽을 수
 *   있고, [DetectPreprocessor]가 내부 배열과 `inputBuffer`를 **재사용**하므로 그 자리에서
 *   복사해 둔다 — 워커로 참조만 넘기면 다음 프레임이 덮어쓴다.
 * - [write]는 **탐지 워커 전용**이며 E·F·G를 재는 구간 **밖**에서 불린다.
 * - [finish]는 워커가 quiesce된 뒤(`MainActivity`의 A12 (2) 이후)에만 부른다.
 *
 * ## 🔴 이 arm의 시간은 인용하지 않는다
 *
 * 샘플당 ~7MB를 디스크에 쓴다. `RenderArm.DETECT_PARITY_NOT_QUOTABLE`이 그 사실과 이유를
 * 담고 `session.json`으로 그대로 나간다.
 */
class DetectParityDumper(
    /**
     * 덤프를 **먼저 쌓아 두는** 자리(앱 외부 파일 디렉토리 아래). 런 디렉토리는 정지 시점에
     * 이름이 확정되므로([MainActivity.resolveRunDir]가 중복 접미사를 붙인다) 런 도중에는
     * 여기 쓰고 [finish]에서 `<run_dir>/parity/`로 옮긴다. **같은 볼륨이라 rename이 즉시다.**
     */
    private val stagingRoot: File,
) {

    /** [start]가 준비한 이번 런의 staging 디렉토리. 준비 전/비덤프 arm에서는 null. */
    @Volatile
    private var stagingDir: File? = null

    /** 이번 런이 덤프를 남기는가. 비덤프 arm에서는 false이고 [capture]가 즉시 null을 낸다. */
    @Volatile
    private var enabled = false

    /** 모델이 낸 클래스 이름(인덱스 순). `cls_name`을 여기서만 가져온다 — 지어내지 않는다. */
    @Volatile
    private var classNames: List<String> = emptyList()

    /** 이미 **자리를 잡은** 샘플 수. 분석 스레드가 올리고 [finish]가 읽는다. */
    private val reserved = AtomicInteger(0)

    /**
     * 마지막으로 샘플 자리를 잡은 시각(`elapsedRealtimeNanos`). [NO_CAPTURE]면 아직 없다.
     * **분석 스레드만 쓴다**(클래스 KDoc의 스레드 규약). [finish]가 읽으므로 `@Volatile`.
     */
    @Volatile
    private var lastCaptureNs: Long = NO_CAPTURE

    /** 첫 샘플 시각. 실제로 몇 초에 걸쳐 샘플을 잡았는지를 매니페스트에 싣기 위해 남긴다. */
    @Volatile
    private var firstCaptureNs: Long = NO_CAPTURE

    private val lock = Any()

    /** 파일까지 다 쓴 샘플. 워커가 넣고 [finish]가 읽는다 → [lock]으로 감싼다. */
    private val samples = ArrayList<SampleRecord>()

    /** 사람이 읽는 실패 문장. 🔴 **조용히 넘기지 않는다** — `session.json`으로 나간다. */
    private val failures = ArrayList<String>()

    private var bytesWritten = 0L

    // ── 분석 스레드가 잡아 두는 것 ────────────────────────────────────────

    /** 평면 하나의 **복사본**과 stride. 원본은 [ImageProxy]가 닫히면 사라진다. */
    class PlaneCopy(
        val name: String,
        val bytes: ByteArray,
        val rowStride: Int,
        val pixelStride: Int,
    )

    /**
     * 샘플 하나의 원본 재료. **분석 스레드가 만들어 워커로 넘긴다.**
     * 🔴 전부 **복사본**이다 — 전처리기의 재사용 배열을 그대로 들고 오면 다음 프레임이 덮어쓴다.
     */
    class Capture(
        val index: Int,
        val tRecvNs: Long,
        /**
         * 🔴 **센서 방향 그대로**(회전 전)다. 아래 [planes]도 같다 — 규약 §4-5.
         * 앱이 회전한 결과를 덤프하면 PC는 그 회전을 검사할 수 없고 **E 대조가 회전을
         * 건너뛴다.** PC가 `source.rotation_degrees`/`rotation_site`를 읽어 **같은 회전을
         * 같은 자리에서 자기 힘으로 재현**한다.
         */
        val srcWidth: Int,
        val srcHeight: Int,
        /**
         * 이 프레임의 `ImageProxy.imageInfo.rotationDegrees` **관측값 그대로**다.
         * ⚠ 런이 실제로 **쓴** 각은 첫 프레임에서 잠근 값이고(규약 §4-3) 그쪽은
         * `DetectRotationFacts`가 들고 있다 — 아래 샘플 간 불일치 검사가 이 둘을 잇는다.
         */
        val rotationDegrees: Int,
        val formatName: String,
        val planes: List<PlaneCopy>,
        /** 그 프레임의 입력 텐서 값(NCHW float32). `inputBuffer`가 재사용이라 복사했다. */
        val input: FloatArray,
        /** 🔴 **그 프레임에 실제로 쓴** letterbox 기하다(마지막 프레임 값이 아니다). */
        val letterbox: DetectContract.Letterbox,
    )

    /** 파일까지 다 쓴 샘플 하나. 매니페스트의 `samples[]` 한 항목이 된다. */
    private class SampleRecord(
        val capture: Capture,
        val srcFile: String,
        val srcSha256: String,
        val inputFile: String,
        val inputCount: Int,
        val inputSha256: String,
        val outputFile: String,
        val outputCount: Int,
        val outputSha256: String,
        val boxesPreNms: Int,
        val boxes: List<DetectPostprocessor.Box>,
        /** 🔴 **센 개수**이지 거른 개수가 아니다 — 해당 박스는 [boxes] 안에 그대로 있다. */
        val boxesInverted: Int,
    )

    // ── 시작 / 정리 ──────────────────────────────────────────────────────

    /**
     * 이번 런의 덤프를 준비한다. **UI 스레드에서, 런 시작 직전에** 부른다.
     *
     * @param classNames 모델 임베드 메타에서 읽은 클래스 이름(인덱스 순).
     * @return 시작하지 못한 사유(사람이 읽는 문장). null이면 시작했다.
     *   🔴 **여기서 실패하면 호출자가 런을 거부한다** — 라벨만 `detect_parity_*`이고 덤프가
     *   없는 런을 만들지 않기 위해서다(`DetectRuntime`의 준비 게이트와 같은 취지).
     */
    fun start(classNames: List<String>): String? {
        reset()
        val dir = File(stagingRoot, STAGING_DIR_NAME)
        // 이전 런이 남긴 것이 있으면 지운다 — 섞이면 어느 런의 샘플인지 말할 수 없다.
        if (dir.exists() && !dir.deleteRecursively()) {
            return "③ 대조 덤프 staging 디렉토리를 비우지 못했다: ${dir.absolutePath}"
        }
        if (!dir.mkdirs()) {
            return "③ 대조 덤프 staging 디렉토리를 만들지 못했다: ${dir.absolutePath}"
        }
        this.classNames = classNames
        stagingDir = dir
        enabled = true
        return null
    }

    /** 덤프를 남기지 않는 arm에서 부른다. 이전 런의 상태가 `session.json`에 새지 않게 한다. */
    fun disable() {
        reset()
    }

    private fun reset() {
        enabled = false
        stagingDir = null
        classNames = emptyList()
        reserved.set(0)
        lastCaptureNs = NO_CAPTURE
        firstCaptureNs = NO_CAPTURE
        synchronized(lock) {
            samples.clear()
            failures.clear()
            bytesWritten = 0L
        }
    }

    // ── 분석 스레드 ──────────────────────────────────────────────────────

    /**
     * 샘플 하나의 재료를 **그 자리에서 복사**한다. 덤프 arm이 아니거나 이미 K개를 잡았으면
     * null이다(그때는 아무 일도 하지 않는다 — 비덤프 arm의 프레임 경로를 건드리지 않는다).
     *
     * 🔴 **E를 재는 구간 밖에서 부른다.** 안에서 부르면 이 복사 비용이 `stage_e_ms`에 섞인다.
     *
     * @param input 전처리기의 `inputBuffer`. **재사용 버퍼라 여기서 값을 복사한다.**
     * @param box 그 프레임의 letterbox 기하. 없으면(전처리를 안 거쳤으면) 샘플을 잡지 않는다.
     */
    fun capture(
        image: ImageProxy,
        input: FloatBuffer,
        box: DetectContract.Letterbox?,
        tRecvNs: Long,
    ): Capture? {
        if (!enabled) return null
        if (reserved.get() >= SAMPLE_COUNT) return null
        // 🔴 **샘플을 런 전체에 퍼뜨린다.** 예전에는 게이트가 이 줄뿐이라 처음 K회 추론을
        //    **연속으로** 집었다 — 실기기 첫 대조에서 4개가 0.8~1.6초 안에 몰렸고, 60초를
        //    찍었는데 덤프는 첫 1초만 봤다(추론 311회 중 붙어 있는 4회). 사실상 한 장면이라
        //    "샘플 4개"라는 말이 실제보다 훨씬 후하게 들린다.
        //    ⚠ **첫 샘플은 간격을 묻지 않는다** — 아주 짧은 런에서도 최소 1개는 남아야 한다.
        val last = lastCaptureNs
        if (last != NO_CAPTURE && tRecvNs - last < SAMPLE_MIN_GAP_NS) return null
        if (box == null) {
            note("샘플을 잡지 못했다: letterbox 기하가 없다 — 전처리를 거치지 않았다")
            return null
        }
        val planes = image.planes
        if (planes.size != PLANE_NAMES.size) {
            note("샘플을 잡지 못했다: 평면이 ${planes.size}개다(YUV_420_888이 아니다)")
            return null
        }
        val index = reserved.getAndIncrement()
        // 자리를 잡은 시점에 갱신한다. **분석 스레드 전용**이라 경합이 없다(클래스 KDoc).
        lastCaptureNs = tRecvNs
        if (firstCaptureNs == NO_CAPTURE) firstCaptureNs = tRecvNs
        return try {
            val copies = ArrayList<PlaneCopy>(PLANE_NAMES.size)
            for (i in PLANE_NAMES.indices) {
                val plane = planes[i]
                val buffer = plane.buffer
                buffer.rewind()
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                copies.add(
                    PlaneCopy(PLANE_NAMES[i], bytes, plane.rowStride, plane.pixelStride)
                )
            }
            input.rewind()
            val floats = FloatArray(input.remaining())
            input.get(floats)
            input.rewind()
            Capture(
                index = index,
                tRecvNs = tRecvNs,
                srcWidth = image.width,
                srcHeight = image.height,
                rotationDegrees = image.imageInfo.rotationDegrees,
                formatName = formatName(image.format),
                planes = copies,
                input = floats,
                letterbox = box,
            )
        } catch (t: Throwable) {
            note("샘플 $index 재료 복사 실패: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    // ── 워커 스레드 ──────────────────────────────────────────────────────

    /**
     * 샘플 하나를 파일 셋으로 쓴다. **탐지 워커에서, E·F·G를 재는 구간 밖에서** 부른다.
     *
     * @param output `session.run()`이 낸 출력 텐서의 버퍼. **그 자리에서 복사한다** —
     *   호출자가 곧 `Result`를 닫으므로 나중에 읽을 수 없다.
     * @param boxes 후처리가 낸 최종 박스. 🔴 **① 센서 좌표계**다(규약 §5-2) —
     *   `src.width`/`src.height`와 같은 공간이며 "바로 선" 회전 후 공간이 아니다.
     * @param boxesInverted 그중 `x2<x1` 또는 `y2<y1`인 박스 수. 🔴 **센 개수다**(§5-3).
     */
    fun write(
        capture: Capture,
        output: FloatBuffer,
        boxesPreNms: Int,
        boxes: List<DetectPostprocessor.Box>,
        boxesInverted: Int,
    ) {
        val dir = stagingDir ?: return
        val idx = twoDigits(capture.index)
        try {
            // 🔴 **센서 방향 원본 그대로** 쓴다(규약 §4-5). 회전한 결과를 쓰면 PC가 회전을
            //    검사할 수 없어 E 대조가 회전을 건너뛴다.
            val srcName = "sample_${idx}_src.yuv"
            val srcSha = writePlanes(File(dir, srcName), capture.planes)

            val inputName = "sample_${idx}_input.f32"
            val inputSha = writeFloats(File(dir, inputName), capture.input, capture.input.size)

            output.rewind()
            val outFloats = FloatArray(output.remaining())
            output.get(outFloats)
            val outputName = "sample_${idx}_output.f32"
            val outputSha = writeFloats(File(dir, outputName), outFloats, outFloats.size)

            synchronized(lock) {
                samples.add(
                    SampleRecord(
                        capture = capture,
                        srcFile = srcName,
                        srcSha256 = srcSha,
                        inputFile = inputName,
                        inputCount = capture.input.size,
                        inputSha256 = inputSha,
                        outputFile = outputName,
                        outputCount = outFloats.size,
                        outputSha256 = outputSha,
                        boxesPreNms = boxesPreNms,
                        boxes = boxes,
                        boxesInverted = boxesInverted,
                    )
                )
            }
        } catch (t: Throwable) {
            note("샘플 ${capture.index} 쓰기 실패: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ── 정지 시점(GL 스레드) ─────────────────────────────────────────────

    /**
     * 매니페스트를 쓰고 staging을 `<run_dir>/parity/`로 옮긴다.
     * **워커가 quiesce된 뒤에만** 부른다(A12 (2) 이후).
     *
     * @param report 이 런의 준비 보고. 🔴 **null이면 매니페스트를 쓰지 않는다** —
     *   모델 sha256·EP·클래스를 지어낼 수 없고, 그 값 없는 덤프는 대조에 쓸 수 없다.
     * @param rotation 이 런의 회전 사실(규약 §4). 🔴 **`session.json`과 같은 객체에서
     *   나온다** — 두 곳이 각자 계산하면 갈리는 날이 온다.
     * @return 덤프 arm이 아니었으면 null.
     */
    fun finish(
        runDir: File,
        report: DetectReport?,
        rotation: DetectRotationFacts,
    ): DetectParityResult? {
        if (!enabled) return null
        val dir = stagingDir
        val requested = SAMPLE_COUNT
        val captured: Int
        val localFailures: List<String>
        val written: Long
        val records: List<SampleRecord>
        synchronized(lock) {
            records = ArrayList(samples)
            captured = records.size
            written = bytesWritten
            localFailures = ArrayList(failures)
        }
        if (dir == null) {
            return DetectParityResult(
                requestedSamples = requested,
                capturedSamples = 0,
                dirName = null,
                files = emptyList(),
                manifestWritten = false,
                moveMethod = "옮기지 않았다 — staging 디렉토리가 없다",
                bytesWritten = 0L,
                failures = localFailures + "staging 디렉토리가 없다 — start()를 거치지 않았다",
            )
        }

        val extraFailures = ArrayList<String>(localFailures)
        // 🔴 잡아 둔 자리 수와 실제로 쓴 샘플 수가 다르면 어딘가에서 조용히 사라진 것이다.
        val reservedCount = reserved.get()
        if (reservedCount != captured) {
            extraFailures += "자리를 잡은 샘플 $reservedCount 개 중 $captured 개만 파일이 됐다 " +
                "— 나머지는 쓰기 실패이거나 런이 그 추론 전에 멈춘 것이다"
        }

        var manifestWritten = false
        if (records.isEmpty()) {
            extraFailures += "샘플이 0개다 — 매니페스트를 쓰지 않았다(빈 매니페스트를 내면 " +
                "PC가 '덤프가 있다'고 읽는다). 런이 너무 짧았거나 추론이 한 번도 돌지 않았다"
        } else if (report == null) {
            extraFailures += "🔴 ③ 준비 보고가 없어 매니페스트를 쓰지 않았다 — 모델 sha256·EP·" +
                "클래스를 지어낼 수 없고, 그 값이 없는 덤프는 대조에 쓸 수 없다. " +
                "샘플 파일은 그대로 남긴다"
        } else {
            manifestWritten = try {
                writeManifest(File(dir, MANIFEST_NAME), report, rotation, records, extraFailures)
                true
            } catch (t: Throwable) {
                extraFailures += "매니페스트 쓰기 실패: ${t.javaClass.simpleName}: ${t.message}"
                false
            }
        }

        val dest = File(runDir, RUN_DIR_NAME)
        val moveMethod = moveInto(dir, dest, extraFailures)
        val files = (dest.listFiles()?.map { it.name } ?: emptyList()).sorted()

        val result = DetectParityResult(
            requestedSamples = requested,
            capturedSamples = captured,
            dirName = if (dest.isDirectory) RUN_DIR_NAME else null,
            files = files,
            manifestWritten = manifestWritten,
            moveMethod = moveMethod,
            bytesWritten = written,
            failures = extraFailures,
        )
        // 다음 런이 이 상태를 물려받지 않게 한다(arm을 바꿔 연속 측정할 수 있다).
        reset()
        return result
    }

    // ── 매니페스트 ───────────────────────────────────────────────────────

    /**
     * `parity.json`. 🔴 **키와 값의 모양은 규약 문서 §3이 정한다** — 여기서 임의로 바꾸지
     * 않는다(고칠 이유를 찾으면 문서를 먼저 고친다).
     */
    private fun writeManifest(
        file: File,
        report: DetectReport,
        rotation: DetectRotationFacts,
        records: List<SampleRecord>,
        failures: MutableList<String>,
    ) {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("byte_order", BYTE_ORDER)

        root.put(
            "model",
            JSONObject()
                // 🔴 앱이 **로드한 파일 바이트에서 직접 계산한** 값이다(metadata.json의 사본이
                //    아니다). PC는 자기가 여는 .onnx의 해시와 대조해 다르면 죽는다.
                .put("sha256", report.sha256Computed ?: JSONObject.NULL)
                .put("asset", DetectContract.declaredFileName)
                .put("input_name", report.inputName ?: JSONObject.NULL)
                .put("input_shape", JSONArray(report.inputShape))
                .put("output_name", report.outputName ?: JSONObject.NULL)
                .put("output_shape", JSONArray(report.outputShape))
        )

        // 🔴 **런타임 좌표를 매니페스트에 함께 싣는다.** 이 라운드의 핵심 주장이
        //    "폰과 PC가 같은 ORT 버전이니 값이 다르면 그건 버전 차이가 아니라 빌드/플랫폼
        //    차이다"인데, 그 전제를 `parity/`만 보고는 확인할 수 없었다 —
        //    `session.json`에만 있었고 PC 대조는 `parity/`만 읽는다(규약 §3의 "디렉토리만
        //    떼어 가도 대조가 성립하게 한다"가 바로 이 취지다).
        //    ⚠ `declared`는 build.gradle.kts에 우리가 **적은** 값이고 `runtime`은
        //    OrtEnvironment.getVersion()이 **말한** 값이다. PC는 runtime 쪽을 쓴다.
        root.put(
            "runtime",
            JSONObject()
                .put("ort_package", report.ortPackage)
                .put("ort_version_declared", report.ortVersion)
                .put("ort_version_runtime", report.ortVersionRuntime ?: JSONObject.NULL)
                .put("ort_version_mismatch", report.ortVersionMismatch ?: JSONObject.NULL)
                .put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
                .put(
                    "note",
                    "폰 쪽 좌표다. PC 쪽(onnxruntime·numpy 버전)은 대조 스크립트가 자기 " +
                        "summary.json에 남긴다. 🔴 **ort_version_runtime이 null이면 " +
                        "'같은 버전이다'라고 말할 수 없다** — 그 경우 버전 대조는 미검증이다"
                )
        )

        // 🔴 **샘플이 언제 잡혔는지를 함께 싣는다.** 이게 없으면 0.8초에 몰린 4개와 40초에
        //    걸친 4개가 보고서에서 똑같이 "샘플 4개"로 보인다 — 실제로 그 일이 났다.
        val spanNs = if (firstCaptureNs == NO_CAPTURE || lastCaptureNs == NO_CAPTURE) {
            -1L
        } else {
            lastCaptureNs - firstCaptureNs
        }
        root.put(
            "sampling",
            JSONObject()
                .put("count_target", SAMPLE_COUNT)
                .put("count_captured", records.size)
                .put("min_gap_ms", SAMPLE_MIN_GAP_MS)
                .put("span_sec", if (spanNs < 0) JSONObject.NULL else spanNs / 1e9)
                .put(
                    "note",
                    "샘플은 **최소 ${SAMPLE_MIN_GAP_MS / 1000}초 간격**으로 잡는다(첫 샘플만 " +
                        "예외 — 아주 짧은 런에서도 하나는 남게). count_captured가 " +
                        "count_target보다 적으면 **런이 짧았던 것**이지 결함이 아니다. " +
                        "🔴 count_target·min_gap_ms는 우리가 선언한 **측정 조건**이지 계약값이 " +
                        "아니고, 이 표본은 **통계적 표본이 아니다**(규약 §9)"
                )
        )

        val nodeCounts = JSONObject()
        for ((provider, count) in report.providerNodeCounts) nodeCounts.put(provider, count)
        root.put(
            "ep",
            JSONObject()
                .put("requested", report.epRequested)
                // 🔴 판별하지 못했으면 unknown이며 그것은 "CPU였다"와 다른 사실이다.
                .put("resolved", report.epResolved)
                .put("node_counts", nodeCounts)
        )

        val names = JSONObject()
        report.classNames.forEachIndexed { i, n -> names.put(i.toString(), n) }
        root.put(
            "classes",
            JSONObject()
                .put("names", names)
                .put("count", report.classNames.size)
                .put("raw", report.classNamesRaw ?: JSONObject.NULL)
        )

        root.put(
            "thresholds",
            JSONObject()
                // 못 읽었으면 애초에 런이 시작되지 않는다(DetectContract.thresholdFailure).
                // 🔴 `conf`는 후처리가 **실제로 쓴 값**이다(오버라이드가 걸리면 metadata의
                //    선언값이 아니다) — 그래서 선언값과 오버라이드 여부를 옆에 함께 싣는다.
                //    다음 세션이 이 덤프를 상류 결과와 대조할 때 임계가 같은지 먼저 봐야 한다.
                .put("conf", DetectContract.confThreshold?.toDouble() ?: JSONObject.NULL)
                .put("iou", DetectContract.iouThreshold?.toDouble() ?: JSONObject.NULL)
                .put(
                    "conf_declared_in_metadata",
                    DetectContract.confDeclaredInMetadata?.toDouble() ?: JSONObject.NULL
                )
                .put("conf_override_active", DetectContract.confOverrideActive)
                .put(
                    "note",
                    "🔴 `conf`는 후처리가 실제로 쓴 값이다. conf_override_active가 true면 " +
                        "conf_declared_in_metadata와 다르며, 사유 전문은 session.json의 " +
                        "detect.postprocess.conf_source에 있다. **임계가 다른 덤프끼리 박스를 " +
                        "대조하지 말 것** — 통과 박스 집합 자체가 달라진다"
                )
        )

        val first = records[0].capture
        // 🔴 **이 검사는 유지한다**(규약 §4-3). 회전각을 첫 프레임에서 잠근 뒤로는 샘플 간
        //    불일치가 나오는 것 자체가 결함이다 — 잠근 값을 쓰는데 관측값이 갈렸다는 뜻이고,
        //    그 사실은 rotation_changed_frames와 함께 읽어야 한다.
        if (records.any { it.capture.rotationDegrees != first.rotationDegrees }) {
            failures += "샘플마다 관측 rotation_degrees가 다르다(첫 샘플 " +
                "${first.rotationDegrees}). 🔴 회전각은 첫 분석 프레임에서 잠그므로" +
                "(규약 §4-3) 이 불일치는 그 자체가 결함이다 — 매니페스트의 " +
                "source.rotation_degrees는 **잠근 값**(${rotation.degrees})이고 " +
                "rotation_changed_frames=${rotation.changedFrames}가 몇 번 갈렸는지 말한다"
        }
        // 🔴 규약 §4. 값의 출처는 **런타임이 잠근 사실 하나**이고 여기서 다시 정하지 않는다.
        //    §4-2: rotation_applied=false + rotation_site=preprocess_*는 **모순**이고 PC는
        //    그것을 읽으면 죽는다. 두 값이 같은 객체에서 나오므로 그 조합은 생길 수 없다.
        root.put(
            "source",
            JSONObject()
                // 잠근 값이다(관측값 그대로가 아니다 — 위 불일치 검사가 그 차이를 잡는다).
                .put("rotation_degrees", rotation.degrees)
                .put("rotation_applied", rotation.applied)
                .put("rotation_site", rotation.site)
                // 🔴 **회전 후 치수**. 90/270이면 아래 src.width/height와 스왑이다.
                //    회전을 적용하지 않은 arm에서는 센서 치수와 같고, 그래야 각 샘플의
                //    letterbox.src_w/src_h와 어긋나지 않는다(규약 §5).
                .put("rotated_width", rotation.rotatedWidth)
                .put("rotated_height", rotation.rotatedHeight)
                .put("rotation_locked", rotation.locked)
                .put("rotation_changed_frames", rotation.changedFrames)
                .put("format", first.formatName)
        )
        // 🔴 매니페스트가 자기 모순을 안고 나가지 않게 여기서 먼저 잡는다 — PC가 죽는
        //    자리를 폰 로그 없이 되묻게 되면 그 런이 통째로 낭비된다.
        if (!rotation.applied && rotation.site != DetectContract.ROTATION_SITE_NONE) {
            failures += "🔴 모순: rotation_applied=false인데 rotation_site=" +
                "${rotation.site}다(규약 §4-2). PC는 이 매니페스트를 읽으면 죽는다"
        }
        if (rotation.applied && rotation.site == DetectContract.ROTATION_SITE_NONE) {
            failures += "🔴 모순: rotation_applied=true인데 rotation_site=none이다(규약 §4-2)"
        }
        val expectedRotated = records.all {
            it.capture.letterbox.srcW == rotation.rotatedWidth &&
                it.capture.letterbox.srcH == rotation.rotatedHeight
        }
        if (!expectedRotated) {
            failures += "🔴 source.rotated_*(${rotation.rotatedWidth}x" +
                "${rotation.rotatedHeight})와 샘플의 letterbox.src_w/src_h가 다르다 — " +
                "규약 §5는 그 둘이 같기를 요구한다(letterbox는 **회전 후 치수**에서 계산된다)"
        }

        val shapeProduct = { shape: List<Long> ->
            var n = 1L
            for (d in shape) n *= d
            n
        }
        val inputElements = shapeProduct(report.inputShape)
        val outputElements = shapeProduct(report.outputShape)

        val samplesJson = JSONArray()
        for (record in records) {
            // 🔴 파일 크기와 선언 shape이 어긋나면 PC가 죽는다(규약 §2). 그 사실을 여기서 먼저
            //    잡아 두지 않으면 "왜 죽었는지"를 폰 로그 없이 되물어야 한다.
            if (record.inputCount.toLong() != inputElements) {
                failures += "샘플 ${record.capture.index}: 입력 원소 수 ${record.inputCount}가 " +
                    "선언 shape의 곱 $inputElements 과 다르다"
            }
            if (record.outputCount.toLong() != outputElements) {
                failures += "샘플 ${record.capture.index}: 출력 원소 수 ${record.outputCount}가 " +
                    "선언 shape의 곱 $outputElements 과 다르다"
            }
            samplesJson.put(buildSample(record, report.inputShape, report.outputShape))
        }
        root.put("samples", samplesJson)

        file.writeText(root.toString(2))
    }

    /**
     * @param inputShape / [outputShape] **그래프에서 읽은 값**이다(앱 코드에 640도 8400도
     *   없다). 파일의 원소 수와 이 곱이 다르면 PC가 죽으므로 [writeManifest]가 먼저 대조한다.
     */
    private fun buildSample(
        record: SampleRecord,
        inputShape: List<Long>,
        outputShape: List<Long>,
    ): JSONObject {
        val capture = record.capture
        val json = JSONObject()
        json.put("index", capture.index)
        // frames.csv / detect.csv 와 **같은 시계**(elapsedRealtimeNanos = CLOCK_BOOTTIME).
        json.put("t_recv_ns", capture.tRecvNs)

        val planes = JSONArray()
        var offset = 0L
        for (plane in capture.planes) {
            planes.put(
                JSONObject()
                    .put("name", plane.name)
                    .put("offset", offset)
                    .put("length", plane.bytes.size)
                    .put("row_stride", plane.rowStride)
                    .put("pixel_stride", plane.pixelStride)
            )
            offset += plane.bytes.size
        }
        json.put(
            "src",
            JSONObject()
                .put("file", record.srcFile)
                // 🔴 **센서 방향 그대로**(회전 전)다 — 규약 §3의 주석과 §4-5.
                .put("width", capture.srcWidth)
                .put("height", capture.srcHeight)
                .put("planes", planes)
                // ⚠ 규약 §3의 예시에는 src에 sha256이 없다. **더 실은 것**이며 뜻은 같다 —
                //   adb pull이 깨진 것을 이식 결함으로 오독하지 않기 위한 값이다.
                .put("sha256", record.srcSha256)
        )

        val box = capture.letterbox
        json.put(
            "letterbox",
            JSONObject()
                // 🔴 **v1.2(/2)의 핵심 변화**: 이 블록은 ② **회전 후** 치수에서 계산된
                //    값이다(① 센서가 아니다). 90/270°에서 pad_x와 pad_y가 자리를 바꾸므로
                //    /1로 읽으면 종횡비가 뒤집힌다 — 그래서 format을 올렸다(규약 §3-3).
                //    src_w/src_h는 그 기준 치수를 명시한 것이고 source.rotated_*와 같다.
                .put("src_w", box.srcW)
                .put("src_h", box.srcH)
                .put("scale", box.scale.toDouble())
                .put("pad_x", box.padX)
                .put("pad_y", box.padY)
                .put("content_w", box.contentW)
                .put("content_h", box.contentH)
                .put("pad_value_u8", DetectContract.PAD_VALUE_U8)
                .put("align", DetectContract.LETTERBOX_ALIGN)
        )

        json.put(
            "input",
            JSONObject()
                .put("file", record.inputFile)
                .put("dtype", DTYPE)
                .put("layout", INPUT_LAYOUT)
                .put("shape", JSONArray(inputShape))
                .put("sha256", record.inputSha256)
        )
        json.put(
            "output",
            JSONObject()
                .put("file", record.outputFile)
                .put("dtype", DTYPE)
                .put("layout", OUTPUT_LAYOUT)
                .put("shape", JSONArray(outputShape))
                .put("sha256", record.outputSha256)
        )

        json.put("boxes_pre_nms", record.boxesPreNms)
        // 🔴 **① 센서 좌표계**다(규약 §5-2) — src.width/height와 같은 공간이며 회전 후
        //    공간이 아니다. 두 공간의 좌표를 동시에 싣지 않는다(반드시 어긋나는 날이 온다).
        val boxes = JSONArray()
        for (b in record.boxes) {
            boxes.put(
                JSONObject()
                    .put("cls", b.cls)
                    // 🔴 이름을 지어내지 않는다 — 모델이 낸 목록에 없으면 null이다.
                    .put("cls_name", classNames.getOrNull(b.cls) ?: JSONObject.NULL)
                    .put("conf", b.conf.toDouble())
                    .put("x1", b.x1.toDouble())
                    .put("y1", b.y1.toDouble())
                    .put("x2", b.x2.toDouble())
                    .put("y2", b.y2.toDouble())
            )
        }
        json.put("boxes", boxes)
        // 🔴 규약 §5-3 — **거른 개수가 아니라 센 개수다.** 해당 박스는 위 boxes 배열에
        //    그대로 들어 있다. 거르면 면적 0인 박스가 화면 가장자리에 남아 ④가 얇은 선을
        //    그리고, 조용히 지우면 결함이 숨는다.
        json.put("boxes_inverted", record.boxesInverted)
        return json
    }

    // ── 파일 쓰기 ────────────────────────────────────────────────────────

    /** 평면 3개를 **이어 붙여** 쓴다(규약 §2). 반환값은 쓴 바이트의 sha256이다. */
    private fun writePlanes(file: File, planes: List<PlaneCopy>): String {
        val digest = MessageDigest.getInstance(SHA_ALGORITHM)
        var total = 0L
        BufferedOutputStream(FileOutputStream(file), IO_BUFFER_BYTES).use { out ->
            for (plane in planes) {
                out.write(plane.bytes)
                digest.update(plane.bytes)
                total += plane.bytes.size
            }
        }
        addBytes(total)
        return hex(digest.digest())
    }

    /**
     * 헤더 없는 **생 float32 나열**. 바이트 순서는 **little-endian 고정**이다 —
     * 네이티브 순서에 맡기지 않는다(규약 §2가 `byte_order`로 그 사실을 싣는다).
     */
    private fun writeFloats(file: File, data: FloatArray, count: Int): String {
        val digest = MessageDigest.getInstance(SHA_ALGORITHM)
        val chunk = ByteBuffer.allocate(CHUNK_FLOATS * 4).order(ByteOrder.LITTLE_ENDIAN)
        val view = chunk.asFloatBuffer()
        val bytes = chunk.array()
        var total = 0L
        BufferedOutputStream(FileOutputStream(file), IO_BUFFER_BYTES).use { out ->
            var i = 0
            while (i < count) {
                val n = if (count - i < CHUNK_FLOATS) count - i else CHUNK_FLOATS
                view.clear()
                view.put(data, i, n)
                out.write(bytes, 0, n * 4)
                digest.update(bytes, 0, n * 4)
                total += n * 4
                i += n
            }
        }
        addBytes(total)
        return hex(digest.digest())
    }

    /**
     * staging → `<run_dir>/parity/`. 같은 볼륨이라 rename이 즉시지만, **실패하면 복사로
     * 되돌린다** — 옮기지 못해 샘플을 잃는 것이 이 라운드에서 가장 비싼 실패다.
     */
    private fun moveInto(src: File, dest: File, failures: MutableList<String>): String {
        if (dest.exists() && !dest.deleteRecursively()) {
            failures += "런 디렉토리에 이미 parity/가 있고 지우지 못했다: ${dest.absolutePath}"
            return "옮기지 못했다"
        }
        if (src.renameTo(dest)) return "renameTo (같은 볼륨)"
        // 폴백: 파일 단위 복사. 느리지만 기록이 이미 멈춘 뒤라 측정에 섞이지 않는다.
        if (!dest.isDirectory && !dest.mkdirs()) {
            failures += "parity 디렉토리를 만들지 못했다: ${dest.absolutePath}"
            return "옮기지 못했다 (rename 실패 + mkdir 실패)"
        }
        var copied = 0
        for (child in src.listFiles() ?: emptyArray()) {
            try {
                child.inputStream().use { input ->
                    File(dest, child.name).outputStream().use { output -> input.copyTo(output) }
                }
                copied++
            } catch (t: Throwable) {
                failures += "복사 실패: ${child.name} — ${t.javaClass.simpleName}: ${t.message}"
            }
        }
        if (!src.deleteRecursively()) {
            failures += "staging 디렉토리를 지우지 못했다: ${src.absolutePath}"
        }
        return "파일 복사 $copied 개 (renameTo 실패)"
    }

    // ── 보조 ─────────────────────────────────────────────────────────────

    private fun addBytes(n: Long) {
        synchronized(lock) { bytesWritten += n }
    }

    private fun note(message: String) {
        Log.w(TAG, message)
        synchronized(lock) { failures.add(message) }
    }

    /**
     * `ImageProxy.format` → 이름. ⚠ `CameraFrameSource.formatName`과 **같은 표**다
     * (그쪽은 private이고 이 클래스는 source 패키지를 모른다). 값이 갈리면 매니페스트의
     * `source.format`과 `session.json`의 `camera_analysis_actual.input_format`이 어긋난다.
     */
    private fun formatName(format: Int): String = when (format) {
        ImageFormat.YUV_420_888 -> "YUV_420_888"
        ImageFormat.FLEX_RGBA_8888 -> "FLEX_RGBA_8888"
        else -> "unknown($format)"
    }

    /** 2자리 0채움(규약 §2). `String.format`을 쓰지 않는다 — Locale 함정. */
    private fun twoDigits(value: Int): String = if (value < 10) "0$value" else value.toString()

    private fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            out.append(HEX[(b.toInt() ushr 4) and 0xF])
            out.append(HEX[b.toInt() and 0xF])
        }
        return out.toString()
    }

    companion object {

        private const val TAG = "BammasilParity"

        /**
         * 샘플 수 K. **기본 4**이며 근거는 규약 §9다: 입력 텐서 하나가 4.9MB라 K가 커지면
         * pull이 오래 걸리고, 이식 정확성은 장면 다양성보다 **연산 경로 일치**의 문제라
         * 표본이 많을 필요가 없다.
         *
         * ⚠ **통계적 표본이 아니다.** "4장에서 일치했다"를 "항상 일치한다"로 쓰지 않는다.
         */
        const val SAMPLE_COUNT = 4

        /**
         * 샘플 사이 **최소 간격**. 🔴 **측정 조건이지 계약값이 아니다**([SAMPLE_COUNT]와 같다).
         *
         * 왜 생겼는가: 이 게이트가 없던 첫 실기기 대조에서 샘플 4개가 **0.8~1.6초 안에**
         * 몰렸다. 60초를 찍고 추론이 311회 돌았는데 덤프는 **붙어 있는 첫 4회**만 봤다 —
         * 사실상 한 장면이고, 그런데도 보고서에는 "샘플 4개"라고 적힌다.
         *
         * 10초를 고른 이유: 실측 실행 주기가 ~300ms이므로(1회 251~263ms) 10초면 그 사이에
         * 30회쯤 추론이 지나가고 장면이 실제로 바뀐다. 60초 런이면 4개가 30초 이상에 걸친다.
         * ⚠ **런이 짧으면 K개를 못 채운다** — 그건 결함이 아니라 사실이고, 매니페스트의
         * `sampling`이 목표·실제·실제 걸친 시간을 함께 실어 그 사실이 드러나게 한다.
         */
        const val SAMPLE_MIN_GAP_MS = 10_000L
        const val SAMPLE_MIN_GAP_NS = SAMPLE_MIN_GAP_MS * 1_000_000L

        /** [lastCaptureNs]의 "아직 없음". 0은 유효한 시각일 수 있어 쓰지 않는다. */
        const val NO_CAPTURE = Long.MIN_VALUE

        /**
         * 🔴 PC는 이 문자열이 다르면 죽는다(규약 §3).
         *
         * **v1.2에서 `/1` → `/2`로 올렸다**(§3-3). 필드가 늘어서가 아니라 **기존 필드의 뜻이
         * 바뀌어서**다: `letterbox.*`가 센서 치수가 아니라 **회전 후 치수**에서 계산되고,
         * `boxes`가 **센서 좌표로 되돌린 값**이 됐다. 같은 키가 다른 것을 말하므로 버전을
         * 올린다 — 선택 필드로 구분하면 *"letterbox를 읽기 전에 다른 키를 먼저 봐야 하는"*
         * 포맷이 되고 그건 조용히 틀릴 자리를 하나 더 만드는 것이다.
         */
        const val FORMAT = "bammasil-detect-parity/2"

        /** 🔴 PC는 이 값이 아니면 **읽지 않고 죽는다**(규약 §2). 그래서 LE로 **고정**해 쓴다. */
        const val BYTE_ORDER = "little_endian"

        const val MANIFEST_NAME = "parity.json"

        /** 런 디렉토리 안의 이름(규약 §2). `frames.csv`·`session.json`과 같은 자리다. */
        const val RUN_DIR_NAME = "parity"

        /** 런 도중 쌓아 두는 자리. 런 디렉토리 이름이 정지 시점에야 확정되기 때문이다. */
        const val STAGING_DIR_NAME = "current"

        const val DTYPE = "float32"
        const val INPUT_LAYOUT = "NCHW"
        const val OUTPUT_LAYOUT = "NCA"

        private val PLANE_NAMES = listOf("y", "u", "v")

        private const val SHA_ALGORITHM = "SHA-256"
        private const val IO_BUFFER_BYTES = 1 shl 16
        private const val CHUNK_FLOATS = 1 shl 14

        private val HEX = "0123456789abcdef".toCharArray()
    }
}

/**
 * 이 런이 남긴 ③ 대조 덤프의 사실. `session.json`의 `detect.parity` 블록이 된다.
 *
 * 🔴 **앱이 맞춰 주지 않는다** — 샘플이 모자라거나 실패가 있으면 그대로 싣는다.
 * 덤프가 반쪽인 채로 PC가 대조를 돌리면 그 결과의 뜻이 사라지기 때문이다.
 */
class DetectParityResult(
    /** 이 런이 목표한 샘플 수([DetectParityDumper.SAMPLE_COUNT]). */
    val requestedSamples: Int,
    /** 파일까지 실제로 쓴 샘플 수. */
    val capturedSamples: Int,
    /** 런 디렉토리 안의 덤프 디렉토리 이름. 못 남겼으면 null. */
    val dirName: String?,
    val files: List<String>,
    val manifestWritten: Boolean,
    val moveMethod: String,
    val bytesWritten: Long,
    /** 사람이 읽는 실패 문장들. 비어 있으면 실패가 없었다는 뜻이다. */
    val failures: List<String>,
)
