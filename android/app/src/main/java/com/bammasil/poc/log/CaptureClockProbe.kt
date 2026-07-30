package com.bammasil.poc.log

/**
 * `SurfaceTexture.getTimestamp()`가 어느 시계 기준인지 **실측으로** 판별한다.
 *
 * 기기마다 `CLOCK_MONOTONIC`(`System.nanoTime()`)일 수도 `CLOCK_BOOTTIME`
 * (`SystemClock.elapsedRealtimeNanos()`)일 수도 있다. 추측해서 `capture_clock_base`를
 * 적으면 하네스가 그 선언을 믿고 `capture_to_render_ms`를 지연 근거로 쓴다.
 *
 * 판별 방법: 프레임 몇 장에서 `getTimestamp()`·`nanoTime()`·`elapsedRealtimeNanos()`를
 * 나란히 찍고, 취득 시각이 **어느 시계 기준일 때 물리적으로 말이 되는지**(지금보다 조금 전)를
 * 본다. 두 시계 차이가 거의 없으면(딥슬립이 없었으면) 판별 자체가 불가능하므로
 * 한쪽을 고르지 않고 `unknown`으로 남긴다.
 */
class ClockProbeSample(
    val tCaptureNs: Long,
    /** `System.nanoTime()` — CLOCK_MONOTONIC */
    val monotonicNs: Long,
    /** `SystemClock.elapsedRealtimeNanos()` — CLOCK_BOOTTIME */
    val boottimeNs: Long,
)

class CaptureClockVerdict(
    /** `"monotonic"` / `"boottime"` / `"unknown"` 중 하나. */
    val base: String,
    val reason: String,
    /**
     * 두 시계의 오프셋. 값이 있으면 하네스가 나중에 `t_capture_ns`를 정규화할 수 있다
     * (딥슬립이 없었던 기기에서는 0에 가깝다).
     */
    val offsetBoottimeMinusMonotonicNs: Long,
    val samples: List<ClockProbeSample>,
)

object CaptureClockProbe {

    const val MONOTONIC = "monotonic"
    const val BOOTTIME = "boottime"
    const val UNKNOWN = "unknown"

    /** 몇 장까지 표본을 남길지. 두 시계 읽기가 추가되는 프레임 수라 작게 둔다. */
    const val SAMPLE_LIMIT = 8

    /**
     * 이 아래로 두 시계가 붙어 있으면 **판별 불가**로 본다. 부팅 후 딥슬립이 없었으면
     * MONOTONIC과 BOOTTIME이 사실상 같아서 어느 쪽인지 구분할 근거가 없다.
     * 이 경우 어느 기준이든 `capture_to_render_ms`는 유효하다.
     */
    const val AMBIGUOUS_OFFSET_NS = 1_000_000L // 1ms

    /** 취득 시각은 "지금"보다 조금 전이어야 한다. 이 범위 밖이면 그 시계가 기준이 아니다. */
    const val PLAUSIBLE_LAG_MIN_NS = -5_000_000L // -5ms (읽기 순서 오차 여유)
    const val PLAUSIBLE_LAG_MAX_NS = 1_000_000_000L // 1s

    fun resolve(samples: List<ClockProbeSample>): CaptureClockVerdict {
        if (samples.isEmpty()) {
            return CaptureClockVerdict(
                base = UNKNOWN,
                reason = "표본이 없다 — 프레임이 한 장도 도착하지 않았거나 t_capture_ns가 비었다",
                offsetBoottimeMinusMonotonicNs = 0L,
                samples = samples,
            )
        }
        val offset = median(samples.map { it.boottimeNs - it.monotonicNs })
        val lagMonotonic = median(samples.map { it.monotonicNs - it.tCaptureNs })
        val lagBoottime = median(samples.map { it.boottimeNs - it.tCaptureNs })

        if (kotlin.math.abs(offset) < AMBIGUOUS_OFFSET_NS) {
            return CaptureClockVerdict(
                base = UNKNOWN,
                reason = "두 시계 오프셋이 ${offset}ns(<${AMBIGUOUS_OFFSET_NS}ns)라 판별 불가 — " +
                    "딥슬립이 없어 MONOTONIC과 BOOTTIME이 사실상 같다. " +
                    "어느 기준이든 capture_to_render_ms 값 자체는 유효하다 " +
                    "(취득~렌더 지연 중위값 ${lagBoottime}ns)",
                offsetBoottimeMinusMonotonicNs = offset,
                samples = samples,
            )
        }

        val monotonicPlausible = isPlausible(lagMonotonic)
        val boottimePlausible = isPlausible(lagBoottime)
        val base = when {
            monotonicPlausible && !boottimePlausible -> MONOTONIC
            boottimePlausible && !monotonicPlausible -> BOOTTIME
            else -> UNKNOWN
        }
        val reason = "취득~콜백 지연 중위값: monotonic 기준 ${lagMonotonic}ns / " +
            "boottime 기준 ${lagBoottime}ns, 허용범위 " +
            "[$PLAUSIBLE_LAG_MIN_NS, $PLAUSIBLE_LAG_MAX_NS]ns, 두 시계 오프셋 ${offset}ns" +
            if (base == UNKNOWN) " — 어느 쪽도 단독으로 말이 되지 않아 판별 불가" else ""
        return CaptureClockVerdict(base, reason, offset, samples)
    }

    private fun isPlausible(lagNs: Long): Boolean =
        lagNs in PLAUSIBLE_LAG_MIN_NS..PLAUSIBLE_LAG_MAX_NS

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }
}
