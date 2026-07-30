package com.bammasil.poc.log

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.concurrent.atomic.AtomicLong

/**
 * 프레임 로그를 메모리에 모아 두고 정지 시 한 번에 직렬화한다.
 *
 * 매 프레임 파일 I/O를 하면 write 지연이 프레임타임에 섞이고, 매 프레임 객체를 만들면
 * GC가 측정을 오염시킨다 → 값은 [LongArray] 청크에만 담는다(프레임당 4개 long, 객체 0개).
 *
 * **스레드 규약:** [start]·[record]·[writeCsv]는 **GL 스레드에서만** 부른다.
 * UI는 [recordedFrames] 같은 volatile 카운터만 읽는다.
 */
class FrameLogRecorder(private val chunkFrames: Int = 4096) {

    private val chunks = ArrayList<LongArray>()

    /** 마지막 청크에서 사용한 long 개수. */
    private var cursor = 0

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

    fun start(startedElapsedNs: Long) {
        chunks.clear()
        cursor = 0
        recordedFrames = 0
        drawsWithoutNewFrame = 0
        surfaceFramesAvailable.set(0)
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

    /** GL 스레드에서 프레임 1장을 기록한다. 객체를 만들지 않는다. */
    fun record(tRecvNs: Long, tCaptureNs: Long, tRenderStartNs: Long, tRenderEndNs: Long) {
        if (!isRecording) return
        var chunk = if (chunks.isEmpty()) null else chunks[chunks.size - 1]
        if (chunk == null || cursor == chunk.size) {
            chunk = LongArray(chunkFrames * VALUES_PER_FRAME)
            chunks.add(chunk)
            cursor = 0
        }
        chunk[cursor] = tRecvNs
        chunk[cursor + 1] = tCaptureNs
        chunk[cursor + 2] = tRenderStartNs
        chunk[cursor + 3] = tRenderEndNs
        cursor += VALUES_PER_FRAME
        recordedFrames++
    }

    /**
     * `frames.csv`를 쓴다. **[stop] 이후에만 부른다** — 기록 중에 부르면 그 I/O가
     * 측정 대상에 섞인다. 반환값은 쓴 행 수.
     */
    fun writeCsv(file: File): Int {
        var rows = 0
        BufferedWriter(FileWriter(file), 1 shl 16).use { out ->
            out.write(CSV_HEADER)
            out.write("\n")
            val line = StringBuilder(96)
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
                        .append('\n')
                    out.write(line.toString())
                    rows++
                    i += VALUES_PER_FRAME
                }
            }
        }
        return rows
    }

    companion object {
        const val VALUES_PER_FRAME = 4

        /**
         * 헤더는 `lib/frame_log.py`의 `REQUIRED_COLUMNS + OPTIONAL_COLUMNS`와 **글자까지**
         * 같아야 한다. 오타를 내면 하네스가 미지 열 경고를 내지만 그 지표는 조용히 0이 된다.
         */
        const val CSV_HEADER =
            "frame_idx,t_recv_ns,t_capture_ns,t_render_start_ns,t_render_end_ns,dropped_since_last"

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
