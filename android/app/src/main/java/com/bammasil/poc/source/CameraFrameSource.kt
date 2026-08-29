package com.bammasil.poc.source

import android.content.Context
import android.graphics.ImageFormat
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraX use case를 바인딩하는 소스 (표시 경로 2-C, `PIPELINE_STACK.md` §H).
 *
 * ## 표시 경로 — `Preview` 하나뿐이고 여기에 `ImageAnalysis`를 섞지 않는다
 *
 * **표시 경로는 `ImageAnalysis`를 쓰지 않는다.** YUV를 CPU로 받아 변환해 **그리면** 실제
 * 파이프라인이 영원히 내지 않을 비용이 베이스라인에 섞인다. 표시용 프레임은 우리
 * `SurfaceTexture`(OES)로 바로 들어가 GPU에 머문다.
 *
 * 결과적으로 **표시 경로에는 분석 콜백도 `ImageProxy`도 없다** — 그래서 표시 프레임 중
 * 버려진 수를 셀 수 없고 `dropped_since_last`가 -1이다(`FrameLogRecorder` 참고).
 *
 * ## ③ 탐지 경로 — 별 use case이고 **그리지 않는다**
 *
 * ③ arm에서만 `ImageAnalysis`를 **하나 더** 바인딩한다([start]의 `bindAnalysis`). 위 근거는
 * 여전히 참인데 이 경로에는 걸리지 않는다 — 탐지는 YUV를 **화면에 그리지 않고** 모델 입력
 * 텐서로만 쓰기 때문이다. 그 비용은 표시 경로의 프레임타임이 아니라 `detect.csv`의 E로 나간다.
 *
 * - 출력 포맷은 **`OUTPUT_IMAGE_FORMAT_YUV_420_888`**이다. `RGBA_8888`을 고르면 CameraX가
 *   변환을 대신 해 주는데 **그 비용이 E 밖에 숨어 E가 과소로 나온다.**
 * - 백프레셔는 **`STRATEGY_KEEP_ONLY_LATEST`**(`android-runtime` 규약).
 * - 🔴 `ImageProxy.close()`는 **이 파일의 `finally` 한 곳에만** 있다([AnalysisSink] 주석).
 * - ③ arm이 아니면 use case를 **붙이지도 않는다.** 붙여 두고 콜백만 막으면 그 arm의
 *   프레임타임에 use case 하나의 비용이 섞여 승격본 45건과의 비교가 끊긴다.
 */
