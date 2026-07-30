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

android {
    namespace = "com.bammasil.poc"

    // 이 개발 머신에 실제로 설치된 platform 디렉토리는 android-36.1 하나다
    // (source.properties: AndroidVersion.ApiLevel=36.1). 추측하지 않고 그 값을 그대로 쓴다.
    // compileSdkMinor 는 AGP 8.13에서 제공됨을 gradle-api 8.13.2 심볼로 확인했다.
    compileSdk = 36
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "com.bammasil.poc"
        // PIPELINE_STACK.md:176 (GLES 3.1 광범위 지원선)
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-poc"

        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
        buildConfigField("String", "GIT_DIRTY", "\"$gitDirty\"")
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
}
