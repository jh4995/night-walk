package com.bammasil.poc.log

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * `detect.csv`를 메모리에 모아 두고 정지 시 한 번에 직렬화한다. **행 하나 = 추론 1회**다
 * (프레임이 아니다 — `docs/FRAME_LOG_SCHEMA.md` §2-D).
 *
 * ## 🔴 왜 [FrameLogRecorder]를 재사용하지 않는가
 *
 * 그쪽 KDoc이 `start`·`record`·`writeCsv`를 **GL 스레드에서만** 부르라고 못 박았고, 탐지는
 * 다른 스레드다. 같은 인스턴스를 두 스레드에서 쓰면 청크 리스트와 커서가 찢어지고, 증상은
 * "가끔 행이 하나 이상하다"라 눈으로 안 걸린다. 그래서 **별 클래스**로 만들되 규약은
 * 글자 그대로 **복제**한다:
 *
 * - 청크 [LongArray] 누적 → 정지 후 일괄 write (프레임당 객체 0개, GC 오염 방지)
 * - 없는 값은 `-1` 센티널 (빈칸이 아니다)
 * - ms 포맷은 **Locale 무관**. `String.format`을 쓰지 않는다 — 기본 Locale이 소수점을 `,`로
 *   찍는 지역에서는 CSV가 통째로 깨진다.
 * - 🔴 **소수 6자리로 낸다.** 스키마가 요구하는 하한은 3자리인데, 1자리로 쓰면 박스 0개일 때
 *   G가 `0.0`이 되고 하네스의 하한 가드(`> 0`)가 **가장 싼 샘플만 골라 폐기**해 G 분포가
 *   위로 치우친다. 그 편향은 폐기 카운트에만 남아 되물을 때 보이지 않는다.
 *
 * ## 스레드 규약
 *
 * [record]는 **탐지 워커 스레드에서만** 부른다. [start]는 기록 시작 직전(UI 스레드),
 * [stop]/[writeCsv]는 **워커가 quiesce된 뒤에만** 부른다 — 그 순서가 `MainActivity`의
 * A12 정지 순서이고, 어기면 마지막 행이 찢긴다.
 */
class DetectLogRecorder(private val chunkRows: Int = 1024) {

    private val chunks = ArrayList<LongArray>()

    /** 마지막 청크에서 사용한 long 개수. */
    private var cursor = 0

    @Volatile
    var isRecording = false
        private set

    /** UI 표시용. 파일에 남을 행 수와 같다. */
    @Volatile
    var recordedRows = 0
        private set

    fun start() {
        chunks.clear()
        cursor = 0
        recordedRows = 0
        isRecording = true
    }

    /** 기록만 멈춘다. 파일은 [writeCsv]가 따로 쓴다(측정 중 I/O를 피하려는 것이 목적이다). */
    fun stop() {
        isRecording = false
    }

    /**
     * 추론 1회를 기록한다. 객체를 만들지 않는다.
     *
     * @param maxConfMicro `max_conf × 1_000_000`을 반올림한 정수. 없으면 -1.
     *   🔴 float를 LongArray에 담을 방법이 필요한데 비트 패턴을 쓰면 -1 센티널과 겹친다
     *   (`-1`도 유효한 float 비트다) — 고정소수점이면 겹치지 않는다.
     *
     * ⚠ **[isRecording]을 보지 않는다.** [stop] 직후에도 워커에 추론 하나가 떠 있을 수
     * 있는데(A12의 quiesce가 그것을 기다린다), 그 행은 기록 창 안에서 **받은** 프레임의
     * 결과다. 여기서 버리면 `detect.csv`의 행 수가 `inferences_run`보다 적어지고, 그
     * 어긋남은 "탐지가 몇 번 돌았는가"를 두 값 중 어느 쪽으로도 말할 수 없게 만든다.
     * 창 밖의 행을 만들지 않는 책임은 호출자(`DetectPipeline`의 `enabled` 게이트)에 있다.
     */
    fun record(
        tRecvNs: Long,
        tEndNs: Long,
        tImageCaptureNs: Long,
        maxConfMicro: Long,
        stageENs: Long,
        stageFNs: Long,
        stageGNs: Long,
        boxesPreNms: Long,
        boxesOut: Long,
        skippedWhileBusy: Long,
    ) {
        var chunk = if (chunks.isEmpty()) null else chunks[chunks.size - 1]
        if (chunk == null || cursor == chunk.size) {
            chunk = LongArray(chunkRows * STRIDE)
            chunks.add(chunk)
            cursor = 0
        }
        chunk[cursor] = tRecvNs
        chunk[cursor + 1] = tEndNs
        chunk[cursor + 2] = tImageCaptureNs
        chunk[cursor + 3] = maxConfMicro
        chunk[cursor + 4] = stageENs
        chunk[cursor + 5] = stageFNs
        chunk[cursor + 6] = stageGNs
        chunk[cursor + 7] = boxesPreNms
        chunk[cursor + 8] = boxesOut
        chunk[cursor + 9] = skippedWhileBusy
        cursor += STRIDE
        recordedRows++
    }

