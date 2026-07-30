package com.bammasil.poc.source

import android.content.Context
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executor

/**
 * CameraX `Preview` 하나만 바인딩하는 소스 (표시 경로 2-C, `PIPELINE_STACK.md` §H).
 *
 * `ImageAnalysis`를 쓰지 않는다. YUV를 CPU로 받아 변환해 그리면 실제 파이프라인이 영원히
 * 내지 않을 비용이 베이스라인에 섞인다. 프레임은 우리 `SurfaceTexture`(OES)로 바로 들어가
 * GPU에 머문다.
 *
 * 결과적으로 이 경로에는 **분석 콜백도 `ImageProxy`도 없다** — 그래서 버려진 프레임 수를
 * 셀 수 없고 `dropped_since_last`가 -1이다(`FrameLogRecorder` 참고).
 */
class CameraFrameSource(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) : FrameSource {

    override val kind: String = "camera"

    @Volatile
    override var negotiated: NegotiatedConfig? = null
        private set

    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null

    override fun start(request: FrameRequest, target: FrameTarget, onError: (String) -> Unit) {
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
                    // 요청값이다. 기기가 이걸 준다는 보장은 없으므로 받은 값은 따로 기록한다.
                    .setTargetFrameRate(Range(request.fps, request.fps))
                    .build()
                newPreview.setSurfaceProvider(
                    mainExecutor,
                    Preview.SurfaceProvider { surfaceRequest ->
                        provideSurface(surfaceRequest, target, mainExecutor, onError)
                    },
                )
                // 재바인딩 경로(GL 컨텍스트 재생성)에서 이전 use case가 남지 않게 한다.
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    newPreview,
                )
                provider = cameraProvider
                preview = newPreview
                Log.i(TAG, "카메라 바인딩 완료 (요청 ${request.width}x${request.height}@${request.fps})")
            } catch (t: Throwable) {
                Log.e(TAG, "카메라 바인딩 실패", t)
                onError("카메라 바인딩 실패: ${t.javaClass.simpleName}: ${t.message}")
            }
        }, mainExecutor)
    }

    override fun stop() {
        provider?.unbindAll()
        preview = null
        provider = null
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
        surfaceRequest.provideSurface(surface, executor) { result ->
            target.releaseSurface(surface, result.resultCode)
        }
    }

    private companion object {
        const val TAG = "CameraFrameSource"
    }
}
