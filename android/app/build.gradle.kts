plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