    /**
     * `detect.csv`를 쓴다. **[stop] + 워커 quiesce 이후에만 부른다.** 반환값은 쓴 행 수.
     *
     * 행이 0개면 **파일을 만들지 않고 0을 돌려준다** — 빈 CSV를 내면 `read_detect`가
     * "행이 하나도 없다"로 죽고, 그건 "탐지가 한 번도 안 돌았다"라는 사실을 파일 존재로
     * 덮는 것이다. 그 사실은 `session.json`의 회계 블록이 말한다.
     */
    fun writeCsv(file: File): Int {
        if (recordedRows == 0) return 0
        var rows = 0
        BufferedWriter(FileWriter(file), 1 shl 16).use { out ->
            out.write(CSV_HEADER)
            out.write("\n")
            val line = StringBuilder(160)
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
                    appendFixed(line, chunk[i + 3], MICRO_DIVISOR, MICRO_DIGITS)
                    line.append(',')
                    appendFixed(line, chunk[i + 4], NS_PER_MS, MS_DIGITS)
                    line.append(',')
                    appendFixed(line, chunk[i + 5], NS_PER_MS, MS_DIGITS)
                    line.append(',')
                    appendFixed(line, chunk[i + 6], NS_PER_MS, MS_DIGITS)
                    line.append(',')
                        .append(chunk[i + 7]).append(',')
                        .append(chunk[i + 8]).append(',')
                        .append(chunk[i + 9])
                        .append('\n')
                    out.write(line.toString())
                    rows++
                    i += STRIDE
                }
            }
        }
        return rows
    }

    /**
     * 고정소수점 정수 → 소수 문자열. **`String.format`을 쓰지 않는다**(Locale 함정).
     * 음수(없는 값)는 `-1` 그대로 낸다.
     */
    private fun appendFixed(line: StringBuilder, value: Long, divisor: Long, digits: Int) {
        if (value < 0L) {
            line.append(MISSING)
            return
        }
        line.append(value / divisor).append('.')
        val frac = value % divisor
        var div = divisor / 10L
        var d = 0
        while (d < digits) {
            line.append(((frac / div) % 10L).toInt())
            div /= 10L
            d++
        }
    }

    companion object {

        /** 행당 long 개수. [CSV_HEADER]의 `detect_idx`를 뺀 열 수와 같다. */
        const val STRIDE = 10

        /** 없는 값. 스키마 §2-D의 "없는 값은 -1" 규약. */
        const val MISSING = -1L

        const val NS_PER_MS = 1_000_000L
        const val MS_DIGITS = 6

        /** `max_conf`를 정수로 담기 위한 배율. 0..1 점수를 백만분율로 본다. */
        const val MICRO_DIVISOR = 1_000_000L
        const val MICRO_DIGITS = 6

        /**
         * 🔴 **열 순서는 `lib/frame_log.py`의 스키마 순서다** — `write_detect()`가 참조
         * 구현이고 그쪽은 `DETECT_REQUIRED_COLUMNS` → `DETECT_OPTIONAL_COLUMNS`
         * (`t_detect_end_ns`, `t_image_capture_ns`, `max_conf` → E·F·G → 카운트 3개) 순으로 쓴다.
         *
         * ⚠ **합계 열을 만들지 않는다.** 총 소요는 `t_detect_end_ns − t_detect_recv_ns`로
         * 유도 가능하고, 스키마 §2가 "유도 가능한 값은 저장하지 않는다"고 정했다.
         *
         * ⚠ 이름 하나라도 오타를 내면 하네스는 미지 열 경고를 내지만 **그 지표는 조용히
         * 0이 된다**(`stage_f_ms`를 `stage_f`로 쓰면 "추론 시간을 재지 않았다"가 된다).
         */
        const val CSV_HEADER =
            "detect_idx,t_detect_recv_ns,t_detect_end_ns,t_image_capture_ns,max_conf," +
                "stage_e_ms,stage_f_ms,stage_g_ms,boxes_pre_nms,boxes_out,skipped_while_busy"
    }
}
