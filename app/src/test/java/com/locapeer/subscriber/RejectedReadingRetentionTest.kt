package com.locapeer.subscriber

import com.locapeer.data.entity.HeartbeatEntity
import com.locapeer.data.entity.ReadingStatus
import com.locapeer.data.entity.RejectReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RejectedReadingRetentionTest {

    private val hour = 3_600_000L
    private val day = 86_400_000L

    private fun reading(
        deviceId: String = "device-a",
        timestamp: Long,
        accuracy: Float,
        accepted: Boolean = false,
    ) = HeartbeatEntity(
        deviceId = deviceId,
        displayName = "Test",
        timestamp = timestamp,
        lat = -37.8,
        lng = 144.9,
        accuracy = accuracy,
        battery = 80,
        motionState = "WALKING",
        expectedIntervalSeconds = 120,
        status = if (accepted) ReadingStatus.ACCEPTED.dbValue else ReadingStatus.REJECTED.dbValue,
        rejectReason = if (accepted) null else RejectReason.TOO_IMPRECISE.code,
    )

    @Test
    fun `rejected detail is kept for seven days and aggregates for thirty`() {
        val now = 1_000L * day
        assertEquals(now - 7 * day, RejectedReadingRetention.fullDetailCutoff(now))
        assertEquals(now - 30 * day, RejectedReadingRetention.aggregateCutoff(now))
    }

    @Test
    fun `detail cutoff lands on an hour boundary so buckets are never split`() {
        // "now" is 37 minutes past an hour: the cutoff must drop back to that hour's start,
        // or a daily run would aggregate a partial bucket and later overwrite it.
        val now = 1_000L * day + 5 * hour + 37 * 60_000L
        assertEquals(1_000L * day + 5 * hour - 7 * day, RejectedReadingRetention.fullDetailCutoff(now))
        assertEquals(
            RejectedReadingRetention.fullDetailCutoff(now),
            RejectedReadingRetention.hourStart(RejectedReadingRetention.fullDetailCutoff(now))
        )
    }

    @Test
    fun `hour bucket is the start of the UTC hour`() {
        assertEquals(0L, RejectedReadingRetention.hourStart(0L))
        assertEquals(0L, RejectedReadingRetention.hourStart(hour - 1))
        assertEquals(hour, RejectedReadingRetention.hourStart(hour))
        assertEquals(hour, RejectedReadingRetention.hourStart(hour + 37_000L))
    }

    @Test
    fun `median handles odd even and empty inputs`() {
        assertEquals(0f, RejectedReadingRetention.median(emptyList()))
        assertEquals(7f, RejectedReadingRetention.median(listOf(7f)))
        assertEquals(3f, RejectedReadingRetention.median(listOf(5f, 1f, 3f)))
        assertEquals(2.5f, RejectedReadingRetention.median(listOf(4f, 1f, 3f, 2f)))
    }

    @Test
    fun `downsampling keeps one row per device and hour with a median accuracy`() {
        val readings = listOf(
            reading(timestamp = 10_000L, accuracy = 40f),
            reading(timestamp = 20_000L, accuracy = 10f),
            reading(timestamp = 30_000L, accuracy = 90f),
            // A later hour for the same device is its own bucket.
            reading(timestamp = hour + 5_000L, accuracy = 200f),
            // A second device in the first hour is not merged with the first.
            reading(deviceId = "device-b", timestamp = 15_000L, accuracy = 60f),
            // Accepted readings never enter the rejected aggregates.
            reading(timestamp = 25_000L, accuracy = 500f, accepted = true),
        )

        val aggregates = RejectedReadingRetention.hourlyAggregates(readings)

        assertEquals(3, aggregates.size)
        val first = aggregates.first { it.deviceId == "device-a" && it.hourStartUtcMs == 0L }
        assertEquals(3, first.rejectedCount)
        assertEquals(40f, first.medianAccuracyMeters)
        val later = aggregates.first { it.deviceId == "device-a" && it.hourStartUtcMs == hour }
        assertEquals(1, later.rejectedCount)
        assertEquals(200f, later.medianAccuracyMeters)
        val otherDevice = aggregates.first { it.deviceId == "device-b" }
        assertEquals(0L, otherDevice.hourStartUtcMs)
        assertEquals(1, otherDevice.rejectedCount)
        assertEquals(60f, otherDevice.medianAccuracyMeters)
    }

    @Test
    fun `accepted readings produce no aggregates`() {
        val aggregates = RejectedReadingRetention.hourlyAggregates(
            listOf(reading(timestamp = 1_000L, accuracy = 10f, accepted = true))
        )
        assertTrue(aggregates.isEmpty())
    }
}
