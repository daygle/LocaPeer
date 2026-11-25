package com.locapeer.subscriber

import com.locapeer.data.entity.HeartbeatEntity
import com.locapeer.data.entity.LocationRejectAggregateEntity

/**
 * Retention policy for readings that were stored but not accepted, and the downsampling that
 * replaces their full detail with hourly aggregates.
 *
 * Accepted readings keep whatever retention policy the user configured. Rejected readings
 * have a fixed, privacy-oriented policy of their own because they exist purely to explain
 * gaps: seven days of full detail (coordinates, accuracy, provider, reason), then one row
 * per hour with the rejected count and median accuracy for thirty days, then nothing.
 *
 * Pure Kotlin so the windows and median are unit-testable without Room.
 */
object RejectedReadingRetention {

    /** Full detail is kept this long after the attempt was recorded. */
    const val FULL_DETAIL_DAYS = 7L

    /** Hourly aggregates are kept this long in total (i.e. 23 days after downsampling starts). */
    const val AGGREGATE_DAYS = 30L

    private const val DAY_MS = 86_400_000L
    private const val HOUR_MS = 3_600_000L

    /** Age cutoff for rows that should be downsampled or deleted from the detail table. */
    fun fullDetailCutoff(nowMs: Long): Long = nowMs - FULL_DETAIL_DAYS * DAY_MS

    /** Age cutoff for hourly aggregate rows, which expire after the aggregate window. */
    fun aggregateCutoff(nowMs: Long): Long = nowMs - AGGREGATE_DAYS * DAY_MS

    /** Start of the UTC hour containing [epochMs], the bucket key for aggregates. */
    fun hourStart(epochMs: Long): Long = Math.floorDiv(epochMs, HOUR_MS) * HOUR_MS

    /**
     * Collapses [readings] into one aggregate per (device, UTC hour): the number of rejected
     * attempts and the median accuracy radius among them. An hour with no rejected readings
     * produces no row, so a quiet hour stays distinguishable from a missing one only by the
     * absence of a row in that hour - the detail table already carries the attempts while
     * they are recent.
     */
    fun hourlyAggregates(readings: List<HeartbeatEntity>): List<LocationRejectAggregateEntity> =
        readings
            .filter { !it.isAccepted }
            .groupBy { it.deviceId to hourStart(it.timestamp) }
            .map { (key, bucket) ->
                LocationRejectAggregateEntity(
                    deviceId = key.first,
                    hourStartUtcMs = key.second,
                    rejectedCount = bucket.size,
                    medianAccuracyMeters = median(bucket.map { it.accuracy }),
                )
            }
            .sortedWith(compareBy({ it.hourStartUtcMs }, { it.deviceId }))

    /** Median of [values], averaging the two middle values for an even count. 0 for empty. */
    fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2f
        }
    }
}
