package com.bammasil.poc.log

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.concurrent.atomic.AtomicLong

/**
 * 프레임 로그를 메모리에 모아 두고 정지 시 한 번에 직렬화한다.
 *
 * 매 프레임 파일 I/O를 하면 write 지연이 프레임타임에 섞이고, 매 프레임 객체를 만들면
 * GC가 측정을 오염시킨다 → 값은 [LongArray] 청크에만 담는다(객체 0개).
 *
 * 프레임당 long 개수는 [stride]다. GPU 패스 시간을 받는 런에서만 3칸이 더 붙는다
 * ([VALUES_PER_FRAME_GPU]) — 패스스루 arm은 계측하지 않으므로 그 칸을 잡지도 않는다.
 *
 * **스레드 규약:** [start]·[record]·[setGpuTiming]·[writeCsv]는 **GL 스레드에서만** 부른다.
 * UI는 [recordedFrames] 같은 volatile 카운터만 읽는다.
 */
class FrameLogRecorder(private val chunkFrames: Int = 4096) {

    private val chunks = ArrayList<LongArray>()

    /** 마지막 청크에서 사용한 long 개수. */
    private var cursor = 0

    /**
     * 프레임당 long 개수. [start]에서 정하고 런 도중 바뀌지 않는다 —
     * 바뀌면 [setGpuTiming]의 슬롯 → 오프셋 계산이 통째로 어긋난다.
     */
    private var stride = VALUES_PER_FRAME_BASE

    @Volatile
    var isRecording = false
        private set

    /** UI 표시용. 파일에 남은 행 수와 같다. */
    @Volatile
    var recordedFrames = 0
        private set

    @Volatile
    var startedElapsedNs = 0L
        private set

    @Volatile
    var stoppedElapsedNs = 0L
        private set

    /**
     * `onFrameAvailable`이 불린 횟수(측정 중에만). 프레임 신호 스레드에서 증가하므로 atomic.
     * ⚠ 이건 "카메라가 낸 프레임 수"가 **아니다** — 우리 SurfaceTexture 큐에 도착한 수다.
     */
    val surfaceFramesAvailable = AtomicLong(0)

    /** 새 프레임 없이 `onDrawFrame`이 돈 횟수(리사이즈·중복 requestRender). GL 스레드 전용. */
    @Volatile
    var drawsWithoutNewFrame = 0
        private set

    /**
     * @param gpuColumns GPU 패스 시간 칸을 잡을 것인가(= 3패스 arm인가). false면 청크에
     *   그 칸 자체가 없고 [setGpuTiming]은 무시된다.
     */
    fun start(startedElapsedNs: Long, gpuColumns: Boolean) {
        chunks.clear()
        cursor = 0
        recordedFrames = 0
        drawsWithoutNewFrame = 0
        surfaceFramesAvailable.set(0)
        stride = if (gpuColumns) VALUES_PER_FRAME_GPU else VALUES_PER_FRAME_BASE
        this.startedElapsedNs = startedElapsedNs
        this.stoppedElapsedNs = 0L
        isRecording = true
    }

    /** 측정을 멈춘다. UI 스레드에서 불러도 안전하다(volatile 플래그만 건드린다). */
    fun stop(stoppedElapsedNs: Long) {
        this.stoppedElapsedNs = stoppedElapsedNs
        isRecording = false
    }

    fun noteDrawWithoutNewFrame() {
        if (!isRecording) return
        drawsWithoutNewFrame++
    }

