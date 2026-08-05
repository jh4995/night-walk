package com.bammasil.poc.detect

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.bammasil.poc.BuildConfig
import com.bammasil.poc.gl.RenderArm
import org.json.JSONArray
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * ③ 탐지용 ONNX Runtime 세션의 **준비와 EP 판별**.
 *
 * 이번 라운드의 범위는 **여기까지다** — `ImageAnalysis`도 전처리도 후처리도 붙이지 않는다.
 * 프레임당 추론이 없으므로 `detect.csv`도 나오지 않고 `detect.enabled`는 false다
 * (`SessionWriter.buildDetect` 참고).
 *
 * ## 이 클래스가 지키는 것
 *
 * 1. 🔴 **조용한 실패 경로를 만들지 않는다.** 모델을 못 찾거나 sha256이 어긋나면 [prepare]가
 *    `ok=false`를 내고 `MainActivity`가 **런 자체를 거부**한다. 탐지 없이 도는 ③ arm 런은
 *    이 저장소가 실제로 당한 실패다(11분 런 하나를 조용한 폴백으로 날렸다).
 * 2. 🔴 **아무것도 추측하지 않는다.** 입력 크기·클래스 이름·클래스 개수는 전부 그래프와
 *    임베드 메타데이터에서 읽고, 선언값(`metadata.json`)과 대조만 한다. 어긋나면 죽는다.
 * 3. 🔴 **EP를 판별하지 못하면 `unknown`이다.** 요청값을 결과 칸에 베끼지 않는다.
 *
 * ## 스레드
 *
 * 모든 ORT 작업은 내부 단일 스레드 executor에서 돈다. 세션 생성은 수백 ms~수 초가 걸리고
 * 모델 파일 sha256도 10MB를 읽으므로 **UI/GL 스레드에 올리면 안 된다.**
 */
