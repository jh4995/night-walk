plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ── 빌드 시점 git 상태 → BuildConfig ──────────────────────────────────────
// app_version_name 은 아래 상수(0.1-poc)라 커밋이 바뀌어도 안 변한다. 그래서 로그와 APK를
// 잇는 고리가 아예 없었다 — 승격 베이스라인 2건이 android 코드 커밋보다 앞선 바이너리에서
// 나왔고 그게 git_dirty:true 의 실체였다. 여기서 커밋과 dirty 여부를 박아 그 고리를 만든다.
//
// ⚠ git 이 없거나 실패해도 **빌드를 죽이지 않는다.** "unknown"으로 남기고, 그 사실 자체가
//   session.json 에 기록되게 한다. 빌드를 막으면 측정을 못 하고, 거짓값을 넣으면 더 나쁘다.
// (최상위 fun 이 아니라 val + 람다인 이유: .kts 의 최상위 함수에서는 rootDir 같은 Project
//  프로퍼티가 암시적 리시버로 잡히지 않는다. 람다는 스크립트 스코프를 캡처한다.)
val repoDir = rootDir
val gitOutput: (List<String>) -> String? = { args ->
    try {
        val process = ProcessBuilder(args)
            .directory(repoDir)
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        if (process.waitFor() == 0) text.trim() else null
    } catch (t: Exception) {
        null
    }
}
val gitCommit: String = gitOutput(listOf("git", "rev-parse", "--short", "HEAD")) ?: "unknown"
val gitStatus: String? = gitOutput(listOf("git", "status", "--porcelain"))
// 3-상태다. git 을 못 돌렸으면 "깨끗하다"가 아니라 "모른다"이며, boolean 으로 만들면
// 모름이 false(=깨끗함)라는 거짓 주장이 된다.
val gitDirty: String = when {
    gitStatus == null -> "unknown"
    gitStatus.isEmpty() -> "false"
    else -> "true"
}

// ── ③ 탐지 런타임 좌표 ────────────────────────────────────────────────────
// 🔴 **full 패키지(`onnxruntime-android`)다. `onnxruntime-mobile`이 아니다** — 그쪽은
//   `.ort` 포맷·축소 연산자셋이라 우리가 받은 `.onnx`(opset 12, FP32)를 못 열 수 있다.
// 좌표를 BuildConfig에도 박는 이유: session.json이 "어느 ORT로 잰 숫자인가"에 답할
// 유일한 수단이다. 여기 한 곳에서 의존성과 로그가 함께 나온다(사본을 만들지 않는다).
val ortPackage = "com.microsoft.onnxruntime:onnxruntime-android"
val ortVersion = "1.28.0"

// ── ③ 모델의 **선언값**을 커밋된 metadata.json에서 읽어 BuildConfig에 박는다 ──────
// `.onnx`는 gitignore라 APK에 동봉하지 않고 adb push로 배포한다. 그러면 "앱이 연 파일이
// 정말 그 모델인가"를 대조할 기준값이 필요한데, 그 기준값의 출처는 **git이 추적하는 계약
// 문서**여야 나중에 되물을 수 있다 — `models/0824/bammasil_det_c4e_s3_11n_640/metadata.json`이
// 그 문서다 (README.md와 함께 커밋돼 있다). BuildConfig.GIT_COMMIT이 이미 박히므로 "어느
// 시점의 metadata.json이었나"까지 추적된다.
//
// ⚠ 읽지 못해도 **빌드를 죽이지 않는다**(위 git 블록과 같은 규약). "unknown"으로 남기고,
//   그 상태에서는 앱이 sha256을 대조할 수 없으므로 **③ arm의 런 자체를 거부한다**
//   (DetectRuntime). 거짓값을 넣는 것보다 런이 안 도는 쪽이 낫다.
//
// ⚠ 경로는 **인수분 원본 배치 그대로**다(`models/0824/<모델명>/`). 우리 편의로 옮기지
//   않는다 — 옮기면 상류가 준 패키지와 이 저장소의 경로가 갈리고, 인수 시점의 배치가
//   무엇이었는지 되물을 수 없다(직전 커밋 41f385e의 선례).
val detectModelDir = "models/0824/bammasil_det_c4e_s3_11n_640"
val detectMetadataRelPath = "$detectModelDir/metadata.json"
val detectMetadataFile = File(repoDir.parentFile, detectMetadataRelPath)

