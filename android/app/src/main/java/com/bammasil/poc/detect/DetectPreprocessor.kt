package com.bammasil.poc.detect

import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * E(전처리): `YUV_420_888` [ImageProxy] → **회전** → letterbox → RGB → `/255` → NCHW float32.
 *
 * ## 좌표 사슬과 각 변환의 방향 (규약 §5)
 *
 * ```
 * ① 센서 프레임        image.width × image.height       (예: 1280×720)
 *      ↓ 회전 (rotationDegrees, 시계 방향)
 * ② 회전 후 프레임      rotation.rotatedW × rotatedH     (예: 720×1280)
 *      ↓ letterbox (🔴 **②의 치수에서** 계산한다)
 * ③ letterbox 640 좌표계 = 입력 텐서의 목적지 좌표
 * ```
 *
 * 🔴 **[convert]는 이 사슬을 거꾸로 탄다.** 목적지 픽셀 하나마다
 * `③ → letterbox 역 → ② → 회전 역 → ①`을 **한 번에** 계산해 센서 평면에서 곧장 샘플한다
 * (`rotation_site = "preprocess_sample_map"`). **추가 패스도 추가 버퍼도 없다** — 평면을
 * 회전 복사한 뒤 기존 경로를 태우는 길(`preprocess_plane_copy`)과 다르다.
 *
 * ⚠ 90/270°에서는 목적지 **행** 하나가 센서의 **열**을 훑는다. Y 평면의 캐시 지역성이
 * 나빠지고 그만큼 E의 **값**이 오른다 — 🔴 **정의가 아니라 값이다**(회귀가 아니라 조건
 * 변경이다). **그 비용은 미측정이며 짝 arm `detect_cpu_norot`과의 차분으로만 말한다.**
 *
 * ⚠ 90° 배수 회전은 **순수 인덱스 치환**이라 보간을 새로 끌어들이지 않는다. 아래 이중선형·
 * 색차 최근접·BT.601 full range·`/255`는 회전 전과 **글자 그대로 같다.**
 *
 * ## 무엇을 어디까지 재는가 (E의 정의)
 *
 * 🔴 **E는 `t`를 찍는 위치가 정의다.** 그 위치는 이 클래스가 아니라 **`DetectPipeline`이**
 * 정한다 — E 구간은 **[ImageProxy]의 평면 버퍼에서 바이트를 꺼내는 것부터 `OnnxTensor`가
 * 준비될 때까지**이고, [convert]는 그중 앞부분이다.
 * 안에 있는 것: 평면 3개 bulk copy, letterbox 리샘플, YUV→RGB, `/255`, NCHW 배치,
 * 직접 버퍼로의 bulk put, **그리고 `OnnxTensor` 생성**(이 클래스 밖, 파이프라인 안).
 * 밖에 있는 것: 프레임 대기 해제, 콜백 디스패치, `ImageProxy.close()`, `session.run()`.
 *
 * ⚠ 이 문단은 예전에 `OnnxTensor` 생성을 "밖"이라고 적었는데 **틀렸다** — 파이프라인이
 * 그것까지 E 안에서 잰다(독립 검증 지적). `session.json`의 `detect_timestamp_sites`가
 * 처음부터 옳게 적고 있었고, 이 KDoc만 낡아 있었다. **밖으로 나가는 기록이 정답이다.**
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
     * @param rotation 이 런이 **실제로 쓰는** 회전. 🔴 첫 분석 프레임에서 잠근 값이고
     *   (규약 §4-3), 회전을 적용하지 않는 대조군 arm에서는 **항등(0°)이 들어온다** —
     *   그 arm도 별도 코드 경로가 아니라 **이 함수를 그대로 탄다.**
     * @throws IllegalStateException 평면 구성이 `YUV_420_888`이 아닐 때. 🔴 **조용히
     *   그럴듯한 텐서를 만들지 않는다** — 색이 뒤집힌 입력은 "돌긴 도는데 박스가 이상한"
     *   상태를 만들고 그게 가장 오래 걸리는 실패다.
     */
    fun convert(image: ImageProxy, rotation: DetectContract.Rotation): FloatBuffer {
        val planes = image.planes
        check(planes.size == 3) { "YUV_420_888이 아니다 — 평면이 ${planes.size}개다" }

        val srcW = image.width
        val srcH = image.height
        // 🔴 회전 기하는 **이 프레임의 치수**로 만든 것이어야 한다. 해상도가 런 도중 바뀌면
        //    반사식의 `N−1`이 다른 프레임의 것이 되어 박스가 조용히 밀린다.
        check(rotation.srcW == srcW && rotation.srcH == srcH) {
            "회전 기하의 소스 치수가 이 프레임과 다르다: " +
                "${rotation.srcW}x${rotation.srcH} vs ${srcW}x$srcH"
        }

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

        // 🔴 **회전 후 치수로** letterbox를 잡는다(규약 §3-3 · §5). 센서 치수를 넣으면
        //    90/270°에서 종횡비가 뒤집혀 pad_x와 pad_y가 자리를 바꾼다.
        val box = DetectContract.letterbox(rotation.rotatedW, rotation.rotatedH, dstW, dstH)
        lastLetterbox = box

        val pixels = dstH * dstW
        val rOff = 0
        val gOff = pixels
        val bOff = 2 * pixels
        val padValue = DetectContract.PAD_VALUE_U8 / 255f

        var dy = 0
        while (dy < dstH) {
            val rowBase = dy * dstW
            // 🔴 패딩 판정은 **목적지 좌표**이고 기하는 회전 후 기준이다(위 box).
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
            // ③ → ② : letterbox 역변환. 픽셀 중심 정렬(cv2.resize와 같은 규약)이라
            // 0.5를 더했다 빼며, 이 값은 **회전 후 프레임의 y**다(아직 센서가 아니다).
            // 🔴 반 픽셀을 빼고 더하지 않으면 축소 시 계통적으로 밀린다.
            val fyr = box.toSrcY(dy + 0.5f) - 0.5f

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
                // ③ → ② : letterbox 역변환 (x). 여기까지가 회전 후 좌표계다.
                val fxr = box.toSrcX(dx + 0.5f) - 0.5f

                // ② → ① : 회전 역함수. 🔴 **여기가 rotation_site = preprocess_sample_map의
                //    실체다** — 목적지 좌표에서 센서 좌표를 한 번에 잡는다.
                //    ⚠ 90/270°면 센서 y가 dx를 따라 움직인다(= 열 방향 접근). 그래서 행마다
                //      끌어올리던 y 관련 값들을 여기 안으로 내렸다 — 회전각을 특별 취급해
                //      항등만 빠른 길로 빼면 `detect_cpu_norot`이 "회전 여부"가 아니라
                //      "루프 모양"까지 다른 arm이 되어 차분의 뜻이 사라진다.
                var fx = rotation.toSensorX(fxr, fyr)
                var fy = rotation.toSensorY(fxr, fyr)
                if (fx < 0f) fx = 0f
                if (fy < 0f) fy = 0f
                var x0 = fx.toInt()
                if (x0 > srcW - 1) x0 = srcW - 1
                var x1 = x0 + 1
                if (x1 > srcW - 1) x1 = srcW - 1
                val ax = fx - x0
                var y0 = fy.toInt()
                if (y0 > srcH - 1) y0 = srcH - 1
                var y1 = y0 + 1
                if (y1 > srcH - 1) y1 = srcH - 1
                val ay = fy - y0
                val yRow0 = y0 * yRow
                val yRow1 = y1 * yRow
                val cRow = (y0 shr 1) * uRow
                val cRowV = (y0 shr 1) * vRow

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