class CameraFrameSource(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    /** ③ 탐지 경로. null이면 `bindAnalysis=true`여도 분석 use case를 바인딩하지 않는다. */
    private val analysisSink: AnalysisSink?,
) : FrameSource {

    override val kind: String = "camera"

    @Volatile
    override var negotiated: NegotiatedConfig? = null
        private set

    @Volatile
    override var analysisConfig: AnalysisConfig? = null
        private set

    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null

    @Volatile
    private var targetRotation: Int = android.view.Surface.ROTATION_0

    /**
     * 분석 콜백 전용 스레드. **탐지 추론 스레드와 다른 스레드다** — 추론이 도는 동안에도
     * 이 스레드가 프레임을 받아야 `skipped_while_busy`가 실제로 세어진다(둘이 같은 스레드면
     * 추론 중에는 콜백 자체가 안 불려 건너뛴 프레임이 0으로 보인다).
     */
    private var analysisExecutor: ExecutorService? = null

    override fun start(
        request: FrameRequest,
        target: FrameTarget,
        bindAnalysis: Boolean,
        onError: (String) -> Unit,
    ) {
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val cameraProvider = future.get()
                val resolutionSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(request.width, request.height),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        )
                    )
                    .build()
                val newPreview = Preview.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setTargetRotation(targetRotation)
                    // 요청값이다. 기기가 이걸 준다는 보장은 없으므로 받은 값은 따로 기록한다.
                    .setTargetFrameRate(Range(request.fps, request.fps))
                    .build()
                newPreview.setSurfaceProvider(
                    mainExecutor,
                    Preview.SurfaceProvider { surfaceRequest ->
                        provideSurface(surfaceRequest, target, mainExecutor, onError)
                    },
                )
                val sink = analysisSink
                val newAnalysis = if (bindAnalysis && sink != null) {
                    buildAnalysis(resolutionSelector, sink)
                } else {
                    null
                }
                // 재바인딩 경로(GL 컨텍스트 재생성 / arm 전환)에서 이전 use case가 남지 않게 한다.
                cameraProvider.unbindAll()
                if (newAnalysis == null) {
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        newPreview,
                    )
                } else {
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        newPreview,
                        newAnalysis,
                    )
                }
                provider = cameraProvider
                preview = newPreview
                analysis = newAnalysis
                Log.i(
                    TAG,
                    "카메라 바인딩 완료 (요청 ${request.width}x${request.height}@${request.fps}, " +
                        "analysis=${newAnalysis != null})"
                )
            } catch (t: Throwable) {
                Log.e(TAG, "카메라 바인딩 실패", t)
                onError("카메라 바인딩 실패: ${t.javaClass.simpleName}: ${t.message}")
            }
        }, mainExecutor)
    }

    override fun stop() {
        provider?.unbindAll()
        analysis?.clearAnalyzer()
        preview = null
        analysis = null
        provider = null
        // 분석 use case를 떼면 이 스레드도 함께 없앤다. 남겨 두면 다른 arm의 런에 쓰지 않는
        // 스레드가 살아 있게 되고, 그 자체가 조건 차이가 된다.
        analysisExecutor?.shutdown()
        analysisExecutor = null
        analysisConfig = null
    }

    override fun updateTargetRotation(rotation: Int) {
        targetRotation = rotation
        preview?.targetRotation = rotation
        analysis?.targetRotation = rotation
        Log.i(TAG, "Camera targetRotation updated: $rotation")
    }

    /**
     * ③ 탐지용 `ImageAnalysis`. 🔴 **포맷·백프레셔·close 세 가지가 이 함수의 전부다.**
     *
     * 포맷을 `RGBA_8888`로 바꾸면 CameraX가 변환을 대신 해 주고 **그 비용이 E 밖에 숨는다** —
     * E는 "전처리 비용"이라는 이름 그대로여야 하므로 YUV를 받아 우리가 변환한다.
     */
    private fun buildAnalysis(
        resolutionSelector: ResolutionSelector,
        sink: AnalysisSink,
    ): ImageAnalysis {
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, ANALYSIS_THREAD) }
        analysisExecutor = executor
        val useCase = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(targetRotation)
            // 🔴 큐에 쌓아 처리하면 프레임타임이 실제보다 좋아 보이고 지연만 늘어난다 —
            //    실시간 보행 보조에서 의미 있는 것은 최신 프레임뿐이다.
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
        useCase.setAnalyzer(executor) { image ->
            try {
                if (analysisConfig == null) {
                    // 실제로 받은 버퍼에서 조건을 확정한다(선언 API가 아니라 사실이다).
                    analysisConfig = AnalysisConfig(
                        width = image.width,
                        height = image.height,
                        imageFormat = image.format,
                        imageFormatName = formatName(image.format),
                        rotationDegrees = image.imageInfo.rotationDegrees,
                        requestedFormatName = REQUESTED_FORMAT,
                        backpressureStrategy = BACKPRESSURE,
                    )
                }
                sink.onAnalysisImage(image)
            } catch (t: Throwable) {
                // 여기서 새는 예외를 삼키지 않으면 analyzer가 죽는다. 삼키되 반드시 남긴다.
                Log.e(TAG, "분석 콜백에서 예외", t)
            } finally {
                // 🔴 **여기 하나뿐이다.** 안 닫으면 카메라가 새 프레임을 못 보내고
                //    파이프라인이 통째로 멈춘다(`android-runtime` §2).
                image.close()
            }
        }
        return useCase
    }

    private fun formatName(format: Int): String = when (format) {
        ImageFormat.YUV_420_888 -> "YUV_420_888"
        ImageFormat.FLEX_RGBA_8888 -> "FLEX_RGBA_8888"
        else -> "unknown($format)"
    }

    private fun provideSurface(
        surfaceRequest: SurfaceRequest,
        target: FrameTarget,
        executor: Executor,
        onError: (String) -> Unit,
    ) {
        val resolution = surfaceRequest.resolution
        val surface = target.acquireSurface(resolution.width, resolution.height)
        if (surface == null) {
            // Surface를 억지로 만들어 주지 않는다. 그러면 프레임이 아무데도 도착하지 않는데
            // 카메라는 정상 동작으로 보인다 — 조용한 실패가 가장 나쁘다.
            surfaceRequest.willNotProvideSurface()
            onError("GL SurfaceTexture 미준비 — 카메라 Surface 요청을 거절했다")
            return
        }
        val range = surfaceRequest.expectedFrameRate
        negotiated = NegotiatedConfig(
            width = resolution.width,
            height = resolution.height,
            // CameraX가 프레임레이트를 확정해 주지 않으면 UNSPECIFIED가 온다. 그때 요청값을
            // 베껴 넣으면 "받았다"는 거짓말이 되므로 null로 둔다.
            frameRateRange = if (range == SurfaceRequest.FRAME_RATE_RANGE_UNSPECIFIED) {
                null
            } else {
                "${range.lower}-${range.upper}"
            },
        )
        Log.i(TAG, "Surface 제공: ${resolution.width}x${resolution.height} fps=$range")
        surfaceRequest.setTransformationInfoListener(executor) { info ->
            target.updatePreviewTransform(
                PreviewTransform(
                    rotationDegrees = info.rotationDegrees,
                    targetRotation = info.targetRotation,
                    hasCameraTransform = info.hasCameraTransform(),
                    mirroring = info.isMirroring,
                )
            )
        }
        surfaceRequest.provideSurface(surface, executor) { result ->
            surfaceRequest.clearTransformationInfoListener()
            target.releaseSurface(surface, result.resultCode)
        }
    }

    private companion object {
        const val TAG = "CameraFrameSource"

        const val ANALYSIS_THREAD = "detect-analysis"

        /** `session.json`에 그대로 나가는 문자열. 코드의 선택과 로그가 한 곳에서 나온다. */
        const val REQUESTED_FORMAT = "OUTPUT_IMAGE_FORMAT_YUV_420_888"
        const val BACKPRESSURE = "STRATEGY_KEEP_ONLY_LATEST"
    }
}