@Suppress("UNCHECKED_CAST")
val detectMeta: Map<String, Any?>? = try {
    if (detectMetadataFile.isFile) {
        groovy.json.JsonSlurper().parse(detectMetadataFile, "UTF-8") as? Map<String, Any?>
    } else {
        null
    }
} catch (t: Exception) {
    null
}

val detectModelBlock = detectMeta?.get("model") as? Map<*, *>
val detectGraphBlock = detectMeta?.get("graph") as? Map<*, *>

/** `graph.inputs[0].shape` 같은 목록을 `"1,3,640,640"` 문자열로. 못 읽으면 "unknown". */
val detectShapeOf: (String, String) -> String = { section, key ->
    val entries = detectGraphBlock?.get(section) as? List<*>
    val first = entries?.firstOrNull() as? Map<*, *>
    when (key) {
        "shape" -> (first?.get("shape") as? List<*>)
            ?.joinToString(",") { it.toString() }
            ?: "unknown"
        else -> (first?.get(key) as? String) ?: "unknown"
    }
}

// ③ 후처리 임계. 🔴 **코드에 숫자를 박지 않는다** — sha256과 **같은 방식**으로 커밋된
// metadata.json에서 빌드 시점에 읽는다. ⚠ 실제 키는 `inference.conf` / `inference.iou`다.
// `INTERFACES.md` §A-5 예시의 `recommended_thresholds` 키는 **이 파일에 없다** — 예시를 믿고
// 그 이름으로 읽으면 조용히 "unknown"이 되고, 앱이 그 상태로 임계를 지어내게 된다.
val detectInferenceBlock = detectMeta?.get("inference") as? Map<*, *>
// ⚠ 이름이 `detectConfDeclared`다(예전 `detectConfThreshold`에서 개명). **이 값은 metadata가
//   선언한 값이고 앱이 쓰는 값이 아니다** — 아래 오버라이드가 있는 한 둘이 다르다.
val detectConfDeclared: String =
    (detectInferenceBlock?.get("conf") as? Number)?.toString() ?: "unknown"
val detectIouThreshold: String =
    (detectInferenceBlock?.get("iou") as? Number)?.toString() ?: "unknown"

// ── ③ conf 오버라이드 ─────────────────────────────────────────────────────
// 🔴 **빈 문자열이면 오버라이드가 없다**는 뜻이고, 그때 앱은 metadata의 선언값을 그대로 쓴다.
//    값을 넣으면 앱이 **그 값을 쓰고**, 파싱이 안 되면 런을 거부한다(DetectContract.
//    thresholdFailure) — 조용히 선언값으로 되돌아가면 로그가 거짓말한다.
val detectConfOverride = "0.25"
// ⚠ 이 문자열은 BuildConfig.java의 **Java 문자열 리터럴**로 그대로 구워진다 —
//   따옴표(")를 넣으면 리터럴이 깨진다. 그래서 인용에 ' 를 쓴다(백슬래시도 넣지 않는다).
val detectConfOverrideSource =
    "🔴 **앱이 실제로 쓰는 conf는 metadata의 선언값이 아니라 이 오버라이드다.** " +
        "근거: 상류 최신 결정(detection.md 9-8, 8/23)이 **'단일 운영 conf는 0.25를 유지'**로 " +
        "못 박았고(docs/research/RESEARCH_20260823_UPSTREAM.md §4-1 인용), 인수 패키지 README도 " +
        "**'운영 conf 는 0.25 를 권장한다 / 패키지 metadata의 inference.conf 0.35는 내보내기 " +
        "검증용 기본값이다'**라고 명시했다(models/0824/readme_c4e_640.md §6-2). " +
        "0.35는 상류의 어느 판정 표에도 없는 argparse 기본값이며 **야간 볼라드를 놓치는 " +
        "임계**다(같은 리서치 §4-2). " +
        "🔴 **그런데 metadata.json을 고치지 않았다** — 고치면 계약값의 출처가 둘로 갈린다" +
        "(CLAUDE.md 규칙 3). 재발행 요청은 알려진 이슈 44로 유효하다. 그래서 선언값은 " +
        "선언값대로 남기고(DETECT_CONF_DECLARED) 앱이 쓰는 값을 여기서 따로 선언한다. " +
        "⚠ **클래스별 conf(bollard 하향)는 적용하지 않았다.** 상류가 그것을 1순위 카드로 " +
        "적었지만(같은 리서치 §4-3 · U-18) **임계값 자체가 어느 계약 문서에도 없고** " +
        "metadata에 클래스별 임계 항목도 없다 — 지어내지 않는다. 그리고 이 모델(11n 계열)은 " +
        "야간 볼라드 사진에서 conf 0.759~0.804라 **0.25에서 이미 잡힌다**" +
        "(models/0824/readme_c4e_640.md §6-2). 코드에 클래스별 경로를 만들지 않았다"