    /**
     * GL 스레드에서 프레임 1장을 기록한다. 객체를 만들지 않는다.
     *
     * @return 이 행의 **슬롯 인덱스**(= `frame_idx`). 기록 중이 아니면 [NO_SLOT].
     *   GPU timer 링이 이 값을 들고 있다가 몇 프레임 뒤에 [setGpuTiming]으로 되돌려준다 —
     *   회수 시점의 최신 행에 쓰면 정렬이 어긋난 채로 그럴듯해 보인다.
     */
    fun record(
        tRecvNs: Long,
        tCaptureNs: Long,
        tRenderStartNs: Long,
        tRenderEndNs: Long,
    ): Int {
        if (!isRecording) return NO_SLOT
        var chunk = if (chunks.isEmpty()) null else chunks[chunks.size - 1]
        if (chunk == null || cursor == chunk.size) {
            chunk = LongArray(chunkFrames * stride)
            chunks.add(chunk)
            cursor = 0
        }
        chunk[cursor] = tRecvNs
        chunk[cursor + 1] = tCaptureNs
        chunk[cursor + 2] = tRenderStartNs
        chunk[cursor + 3] = tRenderEndNs
        if (stride == VALUES_PER_FRAME_GPU) {
            // 새 LongArray는 0으로 차 있다. 0은 "그 패스가 0ms였다"는 적극적 주장이므로
            // **반드시** -1로 덮는다. back-fill이 안 오면 이 값이 그대로 나간다.
            chunk[cursor + 4] = MISSING_NS
            chunk[cursor + 5] = MISSING_NS
            chunk[cursor + 6] = MISSING_NS
        }
        cursor += stride
        val slot = recordedFrames
        recordedFrames++
        return slot
    }

    /**
     * [record]가 돌려준 슬롯에 GPU 패스 시간을 **나중에** 채운다(단위 ns, 없으면 음수).
     * 범위를 벗어난 슬롯이나 GPU 칸이 없는 런에서는 조용히 무시한다 — 잘못된 행에
     * 쓰는 것보다 안 쓰는 쪽이 낫다.
     */
    fun setGpuTiming(slot: Int, stageBNs: Long, stageDNs: Long, presentNs: Long) {
        if (stride != VALUES_PER_FRAME_GPU) return
        if (slot < 0 || slot >= recordedFrames) return
        val chunkIndex = slot / chunkFrames
        if (chunkIndex >= chunks.size) return
        val chunk = chunks[chunkIndex]
        val offset = (slot % chunkFrames) * stride
        chunk[offset + 4] = stageBNs
        chunk[offset + 5] = stageDNs
        chunk[offset + 6] = presentNs
    }

    /**
     * `frames.csv`를 쓴다. **[stop] 이후에만 부른다** — 기록 중에 부르면 그 I/O가
     * 측정 대상에 섞인다. 반환값은 쓴 행 수.
     *
     * @param includeGpuColumns GPU 열을 헤더에 실을 것인가. **실제로 query를 건 런에서만
     *   true다.** 재지 않은 열을 -1로 채워 내보내면 하네스는 "쟀는데 못 얻었다"로 읽는데,
     *   그건 다른 뜻이다(§CSV_HEADER 주석).
     */
    fun writeCsv(file: File, includeGpuColumns: Boolean): Int {
        val emitGpu = includeGpuColumns && stride == VALUES_PER_FRAME_GPU
        var rows = 0
        BufferedWriter(FileWriter(file), 1 shl 16).use { out ->
            out.write(if (emitGpu) CSV_HEADER + ',' + GPU_CSV_HEADER else CSV_HEADER)
            out.write("\n")
            val line = StringBuilder(128)
            for (chunkIndex in chunks.indices) {
                val chunk = chunks[chunkIndex]
                val limit = if (chunkIndex == chunks.size - 1) cursor else chunk.size
                var i = 0
                while (i < limit) {
                    line.setLength(0)
                    line.append(rows).append(',')
                        .append(chunk[i]).append(',')
                        .append(chunk[i + 1]).append(',')
                        .append(chunk[i + 2]).append(',')
                        .append(chunk[i + 3]).append(',')
                        .append(DROPPED_UNKNOWN)
                    if (emitGpu) {
                        appendMs(line.append(','), chunk[i + 4])
                        appendMs(line.append(','), chunk[i + 5])
                        appendMs(line.append(','), chunk[i + 6])
                    }
                    line.append('\n')
                    out.write(line.toString())
                    rows++
                    i += stride
                }
            }
        }
        return rows
    }

