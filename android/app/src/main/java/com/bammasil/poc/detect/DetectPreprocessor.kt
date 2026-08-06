package com.bammasil.poc.detect

import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * E(전처리): `YUV_420_888` [ImageProxy] → letterbox → RGB → `/255` → NCHW float32.
 *
 * ## 무엇을 어디까지 재는가 (E의 정의)
 *
 * 🔴 **E는 `t`를 찍는 위치가 정의다.** [convert]가 도는 구간 전체가 E이고, 그 구간은
 * **[ImageProxy]의 평면 버퍼에서 바이트를 꺼내는 것부터 입력 [FloatBuffer]가 준비될 때까지**다.
 * 밖에 있는 것: 프레임 대기 해제, 콜백 디스패치, `ImageProxy.close()`, `OnnxTensor` 생성,
 * `session.run()`. 안에 있는 것: 평면 3개 bulk copy, letterbox 리샘플, YUV→RGB, `/255`,
 * NCHW 배치, 직접 버퍼로의 bulk put.
 *
 * ⚠ 이 값은 **입력 포맷의 함수**다. `ImageAnalysis`가 `RGBA_8888`을 내주게 두면 변환이
 * CameraX 안에서 일어나 **E 밖에 숨고 E가 과소로 나온다** — 그래서 YUV로 받는다
 * (`CameraFrameSource` 주석).
 *
 * ## 가정 (전부 `DetectContract`가 소유한다)
 *
 * letterbox 정렬 center · 패딩 114 · 이중선형 보간 · BT.601 full range YUV→RGB.
 * 넷 다 기계로 확인된 계약값이 **아니고**, 그 사실이 `session.json`으로 나간다.
 *
 * ## 스레드
 *
 * **분석 스레드 전용이다.** 내부 버퍼를 재사용하므로(프레임당 객체 0개) 두 스레드가 동시에
 * 들어오면 텐서가 섞인다. 재사용하는 이유는 GC가 측정을 오염시키지 않게 하기 위해서다 —
 * 720p Y 평면 하나가 900KB이고 입력 텐서가 4.9MB다.
 */
