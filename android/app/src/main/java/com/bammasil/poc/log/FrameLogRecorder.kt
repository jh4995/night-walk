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
 * 프레임당 long 개수는 [stride]다. GPU 패스 시간을 받는 런에서만 **arm의 패스 수만큼** 칸이
 * 더 붙는다(`RenderArm.gpuColumns`) — 패스스루 arm은 계측하지 않으므로 그 칸을 잡지도 않는다.
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

    /**
     * 이 런이 잡은 GPU 열 개수. 0이면 GPU 칸 자체가 없다.
     * [stride] = [VALUES_PER_FRAME_BASE] + 이 값.
     */
    private var gpuColumnCount = 0

    /**
     * CSV에 붙일 GPU 열 헤더(쉼표 구분). [start]에서 한 번 만든다 —
     * 프레임 경로에서는 만들지 않는다.
     */
    private var gpuHeader = ""

    /**
     * 이 런이 ④ 오버레이 열 3개(`stage_h_ms` / `overlay_boxes` / `t_overlay_source_ns`)를
     * 싣는가. **③ 결과를 그리는 arm에서만** true다(`RenderArm.usesDynamicHighlightBoxes`).
     *
     * 🔴 **정적 더미 arm에는 싣지 않는다.** 재지 않은 열을 헤더에 넣고 -1로 채우면 하네스가
     * "쟀는데 못 얻었다"로 읽는데 그건 다른 뜻이다 — [CSV_HEADER]의 같은 규약이다.
     */
    private var overlayColumns = false

    /** [overlayColumns]가 true면 3, 아니면 0. [stride] 계산과 오프셋에 함께 쓴다. */
    private var overlayValueCount = 0

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
     * @param gpuColumns 이 런이 실을 GPU 패스 시간 열 이름 — **패스 순서 그대로**이며
     *   출처는 `RenderArm.gpuColumns` 하나다. 비어 있으면(패스스루) 청크에 그 칸 자체가
     *   없고 [setGpuTiming]은 무시된다.
     * @param overlayColumns ④ 오버레이 열 3개를 실을 것인가. 🔴 **③ 결과를 그리는 arm에서만
     *   true다** — 그렇지 않은 런에 이 열을 -1로 채워 내면 "쟀는데 못 얻었다"가 된다.
     */
    fun start(
        startedElapsedNs: Long,
        gpuColumns: List<String>,
        overlayColumns: Boolean = false,
    ) {
        chunks.clear()
        cursor = 0
        recordedFrames = 0
        drawsWithoutNewFrame = 0
        surfaceFramesAvailable.set(0)
        gpuColumnCount = gpuColumns.size
        gpuHeader = gpuColumns.joinToString(",")
        this.overlayColumns = overlayColumns
        overlayValueCount = if (overlayColumns) OVERLAY_VALUE_COUNT else 0
        stride = VALUES_PER_FRAME_BASE + gpuColumnCount + overlayValueCount
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
        /**
         * ④ H칸(좌표 평활·hold)의 구간 길이(ns). **CPU 벽시계**이며 GPU 시계가 아니다.
         * 재지 않았으면 [MISSING_NS].
         */
        stageHNs: Long = MISSING_NS,
        /**
         * 🔴 **그 프레임에 실제로 그린 박스 수.** `0`은 정상값이고
         * [OVERLAY_BOXES_UNRECORDED]만 "기록하지 않았다"다.
         */
        overlayBoxes: Int = OVERLAY_BOXES_UNRECORDED,
        /**
         * 그 프레임이 쓴 탐지 결과의 **게시 시각**(`CLOCK_BOOTTIME`).
         * 🔴 **박스가 0개(빈 결과)여도 적는다** — [MISSING_NS]는 첫 추론 완료 전만이다.
         */
        tOverlaySourceNs: Long = MISSING_NS,
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
        // 새 LongArray는 0으로 차 있다. 0은 "그 패스가 0ms였다"는 적극적 주장이므로
        // **반드시** -1로 덮는다. back-fill이 안 오면 이 값이 그대로 나간다.
        var g = 0
        while (g < gpuColumnCount) {
            chunk[cursor + VALUES_PER_FRAME_BASE + g] = MISSING_NS
            g++
        }
        // ④ 오버레이 칸은 **GPU 칸 뒤**에 온다. 그 순서 덕에 [setGpuTiming]의 오프셋 계산이
        // 그대로 남는다(back-fill 오프셋을 건드리면 열이 한 칸씩 밀린 채 그럴듯해 보인다).
        if (overlayValueCount > 0) {
            val o = cursor + VALUES_PER_FRAME_BASE + gpuColumnCount
            chunk[o] = stageHNs
            chunk[o + 1] = overlayBoxes.toLong()
            chunk[o + 2] = tOverlaySourceNs
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
     *
     * @param values 패스 순서대로의 ns. **호출자가 재사용하는 배열**이므로 여기서 보관하지
     *   않고 즉시 복사한다.
     * @param count 실제로 유효한 개수. 이 런이 잡은 칸 수와 다르면 **아무것도 쓰지 않는다** —
     *   어긋난 개수로 쓰면 열이 한 칸씩 밀린 채 그럴듯해 보인다.
     */
    fun setGpuTiming(slot: Int, values: LongArray, count: Int) {
        if (gpuColumnCount == 0 || count != gpuColumnCount) return
        if (slot < 0 || slot >= recordedFrames) return
        val chunkIndex = slot / chunkFrames
        if (chunkIndex >= chunks.size) return
        val chunk = chunks[chunkIndex]
        val offset = (slot % chunkFrames) * stride + VALUES_PER_FRAME_BASE
        var g = 0
        while (g < count) {
            chunk[offset + g] = values[g]
            g++
        }
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
        val emitGpu = includeGpuColumns && gpuColumnCount > 0
        var rows = 0
        BufferedWriter(FileWriter(file), 1 shl 16).use { out ->
            val header = StringBuilder(CSV_HEADER)
            if (emitGpu) header.append(',').append(gpuHeader)
            if (overlayValueCount > 0) header.append(',').append(OVERLAY_HEADER)
            out.write(header.toString())
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
                        var g = 0
                        while (g < gpuColumnCount) {
                            appendMs(line.append(','), chunk[i + VALUES_PER_FRAME_BASE + g])
                            g++
                        }
                    }
                    if (overlayValueCount > 0) {
                        val o = i + VALUES_PER_FRAME_BASE + gpuColumnCount
                        // stage_h_ms — ms **소수 6자리**다. 🔴 소수 1자리로 쓰면 싼 샘플이
                        // 0.0이 되고 하네스의 하한 가드(`> 0`)가 그 샘플만 골라 폐기한다
                        // (E·F·G에 같은 요구가 있는 이유와 글자 그대로 같다).
                        appendMs(line.append(','), chunk[o])
                        // overlay_boxes — **개수**다. 0은 정상값이고 -1만 "기록 안 함"이다.
                        line.append(',').append(chunk[o + 1])
                        // t_overlay_source_ns — 정수 ns 그대로.
                        line.append(',').append(chunk[o + 2])
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

        /** [record]가 슬롯을 주지 못했을 때. */
        const val NO_SLOT = -1

        /** GPU 칸의 "아직/영영 없음". CSV에는 `-1`로 나간다. */
        const val MISSING_NS = -1L

        /** ④ 오버레이 칸 수(`stage_h_ms` / `overlay_boxes` / `t_overlay_source_ns`). */
        const val OVERLAY_VALUE_COUNT = 3

        /**
         * 🔴 `overlay_boxes`의 **"이 프레임을 기록하지 않았다"**. `0`은 "그 프레임에 그린
         * 박스가 없었다"는 **정상값**이므로 없음의 표식으로 쓸 수 없다 —
         * 하네스의 폐기 하한이 `>= 0`인 것과 같은 논거다(`docs/FRAME_LOG_SCHEMA.md` §2).
         */
        const val OVERLAY_BOXES_UNRECORDED = -1

        /**
         * ④ 오버레이 열 헤더(스키마 v7). 이름은 `lib/frame_log.py`의 `OPTIONAL_COLUMNS`와
         * **글자까지** 같아야 한다.
         *
         * 🔴 **GPU 열이 아니다.** `stage_h_ms`는 CPU 벽시계(`elapsedRealtimeNanos`, GL 스레드)라
         * `gpu_sum_ms`·`stage_d_total_ms`에 들어가지 않고, 그래서 `RenderArm.gpuColumns`가
         * 아니라 여기 상수로 있다(그 목록에 넣으면 GPU 열로 읽힌다).
         *
         * 🔴 **신선도(`t_render_start_ns − t_overlay_source_ns`)를 싣지 않는다** — 유도값이라
         * PC가 계산한다. 같은 이름의 열을 앱이 내면 하네스가 미지 열 경고를 낸다.
         */
        const val OVERLAY_HEADER = "stage_h_ms,overlay_boxes,t_overlay_source_ns"

        /**
         * 헤더 규칙 (스키마 v4):
         *
         * 0. **GPU 열은 여기 없다.** 이 상수는 타임스탬프 4개 + `dropped_since_last`뿐이고,
         *    GPU 열은 런마다 `RenderArm.gpuColumns`에서 와서 [start]가 만든 [gpuHeader]로
         *    뒤에 붙는다. v4에서 들어온 세 열(`stage_d_analyze2_ms` / `stage_d_build2_ms` /
         *    `stage_d_apply2_ms`)도 **여기에 적지 않는다** — 조합 arm
         *    (`drago_clahe_chain`)의 `gpuColumns`가 그 이름의 유일한 출처이고, 여기에 사본을
         *    만들면 arm마다 다른 열 구성이 한 칸씩 밀린다(아래 ⚠ 참고).
         *
         * 1. 여기 쓰는 이름은 `lib/frame_log.py`의 `REQUIRED_COLUMNS + OPTIONAL_COLUMNS`에
         *    있는 이름과 **글자까지** 같아야 한다. 오타를 내면 하네스가 미지 열 경고를 내지만
         *    그 지표는 조용히 0이 된다.
         * 2. 그러나 그 목록 **전부를 쓰는 것은 아니다.** 이번 라운드에 실제로 측정하는 열만
         *    싣는다 — 하네스는 부분 집합을 정상 처리한다.
         *
         * ⚠ **"열 없음"과 "전부 -1"은 하네스에서 다르게 보고된다.** 재지 않은 열을 헤더에
         * 넣고 -1로 채우면 `_add_gpu_warnings`가 "열은 있는데 유효 표본이 0개다 = 쟀는데 못
         * 얻었다"고 말한다. 그건 사실이 아니다(애초에 재지 않았다). 그래서 GPU 열은
         * **실제로 query를 건 런에서만** 붙는다(`writeCsv(includeGpuColumns=...)`).
         *
         * ⚠ GPU 열 이름의 출처는 `RenderArm.gpuColumns` **하나**다. 여기에 두 번째 사본을
         * 만들지 않는다 — arm마다 열 구성이 다르므로(3패스 arm 3개, drago 5개) 상수로 박으면
         * 어긋나는 날 열이 한 칸씩 밀린다. 단위는 **ms(float)**이고 출처는 GPU 시계라
         * `t_*_ns`(CLOCK_BOOTTIME)와 서로 더하거나 뺄 수 없다(스키마 §2).
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