// 앱이 실제로 쓰는 conf. 🔴 **오버라이드 문자열을 그대로 싣는다** — 여기서 파싱해 검증하면
// 실패를 빌드 시점에 뭉개게 되고, 앱은 "선언값으로 되돌아간 런"을 정상으로 보게 된다.
val detectConfUsed: String =
    if (detectConfOverride.isNotEmpty()) detectConfOverride else detectConfDeclared

val detectModelSha256: String = (detectModelBlock?.get("sha256") as? String) ?: "unknown"
val detectModelFileName: String = (detectModelBlock?.get("file") as? String) ?: "unknown"
// `{"0": "person", "1": "stairs"}` → `"0=person,1=stairs"`. 인덱스 순으로 정렬한다 —
// JSON 객체의 순서에 기대면 파서가 바뀌는 날 조용히 뒤집힌다.
val detectModelClasses: String = (detectMeta?.get("classes") as? Map<*, *>)
    ?.entries
    ?.sortedBy { (it.key as? String)?.toIntOrNull() ?: Int.MAX_VALUE }
    ?.joinToString(",") { "${it.key}=${it.value}" }
    ?: "unknown"
// ⚠ "unavailable"은 앱의 `DetectContract.SOURCE_UNAVAILABLE`과 **같은 문자열이어야 한다** —
//   갈리면 앱의 declaredMissing 검사가 조용히 통과해 대조 없는 런이 돈다.
val detectDeclaredSource: String =
    if (detectMeta != null) detectMetadataRelPath else "unavailable"

// Package the metadata-declared model as an APK asset. DetectRuntime installs this asset into
// the writable app-specific models directory before ONNX Runtime opens it.
val detectModelAssetDir = File(repoDir.parentFile, detectModelDir)