    /**
     * ns → ms 문자열. **`String.format`을 쓰지 않는다** — 기본 Locale이 소수점을 `,`로
     * 찍는 지역에서는 CSV가 통째로 깨진다. 음수(없는 값)는 `-1` 그대로 낸다.
     */
    private fun appendMs(line: StringBuilder, ns: Long) {
        if (ns < 0L) {
            line.append(MISSING_NS)
            return
        }
        line.append(ns / 1_000_000L).append('.')
        val frac = ns % 1_000_000L
        var div = 100_000L
        while (div > 0L) {
            line.append(((frac / div) % 10L).toInt())
            div /= 10L
        }
    }

    companion object {
        /** 타임스탬프 4개. GPU를 재지 않는 런의 프레임당 long 개수다. */
        const val VALUES_PER_FRAME_BASE = 4

        /** 위 4개 + `stage_b` / `stage_d` / `gpu_present`(ns). */
        const val VALUES_PER_FRAME_GPU = 7

        /** [record]가 슬롯을 주지 못했을 때. */
        const val NO_SLOT = -1

        /** GPU 칸의 "아직/영영 없음". CSV에는 `-1`로 나간다. */
        const val MISSING_NS = -1L

        /**
         * 헤더 규칙 (스키마 v2):
         *
         * 1. 여기 쓰는 이름은 `lib/frame_log.py`의 `REQUIRED_COLUMNS + OPTIONAL_COLUMNS`에
         *    있는 이름과 **글자까지** 같아야 한다. 오타를 내면 하네스가 미지 열 경고를 내지만
         *    그 지표는 조용히 0이 된다.
         * 2. 그러나 그 목록 **전부를 쓰는 것은 아니다.** 이번 라운드에 실제로 측정하는 열만
         *    싣는다 — 하네스는 부분 집합을 정상 처리한다.
         *
         * ⚠ **"열 없음"과 "전부 -1"은 하네스에서 다르게 보고된다.** 재지 않은 열을 헤더에
         * 넣고 -1로 채우면 `_add_gpu_warnings`가 "열은 있는데 유효 표본이 0개다 = 쟀는데 못
         * 얻었다"고 말한다. 그건 사실이 아니다(애초에 재지 않았다). 그래서 [GPU_CSV_HEADER]는
         * **실제로 query를 건 런에서만** 붙는다(`writeCsv(includeGpuColumns=...)`).
         */
        const val CSV_HEADER =
            "frame_idx,t_recv_ns,t_capture_ns,t_render_start_ns,t_render_end_ns,dropped_since_last"

        /**
         * 3패스 arm에서만 붙는 GPU 패스 시간 열. 이름은 `lib/frame_log.py`의
         * `GPU_TIME_COLUMNS`와 **글자까지** 같아야 한다.
         *
         * `stage_i_ms`는 없다 — ④ 오버레이 arm이 아직 없어서 잴 것이 없다. 재지 않는 열을
         * 싣지 않는 것이 위 규칙이다.
         *
         * ⚠ 단위는 **ms(float)**이고 출처는 GPU 시계다. `t_*_ns`(CLOCK_BOOTTIME)와 다른
         * 시계라 서로 더하거나 뺄 수 없다(스키마 §2).
         */
        const val GPU_CSV_HEADER = "stage_b_ms,stage_d_ms,gpu_present_ms"

        /**
         * `dropped_since_last`는 **항상 -1**이다.
         *
         * 표시 경로 2-C에는 `ImageProxy`가 없어 백프레셔로 버려진 프레임 수를 실제로 셀 수
         * 없다. `0`은 "드롭이 없었다"는 **적극적 주장**이므로 거짓말이 된다 → 스키마의
         * "없는 값 = -1" 규약(`docs/FRAME_LOG_SCHEMA.md` §2)을 따른다.
         */
        const val DROPPED_UNKNOWN = -1
    }
}
