package com.bammasil.poc.source

import android.view.Surface
import androidx.camera.core.ImageProxy

/**
 * 프레임 공급원 추상화.
 *
 * 지금 구현체는 [CameraFrameSource] 하나뿐인데도 첫날에 인터페이스를 박는 이유:
 * 나중에 영상 파일 디코더나 화면 캡처를 입력으로 끼울 때(`docs/STATUS.md`) 소스가
 * 카메라에 직결돼 있으면 렌더·로깅 경로를 통째로 뜯어야 한다.
 *
 * ⚠ 이번 PoC는 **카메라 구현체만** 만든다. 디코더·화면캡처는 자리만 비워 둔다 —
 * 쓰지 않을 구현을 미리 짜면 검증되지 않은 코드가 저장소에 남는다.
 */
interface FrameSource {

    /** `session.json`에 남는 소스 종류. */
    val kind: String

    /** 소스가 **실제로** 물어온 조건. 시작 전에는 null. */
    val negotiated: NegotiatedConfig?

    /**
     * ③ 탐지 경로(분석 use case)가 **실제로** 물어온 조건. 바인딩하지 않았으면 null.
     *
     * 🔴 [negotiated]와 **따로** 낸다. 표시 경로와 분석 경로는 다른 use case라 CameraX가
     * 서로 다른 해상도를 줄 수 있고, 한 칸에 섞으면 그 런의 조건이 거짓말을 한다.
     */
    val analysisConfig: AnalysisConfig?

    /**
     * 프레임 공급을 시작한다.
     *
     * [target]이 Surface를 내주지 못하면(GL 미준비) 소스는 프레임을 흘리지 않고 [onError]로
     * 알린다 — 거짓 Surface를 만들어 "성공한 척" 도는 경로를 만들지 않는다.
     *
     * @param bindAnalysis ③ 탐지용 분석 use case를 **함께 바인딩할 것인가.**
     *   🔴 false면 use case를 **붙이지도 않는다** — 붙여 두고 콜백만 막으면 그 arm의
     *   프레임타임에 use case 하나의 비용이 섞여 승격본과의 비교가 끊긴다.
     */
    fun start(
        request: FrameRequest,
        target: FrameTarget,
        bindAnalysis: Boolean,
        onError: (String) -> Unit,
    )

    fun stop()
}

/** 소스에 **요청하는** 조건. 실제로 받는 값과 다를 수 있다 → [NegotiatedConfig]. */
data class FrameRequest(val width: Int, val height: Int, val fps: Int)

/**
 * 소스가 실제로 물어온 조건. 요청값만 기록하면 측정 조건이 거짓말을 하므로
 * `session.json`에 요청값과 **따로** 싣는다.
 */
data class NegotiatedConfig(
    val width: Int,
    val height: Int,
    /** 소스가 알려준 프레임레이트 범위 문자열. 모르면 **null** — 0이나 요청값으로 채우지 않는다. */
    val frameRateRange: String?,
)

/**
 * 분석 use case가 **실제로** 물어온 조건. `session.json`의 `detect.input` 블록이 여기서 나온다.
 *
 * 🔴 값의 출처는 **첫 [ImageProxy] 그 자체**다(`resolutionInfo` 같은 선언 API가 아니다) —
 * 실제로 전처리가 읽은 버퍼의 크기·포맷이어야 E의 뜻이 확정된다.
 */
data class AnalysisConfig(
    val width: Int,
    val height: Int,
    /** `android.graphics.ImageFormat` 상수 원본. */
    val imageFormat: Int,
    /** 위 상수의 이름(사람이 읽는 값). 모르면 `"unknown(<int>)"`. */
    val imageFormatName: String,
    /**
     * `ImageProxy.imageInfo.rotationDegrees` — **마지막으로 관측한 값**이다.
     * `session.json`의 `detect.input.rotation_degrees`가 이 값에서 나온다.
     *
     * 🔴 **③ 전처리는 이 값을 쓰지 않는다.** 앱은 **첫 분석 프레임의 값으로 잠그고**
     * (규약 §4-3) 이후 다른 값이 오면 잠근 값을 계속 쓰면서 센다 — 런 도중에 기하가 갈리면
     * E와 박스 좌표가 한 런 안에서 두 뜻을 갖기 때문이다. 실제로 쓴 각은
     * `detect.input.rotation_degrees_locked`이고, 이 값과 다르면 그 자체가 신호다
     * (`rotation_changed_frames` 참고).
     *
     * ⚠ **회전은 비용에 영향이 있다**(2026-08-07 정정). 예전 주석은 *"회전을 적용하지 않으니
     * 비용에도 영향이 없다"* 였는데 **두 문장 다 이제 거짓이다** — 회전은 전처리 샘플 맵에
     * 합성돼 있고(`rotation_site = preprocess_sample_map`), 90/270°에서는 목적지 행 하나가
     * 센서의 **열**을 훑어 **E의 값이 바뀐다**(정의가 아니라 값이다). 그 차분을 재려고
     * 짝 arm `detect_cpu_norot`이 있다. **비용은 미측정이다.**
     */
    val rotationDegrees: Int,
    /** 우리가 **요청한** 출력 포맷 이름. 받은 것과 다를 수 있어 따로 싣는다. */
    val requestedFormatName: String,
    /** 백프레셔 전략 이름. */
    val backpressureStrategy: String,
)

/**
 * ③ 탐지 경로가 분석 프레임을 받는 자리.
 *
 * 🔴 **[ImageProxy]를 보관하지 않는다.** [onAnalysisImage]가 반환하면 [CameraFrameSource]가
 * `finally`에서 닫는다 — 닫기를 구현체에 맡기면 그 하나를 빠뜨렸을 때 카메라가 몇 프레임 뒤에
 * 굶어 죽고, 증상은 "탐지가 느리다"로 보여 원인을 엉뚱한 데서 찾게 된다. 그래서 close를
 * **한 곳**에만 둔다.
 */
fun interface AnalysisSink {

    /** 분석 전용 스레드에서 불린다(GL 스레드도 메인 스레드도 아니다). */
    fun onAnalysisImage(image: ImageProxy)
}

/**
 * 소스가 프레임을 얹을 대상. GL 렌더러가 구현한다.
 * 콜백은 **메인 스레드**에서 불린다(GL 스레드가 아니다).
 */
interface FrameTarget {

    /** 프레임을 받을 Surface. GL 텍스처가 아직 없으면 **null**을 돌려준다. */
    fun acquireSurface(width: Int, height: Int): Surface?

    /** 소스가 Surface 사용을 끝냈을 때. [resultCode]는 소스별 결과 코드(진단용). */
    fun releaseSurface(surface: Surface, resultCode: Int)
}