android {
    namespace = "com.bammasil.poc"

    // 이 개발 머신에 실제로 설치된 platform 디렉토리는 android-36.1 하나다
    // (source.properties: AndroidVersion.ApiLevel=36.1). 추측하지 않고 그 값을 그대로 쓴다.
    // compileSdkMinor 는 AGP 8.13에서 제공됨을 gradle-api 8.13.2 심볼로 확인했다.
    compileSdk = 36
    compileSdkMinor = 1

    sourceSets.getByName("main").assets.srcDir(detectModelAssetDir)

    defaultConfig {
        applicationId = "com.bammasil.poc"
        // PIPELINE_STACK.md:176 (GLES 3.1 광범위 지원선)
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-poc"

        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
        buildConfigField("String", "GIT_DIRTY", "\"$gitDirty\"")

        // ③ 탐지: 실제로 링크한 ORT 좌표. session.json의 detect.runtime으로 그대로 나간다.
        buildConfigField("String", "ORT_PACKAGE", "\"$ortPackage\"")
        buildConfigField("String", "ORT_VERSION", "\"$ortVersion\"")

        // ③ 탐지: metadata.json이 **선언한** 값들. 앱은 이것을 실측(파일 sha256 / 그래프
        // TensorInfo / 임베드 names)과 대조하고, 어긋나면 런을 시작하지 않는다.
        // ⚠ 값 자체가 아니라 **대조 기준**이다 — 앱이 이 값을 그대로 로그에 베껴 쓰지 않는다.
        buildConfigField("String", "DETECT_DECLARED_SOURCE", "\"$detectDeclaredSource\"")
        // ⚠ 위 DECLARED_SOURCE와 **다른 값이다**: 저쪽은 못 읽으면 "unavailable"이 되므로
        //   "어디에 파일을 두라"는 안내에 쓸 수 없다. 아래 둘은 **읽기 성공과 무관하게** 기대
        //   경로를 말한다(DetectRuntime의 실패 안내가 이것을 쓴다 — 사본을 만들지 않는다).
        buildConfigField("String", "DETECT_MODEL_DIR", "\"$detectModelDir\"")
        buildConfigField("String", "DETECT_METADATA_PATH", "\"$detectMetadataRelPath\"")
        buildConfigField("String", "DETECT_MODEL_FILE", "\"$detectModelFileName\"")
        buildConfigField("String", "DETECT_MODEL_SHA256", "\"$detectModelSha256\"")
        buildConfigField("String", "DETECT_MODEL_CLASSES", "\"$detectModelClasses\"")
        buildConfigField(
            "String", "DETECT_INPUT_NAME", "\"${detectShapeOf("inputs", "name")}\""
        )
        buildConfigField(
            "String", "DETECT_INPUT_SHAPE", "\"${detectShapeOf("inputs", "shape")}\""
        )
        buildConfigField(
            "String", "DETECT_OUTPUT_NAME", "\"${detectShapeOf("outputs", "name")}\""
        )
        buildConfigField(
            "String", "DETECT_OUTPUT_SHAPE", "\"${detectShapeOf("outputs", "shape")}\""
        )
        // ③ 후처리 임계. **위 대조 기준들과 성격이 다르다** — 이 둘은 앱이 실제로 **쓰는 값**이다.
        // 그래서 String으로 싣고 앱이 파싱하며, "unknown"이면 후처리를 시작하지 않는다
        // (숫자로 구우면 파싱 실패가 0.0이 되어 조용히 전량 통과하는 임계가 된다).
        // 🔴 **THRESHOLD는 앱이 실제로 쓰는 값이고, DECLARED는 metadata가 선언한 값이다.**
        //    둘을 한 키에 담으면 "어느 임계로 잰 숫자인가"에 답할 수 없다.
        buildConfigField("String", "DETECT_CONF_THRESHOLD", "\"$detectConfUsed\"")
        buildConfigField("String", "DETECT_CONF_DECLARED", "\"$detectConfDeclared\"")
        // 빈 문자열 = 오버라이드 없음. 앱이 이 값으로 "오버라이드가 걸린 런인가"를 판별한다.
        buildConfigField("String", "DETECT_CONF_OVERRIDE", "\"$detectConfOverride\"")
        buildConfigField(
            "String", "DETECT_CONF_OVERRIDE_SOURCE", "\"$detectConfOverrideSource\""
        )
        buildConfigField("String", "DETECT_IOU_THRESHOLD", "\"$detectIouThreshold\"")

        ndk {
            // 측정 기기는 A34(arm64-v8a) 하나다. ORT AAR은 ABI 4종의 네이티브 라이브러리를
            // 들고 오는데(arm64-v8a의 libonnxruntime.so만 28.6MB), 나머지 셋은 이 저장소의
            // 어떤 측정에도 쓰이지 않는다 → APK에서 뺀다.
            // ⚠ 이 필터는 **다른 ABI 기기에 설치가 안 된다**는 뜻이다. 측정 기기가 바뀌면
            //   여기를 먼저 본다.
            //
            // 실측(release APK, 2026-08-05):
            //   ORT 전            2,482,025 B
            //   ORT + 필터 없음 121,201,115 B  (ABI 4종)
            //   ORT + arm64만    31,104,432 B  (+28.6MB = libonnxruntime.so 그 자체)
            // 네이티브 라이브러리가 APK에 **무압축**으로 들어가므로(extractNativeLibs=false)
            // .so 크기가 그대로 APK 크기다.
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            // 측정은 release 빌드로만 한다 (debug는 프레임타임이 부풀려진다).
            // 그런데 서명 설정이 없으면 unsigned APK가 나와 기기에 설치조차 안 된다 →
            // 배포용 키가 아직 없으므로 AGP가 기본 제공하는 debug 키스토어를 재사용한다.
            // ⚠ PoC 측정 전용이다. 배포 빌드에는 별도 키가 필요하다.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            isDebuggable = false
        }
    }

    buildFeatures {
        // BuildConfig.BUILD_TYPE 를 session.json 에 적기 위해 필요하다.
        // AGP 8부터 기본이 꺼져 있어 명시해야 한다 — 이걸 빼면 문자열 리터럴로
        // "release"를 박는 유혹이 생기고, 그러면 debug 빌드가 release라고 거짓말한다.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // ComponentActivity = LifecycleOwner. CameraX bindToLifecycle 에 필요한 최소치.
    // appcompat 은 쓰지 않는다 (테마·리소스 표면을 늘릴 이유가 없다).
    implementation("androidx.activity:activity:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")

    // 표시 경로 2-C: Preview.setSurfaceProvider 로 우리 SurfaceTexture(OES)에 받는다.
    // camera-view(PreviewView)는 쓰지 않는다 — 우리가 GL로 직접 그린다.
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")

    // ③ 위험 탐지 추론. **full 패키지다** (위 ortPackage 주석 참고).
    // NNAPI·XNNPACK EP가 함께 빌드돼 있으므로 EP 요청은 코드에서만 고르면 된다.
    implementation("$ortPackage:$ortVersion")
}