class DetectRuntime(
    /** `getExternalFilesDir(null)`. null이면 모델을 찾을 수 없다(그것도 실패다). */
    private val externalFilesDir: File?,
    /** 프로파일 JSON을 쓸 디렉토리(앱 cacheDir). 쓰기 불가면 프로파일 세션 생성이 실패한다. */
    private val profileDir: File,
) {

    private val worker: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "detect-init") }

    /** 열려 있는 **측정 세션**. 다음 라운드의 추론이 이것을 쓴다. */
    private var measuredSession: OrtSession? = null
    private var environment: OrtEnvironment? = null

    /** 마지막 준비 결과. GL/UI 스레드가 읽는다. */
    @Volatile
    var report: DetectReport? = null
        private set

    /** 준비 중인가. UI가 "아직 준비 중"을 말할 수 있어야 한다. */
    @Volatile
    var preparing: Boolean = false
        private set

    /**
     * [arm]에 맞는 세션을 준비한다. **비동기다** — [onDone]은 워커 스레드에서 불린다.
     *
     * 이미 같은 arm으로 준비돼 있으면 다시 열지 않는다. arm이 다르면(=EP나 프로파일러 설정이
     * 다르면) 기존 세션을 닫고 새로 연다 — 한 세션은 EP 요청 하나에 묶여 있다.
     */
    fun prepareAsync(arm: RenderArm, onDone: (DetectReport) -> Unit) {
        val current = report
        if (current != null && current.arm == arm) {
            onDone(current)
            return
        }
        preparing = true
        worker.execute {
            val result = try {
                prepare(arm)
            } catch (t: Throwable) {
                // 여기까지 새는 예외는 우리가 예상하지 못한 것이다. 삼키지 않고 보고로 만든다.
                Log.e(TAG, "③ 준비 중 예상 못 한 예외", t)
                failed(arm, "예상 못 한 예외: ${t.javaClass.simpleName}: ${t.message}")
            }
            report = result
            preparing = false
            Log.i(TAG, result.oneLine())
            onDone(result)
        }
    }

    /** 세션을 닫는다. 워커에서 순차 실행되므로 준비 중이면 그 뒤에 닫힌다. */
    fun shutdown() {
        worker.execute { closeMeasured() }
        worker.shutdown()
    }

    // ── 준비 본체 ────────────────────────────────────────────────────────

    private fun prepare(arm: RenderArm): DetectReport {
        closeMeasured()

        val epRequested = arm.detectEpRequested
            ?: return failed(arm, "EP를 요청하지 않는 arm이다 (arm=${arm.id})")

        // 0) 선언값이 없으면 대조가 불가능하다 → 시작하지 않는다.
        if (DetectContract.declaredMissing) {
            return failed(
                arm,
                "빌드 시점에 ${DetectContract.declaredSource}를 읽지 못해 선언값이 없다 — " +
                    "sha256·클래스·shape을 대조할 기준이 없으므로 런을 시작하지 않는다. " +
                    "리포지토리 루트에 models/det_c4b_loli0_640/metadata.json이 있는 상태로 " +
                    "다시 빌드할 것",
            )
        }

        // 1) 모델 파일. 🔴 없으면 명시적으로 죽는다(조용히 탐지 없이 도는 경로를 만들지 않는다).
        val dir = externalFilesDir
            ?: return failed(arm, "getExternalFilesDir(null)이 null이다 — 모델을 찾을 수 없다")
        val modelFile = File(File(dir, DetectContract.MODELS_SUBDIR), DetectContract.declaredFileName)
        if (!modelFile.isFile) {
            return failed(
                arm,
                "모델 파일이 없다: ${modelFile.absolutePath}\n" +
                    "adb push models/det_c4b_loli0_640/${DetectContract.declaredFileName} " +
                    "<외부 파일 디렉토리>/${DetectContract.MODELS_SUBDIR}/ 를 먼저 할 것",
            )
        }

        // 2) 🔴 sha256을 **앱이 파일 바이트에서 직접** 계산해 선언값과 대조한다.
        //    metadata.json의 값을 베껴 넣으면 그건 주장이지 사실이 아니다.
        val hashStart = SystemClock.elapsedRealtime()
        val computed = try {
            sha256Of(modelFile)
        } catch (t: Throwable) {
            return failed(arm, "sha256 계산 실패: ${t.javaClass.simpleName}: ${t.message}")
        }
        val hashMs = SystemClock.elapsedRealtime() - hashStart
        val matches = computed.equals(DetectContract.declaredSha256, ignoreCase = true)
        if (!matches) {
            return failed(
                arm,
                "🔴 모델 sha256 불일치 — 런을 시작하지 않는다.\n" +
                    "계산값 = $computed\n" +
                    "선언값 = ${DetectContract.declaredSha256} " +
                    "(${DetectContract.declaredSource})\n" +
                    "어느 모델로 잰 숫자인지 말할 수 없는 런은 무가치하다",
            )
        }

        // 3) NNAPI 가드. 🔴 미지원이면 **CPU로 조용히 떨어뜨리지 않고** 실패로 낸다 —
        //    떨어뜨리면 그게 정확히 이 라운드가 잡으려는 실패 양식이다.
        val nnapiGuard: String
        if (epRequested == DetectContract.EP_NNAPI) {
            if (Build.VERSION.SDK_INT < NNAPI_MIN_SDK) {
                return failed(
                    arm,
                    "NNAPI EP는 API $NNAPI_MIN_SDK 이상이 필요한데 이 기기는 " +
                        "API ${Build.VERSION.SDK_INT}다 (앱 minSdk는 26). " +
                        "CPU로 대체하지 않는다 — 그러면 nnapi arm에 CPU 숫자가 들어간다",
                )
            }
            nnapiGuard = "API ${Build.VERSION.SDK_INT} >= $NNAPI_MIN_SDK — 요청 가능"
        } else {
            nnapiGuard = "해당 없음 (ep.requested=$epRequested)"
        }

        // 4) 환경. verbose로 연다 — 세션 생성 시 노드 배치 요약이 logcat에 찍히고, 그것이
        //    EP 판별의 2순위 근거다. 측정 세션은 아래에서 다시 WARNING으로 눌러 둔다.
        val envStart = SystemClock.elapsedRealtime()
        val env = try {
            OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_VERBOSE, ENV_ID)
        } catch (t: Throwable) {
            return failed(
                arm,
                "OrtEnvironment 생성 실패: ${t.javaClass.simpleName}: ${t.message} " +
                    "(네이티브 라이브러리 로드 실패일 수 있다 — abiFilters와 기기 ABI 확인)",
            )
        }
        environment = env
        val envMs = SystemClock.elapsedRealtime() - envStart

        val available = try {
            // ⚠ **빌드에 컴파일된 EP 목록일 뿐이다.** "실제로 쓰였나"에 대해 아무것도
            //   말하지 않는다. 그래도 기록은 한다 — "무엇이 요청 가능했나"의 유일한 근거다.
            OrtEnvironment.getAvailableProviders().map { it.name }.sorted()
        } catch (t: Throwable) {
            emptyList()
        }

        val warnings = mutableListOf<String>()

        // 5) 프로브 세션 — EP 판별 전용. 프로파일러를 켜고 더미 추론 1회.
        val probe = openSession(env, modelFile, epRequested, profiling = true, verbose = true)
        if (probe.session == null) {
            return failed(arm, "프로브 세션 생성 실패: ${probe.error}")
        }
        val probeSession = probe.session

        // 그래프 검증은 프로브에서 한다(측정 세션과 같은 그래프다). 어긋나면 여기서 죽는다.
        val graph = readGraph(probeSession)
        if (graph.mismatches.isNotEmpty()) {
            closeQuietly(probeSession)
            return failed(
                arm,
                "🔴 그래프가 선언과 어긋난다 — 런을 시작하지 않는다:\n" +
                    graph.mismatches.joinToString("\n") { "  · $it" },
            )
        }

        val probeInferStart = SystemClock.elapsedRealtime()
        val probeInferError = runDummyInference(env, probeSession, graph)
        val probeInferMs = SystemClock.elapsedRealtime() - probeInferStart
        if (probeInferError != null) {
            closeQuietly(probeSession)
            return failed(arm, "프로브 더미 추론 실패: $probeInferError")
        }

        val probeProfilePath = try {
            probeSession.endProfiling()
        } catch (t: Throwable) {
            warnings += "endProfiling 실패: ${t.javaClass.simpleName}: ${t.message}"
            null
        }
        closeQuietly(probeSession)

        // 6) EP 판별. 🔴 못 했으면 unknown이다.
        val counts = probeProfilePath?.let { readProviderCounts(it) } ?: ProviderCounts()
        if (probeProfilePath != null && counts.kernel.isEmpty() && counts.all.isEmpty()) {
            warnings += "프로파일 JSON을 읽었으나 provider가 붙은 이벤트가 하나도 없다: " +
                probeProfilePath
        }
        val basis = if (counts.kernel.isNotEmpty()) counts.kernel else counts.all
        val resolved = resolveEp(basis)
        val method = if (basis.isEmpty()) DetectContract.METHOD_NONE else DetectContract.METHOD_PROFILE_PROBE

        // 7) verbose 로그 원문 회수(2순위 근거). 🔴 **파싱해서 판정하지 않는다.**
        val logcat = readOrtLogcat()
        if (logcat.lines.isEmpty()) {
            warnings += "ORT verbose 로그를 회수하지 못했다 — ${logcat.note}"
        }

        // 8) 측정 세션. 프로파일러는 `_prof` arm에서만 켠다(다른 arm의 시간을 오염시키지 않는다).
        val measured = openSession(
            env,
            modelFile,
            epRequested,
            profiling = arm.detectProfilingEnabled,
            verbose = false,
        )
        if (measured.session == null) {
            return failed(arm, "측정 세션 생성 실패: ${measured.error}")
        }
        measuredSession = measured.session

        val warmupStart = SystemClock.elapsedRealtime()
        val warmupError = runDummyInference(env, measured.session, graph)
        val warmupMs = SystemClock.elapsedRealtime() - warmupStart
        if (warmupError != null) {
            closeMeasured()
            return failed(arm, "측정 세션 더미 추론 실패: $warmupError")
        }

        return DetectReport(
            arm = arm,
            ok = true,
            failure = null,
            warnings = warnings,
            modelPath = modelFile.absolutePath,
            modelBytes = modelFile.length(),
            sha256Computed = computed,
            sha256Declared = DetectContract.declaredSha256,
            sha256MatchesDeclared = true,
            sha256Ms = hashMs,
            inputName = graph.inputName,
            inputShape = graph.inputShape.toList(),
            inputType = graph.inputType,
            outputName = graph.outputName,
            outputShape = graph.outputShape.toList(),
            outputType = graph.outputType,
            graphMismatches = emptyList(),
            classNames = graph.classNames,
            classNamesRaw = graph.classNamesRaw,
            contractConflict = if (graph.classNames == DetectContract.INTERFACES_A4_CLASS_ORDER) {
                null
            } else {
                DetectContract.contractConflictText(graph.classNames)
            },
            epRequested = epRequested,
            epResolved = resolved,
            resolutionMethod = method,
            providerNodeCounts = counts.kernel,
            providerEventCounts = counts.all,
            probeProfilePath = probeProfilePath,
            measuredProfilePrefix = if (arm.detectProfilingEnabled) measured.profilePrefix else null,
            availableProviders = available,
            verboseLogLines = logcat.lines,
            verboseLogNote = logcat.note,
            nnapiGuard = nnapiGuard,
            envInitMs = envMs,
            probeCreateMs = probe.createMs,
            probeInferMs = probeInferMs,
            measuredCreateMs = measured.createMs,
            warmupInferMs = warmupMs,
            ortPackage = BuildConfig.ORT_PACKAGE,
            ortVersion = BuildConfig.ORT_VERSION,
        )
    }

    // ── 세션 ─────────────────────────────────────────────────────────────

    private class OpenResult(
        val session: OrtSession?,
        val error: String?,
        val createMs: Long,
        /** 프로파일러를 켰다면 우리가 넘긴 경로 접두사. 실제 파일명은 ORT가 정한다. */
        val profilePrefix: String?,
    )

    private fun openSession(
        env: OrtEnvironment,
        modelFile: File,
        epRequested: String,
        profiling: Boolean,
        verbose: Boolean,
    ): OpenResult {
        val start = SystemClock.elapsedRealtime()
        var prefix: String? = null
        return try {
            val options = OrtSession.SessionOptions()
            if (verbose) {
                options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_VERBOSE)
            } else {
                // 측정 세션은 조용히 둔다 — verbose는 추론 경로에도 로그를 남긴다.
                options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING)
            }
            if (profiling) {
                if (!profileDir.isDirectory) profileDir.mkdirs()
                // ⚠ 접두사에 디렉토리가 없으면 ORT가 프로세스 CWD("/")에 쓰려다 실패한다.
                prefix = File(profileDir, "ort_${epRequested}_").absolutePath
                options.enableProfiling(prefix)
            }
            // ⚠ **CPU EP를 명시적으로 추가하지 않는다.** ORT는 어느 EP도 못 잡은 노드를
            //   기본 CPU EP로 떨어뜨리므로 addCPU를 부르면 우선순위만 흔들고 얻는 게 없다.
            if (epRequested == DetectContract.EP_NNAPI) {
                // ⚠ 플래그 없는 기본 호출이다. USE_FP16 등은 **정확도를 바꾸는 결정**이라
                //   임의로 켜지 않는다(INTERFACES.md A-1의 정밀도 칸이 아직 ☐다).
                options.addNnapi()
            }
            val session = env.createSession(modelFile.absolutePath, options)
            OpenResult(session, null, SystemClock.elapsedRealtime() - start, prefix)
        } catch (t: Throwable) {
            OpenResult(
                null,
                "${t.javaClass.simpleName}: ${t.message}",
                SystemClock.elapsedRealtime() - start,
                prefix,
            )
        }
    }

    private fun closeMeasured() {
        val session = measuredSession ?: return
        // `_prof` arm이면 프로파일 파일을 여기서 확정한다 — 안 부르면 파일이 마감되지 않아
        // 나중에 열어도 반쪽 JSON일 수 있다. 확정 경로는 logcat에만 남는다(보고는 세션 준비
        // 시점에 이미 만들어져 있다). ⚠ **다음 라운드**에는 이 호출이 런 정지 시점으로 가고
        // 결과 파일이 런 디렉토리로 들어가야 한다 — 그때가 이 프로파일이 뜻을 갖는 시점이다.
        try {
            val path = session.endProfiling()
            Log.i(TAG, "측정 세션 프로파일 확정: $path")
        } catch (t: Throwable) {
            // 프로파일러를 안 켠 세션에서는 실패가 정상이다. 조용히 넘기지 않고 남긴다.
            Log.d(TAG, "endProfiling 생략/실패(프로파일러 미사용이면 정상): ${t.message}")
        }
        closeQuietly(session)
        measuredSession = null
    }

    private fun closeQuietly(session: OrtSession) {
        try {
            session.close()
        } catch (t: Throwable) {
            Log.w(TAG, "세션 close 실패", t)
        }
    }

    // ── 그래프 읽기 ──────────────────────────────────────────────────────

    private class GraphFacts(
        val inputName: String?,
        val inputShape: LongArray,
        val inputType: String?,
        val outputName: String?,
        val outputShape: LongArray,
        val outputType: String?,
        val classNames: List<String>,
        val classNamesRaw: String?,
        val mismatches: List<String>,
    )

    /**
     * 🔴 **그래프를 읽어 자기를 설정한다.** 해상도도 클래스 수도 코드에 없다 — `TensorInfo`와
     * 임베드 메타데이터에서 읽고, 커밋된 `metadata.json`의 선언과 **대조**만 한다.
     */
    private fun readGraph(session: OrtSession): GraphFacts {
        val mismatches = mutableListOf<String>()

        val inputs = try {
            session.inputInfo
        } catch (t: Throwable) {
            mismatches += "inputInfo를 읽지 못했다: ${t.javaClass.simpleName}: ${t.message}"
            emptyMap()
        }
        val outputs = try {
            session.outputInfo
        } catch (t: Throwable) {
            mismatches += "outputInfo를 읽지 못했다: ${t.javaClass.simpleName}: ${t.message}"
            emptyMap()
        }
        if (inputs.size != 1) mismatches += "입력이 1개가 아니다 (${inputs.size}개)"
        if (outputs.size != 1) mismatches += "출력이 1개가 아니다 (${outputs.size}개)"

        val inEntry = inputs.entries.firstOrNull()
        val outEntry = outputs.entries.firstOrNull()
        val inTensor = inEntry?.value?.info as? TensorInfo
        val outTensor = outEntry?.value?.info as? TensorInfo
        if (inEntry != null && inTensor == null) mismatches += "입력이 텐서가 아니다"
        if (outEntry != null && outTensor == null) mismatches += "출력이 텐서가 아니다"

        val inShape = inTensor?.shape ?: LongArray(0)
        val outShape = outTensor?.shape ?: LongArray(0)

        // 이름·shape·dtype을 **선언과 문자열로** 대조한다. 선언의 출처는 커밋된 metadata.json이다.
        if (inEntry != null && inEntry.key != DetectContract.declaredInputName) {
            mismatches += "입력 이름이 다르다: 실측=${inEntry.key} 선언=${DetectContract.declaredInputName}"
        }
        if (outEntry != null && outEntry.key != DetectContract.declaredOutputName) {
            mismatches += "출력 이름이 다르다: 실측=${outEntry.key} 선언=${DetectContract.declaredOutputName}"
        }
        val inShapeText = inShape.joinToString(",")
        val outShapeText = outShape.joinToString(",")
        if (inShapeText != DetectContract.declaredInputShape) {
            mismatches += "입력 shape이 다르다: 실측=[$inShapeText] 선언=[${DetectContract.declaredInputShape}]"
        }
        if (outShapeText != DetectContract.declaredOutputShape) {
            mismatches += "출력 shape이 다르다: 실측=[$outShapeText] 선언=[${DetectContract.declaredOutputShape}]"
        }
        val inType = inTensor?.type?.toString()
        if (inType != null && inType != EXPECTED_TENSOR_TYPE) {
            mismatches += "입력 dtype이 float가 아니다: $inType"
        }

        // 동적 축이 하나라도 있으면 EP 파티셔닝이 깨진다(INTERFACES.md A-1이 그렇게 경고한다).
        // ONNX의 동적 축은 여기서 -1이나 0으로 온다.
        if (inShape.any { it <= 0L }) mismatches += "입력에 동적 축이 있다: [$inShapeText]"
        if (outShape.any { it <= 0L }) mismatches += "출력에 동적 축이 있다: [$outShapeText]"

        // 🔴 클래스 이름은 **모델 내장 메타데이터**가 1순위 출처다(가중치와 어긋날 수 없다).
        var rawNames: String? = null
        var names: List<String> = emptyList()
        try {
            val custom = session.metadata.customMetadata
            rawNames = custom[NAMES_KEY]
        } catch (t: Throwable) {
            mismatches += "임베드 메타데이터를 읽지 못했다: ${t.javaClass.simpleName}: ${t.message}"
        }
        if (rawNames == null) {
            mismatches += "임베드 메타데이터에 '$NAMES_KEY' 키가 없다 — 클래스 순서의 1순위 출처가 없다"
        } else {
            // ⚠ 값은 파이썬 dict 문자열(작은따옴표)이라 JSON 파서가 못 먹는다.
            //   관용 파서를 쓰되 **파싱 실패를 조용히 넘기지 않는다.**
            val parsed = parseNamesDict(rawNames)
            if (parsed == null) {
                mismatches += "'$NAMES_KEY'를 파싱하지 못했다(원문: $rawNames)"
            } else {
                names = parsed
                val asDeclared = parsed.mapIndexed { i, n -> "$i=$n" }.joinToString(",")
                if (asDeclared != DetectContract.declaredClasses) {
                    mismatches += "클래스가 metadata.json 선언과 다르다: " +
                        "모델=$asDeclared 선언=${DetectContract.declaredClasses}"
                }
                // 출력 채널이 4 + nc여야 한다. 이건 하드코딩이 아니라 **두 실측의 정합성**이다.
                if (outShape.size == 3) {
                    val expected = 4L + parsed.size
                    if (outShape[1] != expected) {
                        mismatches += "출력 채널이 4+nc가 아니다: 실측=${outShape[1]} " +
                            "기대=$expected (nc=${parsed.size})"
                    }
                }
            }
        }

        return GraphFacts(
            inputName = inEntry?.key,
            inputShape = inShape,
            inputType = inType,
            outputName = outEntry?.key,
            outputShape = outShape,
            outputType = outTensor?.type?.toString(),
            classNames = names,
            classNamesRaw = rawNames,
            mismatches = mismatches,
        )
    }

    /**
     * `{0: 'person', 1: 'stairs'}` 같은 **파이썬 dict 문자열**을 인덱스 순 목록으로.
     *
     * 실패하면 null이다 — 부분 성공을 그럴듯하게 돌려주면 클래스 순서가 조용히 틀린다.
     * 인덱스가 `0..n-1`을 빠짐없이 덮지 않아도 실패로 본다(구멍이 있으면 순서를 못 정한다).
     */
    private fun parseNamesDict(raw: String): List<String>? {
        val byIndex = HashMap<Int, String>()
        for (m in NAMES_ENTRY.findAll(raw)) {
            val idx = m.groupValues[1].toIntOrNull() ?: return null
            if (byIndex.containsKey(idx)) return null
            byIndex[idx] = m.groupValues[2]
        }
        if (byIndex.isEmpty()) return null
        val ordered = ArrayList<String>(byIndex.size)
        for (i in 0 until byIndex.size) {
            ordered.add(byIndex[i] ?: return null)
        }
        return ordered
    }

    // ── 더미 추론 ────────────────────────────────────────────────────────

    /**
     * 그래프에서 읽은 shape 그대로 **0으로 채운** 텐서를 한 번 흘린다.
     *
     * 🔴 **이 시간을 F로 인용하지 않는다.** 첫 추론은 지연 초기화(EP 커널 준비·메모리 아레나)를
     * 포함하고 표본이 1개이며, 입력도 실제 프레임이 아니다. 여기서 확인하는 것은 "세션이
     * 실제로 도는가"와 "프로파일러가 노드 이벤트를 내는가" 둘뿐이다.
     */
    private fun runDummyInference(
        env: OrtEnvironment,
        session: OrtSession,
        graph: GraphFacts,
    ): String? {
        val name = graph.inputName ?: return "입력 이름을 모른다"
        val shape = graph.inputShape
        if (shape.isEmpty()) return "입력 shape을 모른다"
        var elements = 1L
        for (d in shape) elements *= d
        if (elements <= 0L || elements > MAX_DUMMY_ELEMENTS) {
            return "더미 텐서 원소 수가 이상하다: $elements"
        }
        var tensor: OnnxTensor? = null
        var result: OrtSession.Result? = null
        return try {
            val buffer = ByteBuffer
                .allocateDirect((elements * 4L).toInt())
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            tensor = OnnxTensor.createTensor(env, buffer, shape)
            result = session.run(mapOf(name to tensor))
            if (result.size() < 1) "출력이 비었다" else null
        } catch (t: Throwable) {
            "${t.javaClass.simpleName}: ${t.message}"
        } finally {
            // ⚠ 반드시 닫는다. 네이티브 버퍼가 새면 다음 라운드의 프레임 루프에서 바로 터진다.
            try {
                result?.close()
            } catch (t: Throwable) {
                Log.w(TAG, "Result close 실패", t)
            }
            try {
                tensor?.close()
            } catch (t: Throwable) {
                Log.w(TAG, "OnnxTensor close 실패", t)
            }
        }
    }

    // ── EP 판별 ──────────────────────────────────────────────────────────

    private class ProviderCounts(
        val kernel: Map<String, Int> = emptyMap(),
        val all: Map<String, Int> = emptyMap(),
    )

    /**
     * ORT 프로파일(Chrome trace JSON)에서 **노드별 provider**를 센다 — 1순위 판별 수단.
     *
     * NNAPI가 잡은 서브그래프는 융합 노드 **하나**로 나타나므로 provider별 노드 수는
     * **파티셔닝의 모양**을 보여 주지 작업량 비율을 보여 주지 않는다. 전 노드가 CPU면
     * **통째 폴백 확정**이다.
     */
    private fun readProviderCounts(path: String): ProviderCounts {
        val file = File(path)
        if (!file.isFile) return ProviderCounts()
        return try {
            val array = JSONArray(file.readText())
            val kernel = HashMap<String, Int>()
            val all = HashMap<String, Int>()
            for (i in 0 until array.length()) {
                val event = array.optJSONObject(i) ?: continue
                val args = event.optJSONObject("args") ?: continue
                val provider = args.optString("provider", "")
                if (provider.isEmpty()) continue
                all[provider] = (all[provider] ?: 0) + 1
                if (event.optString("name", "").endsWith(KERNEL_TIME_SUFFIX)) {
                    kernel[provider] = (kernel[provider] ?: 0) + 1
                }
            }
            ProviderCounts(kernel, all)
        } catch (t: Throwable) {
            Log.w(TAG, "프로파일 JSON 파싱 실패: $path", t)
            ProviderCounts()
        }
    }

    /**
     * provider 원문 문자열 집계 → 어휘(`cpu`/`nnapi`/`unknown`).
     *
     * - NNAPI 노드가 **하나라도** 있으면 `nnapi`다(부분 파티셔닝도 NNAPI가 실행에 관여한
     *   것이다). 얼마나 가져갔는지는 `ep.node_counts`가 말한다 — 그 값을 함께 인용할 것.
     * - NNAPI가 없고 나머지가 전부 CPU면 `cpu`(= 통째 폴백).
     * - 그 밖(집계가 비었거나 모르는 provider가 섞였다)은 `unknown`이다.
     *   🔴 **모름과 CPU는 다른 사실이다.**
     */
    private fun resolveEp(counts: Map<String, Int>): String {
        if (counts.isEmpty()) return DetectContract.EP_UNKNOWN
        if (counts.keys.any { it.contains("nnapi", ignoreCase = true) }) {
            return DetectContract.EP_NNAPI
        }
        if (counts.keys.all { it.contains("cpu", ignoreCase = true) }) {
            return DetectContract.EP_CPU
        }
        return DetectContract.EP_UNKNOWN
    }

    // ── verbose 로그 원문 회수 ───────────────────────────────────────────

    private class LogcatDump(val lines: List<String>, val note: String)

    /**
     * ORT가 logcat에 남긴 줄을 **원문 그대로** 걷어 온다 — 2순위 근거.
     *
     * 🔴 **파싱해서 판정하지 않는다.** 이 문자열은 ORT 버전마다 바뀌므로 여기서 결론을 내면
     * 버전이 오르는 날 조용히 틀린다. 결론은 프로파일러가 내고, 이건 그 결론을 사람이
     * 눈으로 대조할 원문이다.
     *
     * 앱은 **자기 프로세스의 로그만** 읽을 수 있다(READ_LOGS 없이는 그렇다) — 우리가 원하는
     * 것이 정확히 그것이다.
     */
    private fun readOrtLogcat(): LogcatDump {
        return try {
            val process = ProcessBuilder(
                "logcat", "-d", "-v", "brief", "-t", LOGCAT_TAIL_LINES.toString()
            ).redirectErrorStream(true).start()
            val collected = ArrayList<String>()
            var seen = 0
            process.inputStream.bufferedReader().useLines { seq ->
                for (line in seq) {
                    if (LOGCAT_KEEP.none { line.contains(it, ignoreCase = true) }) continue
                    seen++
                    if (collected.size < LOGCAT_MAX_KEPT) collected.add(line)
                }
            }
            process.waitFor()
            val note = "logcat -d -v brief -t $LOGCAT_TAIL_LINES 의 출력에서 " +
                "${LOGCAT_KEEP.joinToString("/")} 를 포함한 줄만 남겼다(선택이며 판정이 아니다). " +
                "해당 줄 ${seen}개 중 ${collected.size}개 보존" +
                if (seen > collected.size) " — **잘렸다**" else ""
            LogcatDump(collected, note)
        } catch (t: Throwable) {
            LogcatDump(
                emptyList(),
                "logcat 실행 실패: ${t.javaClass.simpleName}: ${t.message}",
            )
        }
    }

    // ── 보조 ─────────────────────────────────────────────────────────────

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(SHA_BUFFER_BYTES)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        val out = StringBuilder(64)
        for (b in digest.digest()) {
            out.append(HEX[(b.toInt() ushr 4) and 0xF])
            out.append(HEX[b.toInt() and 0xF])
        }
        return out.toString()
    }

    private fun failed(arm: RenderArm, why: String): DetectReport = DetectReport(
        arm = arm,
        ok = false,
        failure = why,
        warnings = emptyList(),
        modelPath = null,
        modelBytes = 0L,
        sha256Computed = null,
        sha256Declared = DetectContract.declaredSha256,
        sha256MatchesDeclared = null,
        sha256Ms = 0L,
        inputName = null,
        inputShape = emptyList(),
        inputType = null,
        outputName = null,
        outputShape = emptyList(),
        outputType = null,
        graphMismatches = emptyList(),
        classNames = emptyList(),
        classNamesRaw = null,
        contractConflict = null,
        epRequested = arm.detectEpRequested ?: DetectContract.EP_UNKNOWN,
        // 🔴 세션을 못 열었으면 무엇이 실행했는지도 모른다. 요청값을 베끼지 않는다.
        epResolved = DetectContract.EP_UNKNOWN,
        resolutionMethod = DetectContract.METHOD_NONE,
        providerNodeCounts = emptyMap(),
        providerEventCounts = emptyMap(),
        probeProfilePath = null,
        measuredProfilePrefix = null,
        availableProviders = emptyList(),
        verboseLogLines = emptyList(),
        verboseLogNote = "준비가 실패해 회수하지 않았다",
        nnapiGuard = "미확인 (준비 실패)",
        envInitMs = 0L,
        probeCreateMs = 0L,
        probeInferMs = 0L,
        measuredCreateMs = 0L,
        warmupInferMs = 0L,
        ortPackage = BuildConfig.ORT_PACKAGE,
        ortVersion = BuildConfig.ORT_VERSION,
    )

    private companion object {
        const val TAG = "BammasilDetect"

        /** ORT 로그의 식별자. logcat에서 우리 세션의 줄을 찾을 때 쓴다. */
        const val ENV_ID = "bammasil"

        /** NNAPI EP의 런타임 하한. 앱 minSdk는 26이라 **가드가 실제로 필요하다.** */
        const val NNAPI_MIN_SDK = 27

        const val NAMES_KEY = "names"

        /** `TensorInfo.type`(OnnxJavaType)의 float 표기. */
        const val EXPECTED_TENSOR_TYPE = "FLOAT"

        /** ORT 프로파일에서 노드 실행 이벤트의 이름 접미사. */
        const val KERNEL_TIME_SUFFIX = "_kernel_time"

        /** `{0: 'person', 1: 'stairs'}` / `{0: "person"}` 둘 다 받는다. */
        val NAMES_ENTRY = Regex("""(\d+)\s*:\s*['"]([^'"]*)['"]""")

        val LOGCAT_KEEP = listOf("onnxruntime", "ExecutionProvider", "Node placements")
        const val LOGCAT_TAIL_LINES = 2000
        const val LOGCAT_MAX_KEPT = 200

        const val SHA_BUFFER_BYTES = 1 shl 16

        /** 640×640×3 = 1,228,800. 상한은 그 10배쯤으로 잡아 터무니없는 shape을 막는다. */
        const val MAX_DUMMY_ELEMENTS = 16L * 1024L * 1024L

        val HEX = "0123456789abcdef".toCharArray()
    }
}