class DetectPreprocessor(
    private val dstW: Int,
    private val dstH: Int,
    private val channels: Int,
) {

    /** 입력 텐서. `createTensor`가 직접 읽는 네이티브 메모리다. */
    val inputBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(channels * dstH * dstW * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    /**
     * 직접 버퍼에 float 하나씩 put 하는 것보다 **배열에 쓰고 한 번에 밀어 넣는 쪽**이 훨씬
     * 싸다(경계 검사 120만 번이 사라진다). 이 배열도 재사용한다.
     */
    private val plane = FloatArray(channels * dstH * dstW)

    private var yBytes = ByteArray(0)
    private var uBytes = ByteArray(0)
    private var vBytes = ByteArray(0)

    /** 마지막으로 계산한 letterbox 기하. 후처리 역변환이 **같은 값**을 써야 한다. */
    var lastLetterbox: DetectContract.Letterbox? = null
        private set

    init {
        // 🔴 아래 루프는 RGB 3평면을 전제로 오프셋을 잡는다. 그래프가 다른 채널 수를 말하면
        //    조용히 틀린 텐서를 만드는 대신 여기서 죽는다.
        require(channels == 3) { "입력 채널이 3이 아니다: $channels (그래프에서 읽은 값)" }
    }

    /**
     * 프레임 하나를 입력 텐서로 만든다. 반환값은 [inputBuffer](position=0으로 되감겨 있다).
     *
     * @throws IllegalStateException 평면 구성이 `YUV_420_888`이 아닐 때. 🔴 **조용히
     *   그럴듯한 텐서를 만들지 않는다** — 색이 뒤집힌 입력은 "돌긴 도는데 박스가 이상한"
     *   상태를 만들고 그게 가장 오래 걸리는 실패다.
     */
    fun convert(image: ImageProxy): FloatBuffer {
        val planes = image.planes
        check(planes.size == 3) { "YUV_420_888이 아니다 — 평면이 ${planes.size}개다" }

        val srcW = image.width
        val srcH = image.height

        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        yBytes = copyPlane(yPlane.buffer, yBytes)
        uBytes = copyPlane(uPlane.buffer, uBytes)
        vBytes = copyPlane(vPlane.buffer, vBytes)
        val yRow = yPlane.rowStride
        val yPix = yPlane.pixelStride
        val uRow = uPlane.rowStride
        val uPix = uPlane.pixelStride
        val vRow = vPlane.rowStride
        val vPix = vPlane.pixelStride

        val box = DetectContract.letterbox(srcW, srcH, dstW, dstH)
        lastLetterbox = box

        val pixels = dstH * dstW
        val rOff = 0
        val gOff = pixels
        val bOff = 2 * pixels
        val padValue = DetectContract.PAD_VALUE_U8 / 255f
        val invScale = 1f / box.scale

        var dy = 0
        while (dy < dstH) {
            val rowBase = dy * dstW
            if (dy < box.padY || dy >= box.padY + box.contentH) {
                // 패딩 행 — 세 채널 전부 회색. (720p→640이면 전체의 43.75%가 여기다.)
                var dx = 0
                while (dx < dstW) {
                    val p = rowBase + dx
                    plane[rOff + p] = padValue
                    plane[gOff + p] = padValue
                    plane[bOff + p] = padValue
                    dx++
                }
                dy++
                continue
            }
            // 픽셀 중심 정렬(cv2.resize와 같은 규약). 0.5를 빼고 더하지 않으면 축소 시
            // 반 픽셀이 계통적으로 밀린다.
            var fy = ((dy - box.padY) + 0.5f) * invScale - 0.5f
            if (fy < 0f) fy = 0f
            var y0 = fy.toInt()
            if (y0 > srcH - 1) y0 = srcH - 1
            var y1 = y0 + 1
            if (y1 > srcH - 1) y1 = srcH - 1
            val ay = fy - y0
            val yRow0 = y0 * yRow
            val yRow1 = y1 * yRow
            val cRow = (y0 shr 1) * uRow
            val cRowV = (y0 shr 1) * vRow

            var dx = 0
            while (dx < dstW) {
                val p = rowBase + dx
                if (dx < box.padX || dx >= box.padX + box.contentW) {
                    plane[rOff + p] = padValue
                    plane[gOff + p] = padValue
                    plane[bOff + p] = padValue
                    dx++
                    continue
                }
                var fx = ((dx - box.padX) + 0.5f) * invScale - 0.5f
                if (fx < 0f) fx = 0f
                var x0 = fx.toInt()
                if (x0 > srcW - 1) x0 = srcW - 1
                var x1 = x0 + 1
                if (x1 > srcW - 1) x1 = srcW - 1
                val ax = fx - x0

                // 휘도는 이중선형(DetectContract.RESIZE_INTERPOLATION_ASSUMPTION).
                val y00 = (yBytes[yRow0 + x0 * yPix].toInt() and 0xFF).toFloat()
                val y01 = (yBytes[yRow0 + x1 * yPix].toInt() and 0xFF).toFloat()
                val y10 = (yBytes[yRow1 + x0 * yPix].toInt() and 0xFF).toFloat()
                val y11 = (yBytes[yRow1 + x1 * yPix].toInt() and 0xFF).toFloat()
                val yTop = y00 + (y01 - y00) * ax
                val yBot = y10 + (y11 - y10) * ax
                val yy = yTop + (yBot - yTop) * ay

                // 색차는 최근접(이미 2:1로 서브샘플된 평면이라 표준 관행이다).
                val cx = x0 shr 1
                val uu = (uBytes[cRow + cx * uPix].toInt() and 0xFF) - 128
                val vv = (vBytes[cRowV + cx * vPix].toInt() and 0xFF) - 128

                // BT.601 full range (DetectContract.YUV_TO_RGB_ASSUMPTION).
                var r = yy + 1.402f * vv
                var g = yy - 0.344136f * uu - 0.714136f * vv
                var b = yy + 1.772f * uu
                if (r < 0f) r = 0f else if (r > 255f) r = 255f
                if (g < 0f) g = 0f else if (g > 255f) g = 255f
                if (b < 0f) b = 0f else if (b > 255f) b = 255f

                // /255 하나뿐이다 — 평균/표준편차 정규화 없음(metadata.json preprocess).
                plane[rOff + p] = r * INV_255
                plane[gOff + p] = g * INV_255
                plane[bOff + p] = b * INV_255
                dx++
            }
            dy++
        }

        inputBuffer.clear()
        inputBuffer.put(plane)
        inputBuffer.rewind()
        return inputBuffer
    }

    /**
     * 평면 버퍼를 배열로 bulk copy. **재사용 배열을 돌려준다** — 매 프레임 900KB를 새로
     * 잡으면 GC가 측정을 오염시킨다.
     *
     * ⚠ `ByteBuffer.get(i)`로 직접 읽지 않고 복사하는 이유: 위 루프가 픽셀당 12번 읽는데
     * 배열 접근이 직접 버퍼 접근보다 눈에 띄게 싸다. 이 복사 비용도 **E 안에 있다**.
     */
    private fun copyPlane(buffer: ByteBuffer, reuse: ByteArray): ByteArray {
        buffer.rewind()
        val n = buffer.remaining()
        val out = if (reuse.size >= n) reuse else ByteArray(n)
        buffer.get(out, 0, n)
        return out
    }

    private companion object {
        const val INV_255 = 1f / 255f
    }
}
