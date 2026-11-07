package com.locapeer.map

import com.locapeer.data.entity.HeartbeatEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePinDetectorTest {

    private val now = 1_000_000_000L

    private fun isLive(previous: HeartbeatEntity?, latest: HeartbeatEntity?, nowMs: Long) =
        LivePinDetector.liveUntil(previous, latest)?.let { nowMs < it } ?: false

    private fun hb(id: Long, sentAt: Long, receivedAt: Long, intervalSec: Long = 120L) = HeartbeatEntity(
        id = id,
        deviceId = "peer",
        displayName = "Peer",
        timestamp = sentAt,
        lat = 1.0,
        lng = 2.0,
        accuracy = 5f,
        battery = 80,
        motionState = "DRIVING",
        receivedAt = receivedAt,
        expectedIntervalSeconds = intervalSec
    )

    @Test
    fun `consecutive heartbeats a few seconds apart read as live`() {
        val prev = hb(1, now - 6_000, now - 6_000)
        val latest = hb(2, now - 1_000, now - 1_000)
        assertTrue(isLive(prev, latest, now))
    }

    @Test
    fun `heartbeats at the normal cadence are not live`() {
        val prev = hb(1, now - 121_000, now - 121_000)
        val latest = hb(2, now - 1_000, now - 1_000)
        assertFalse(isLive(prev, latest, now))
    }

    @Test
    fun `a stale stream is no longer live`() {
        val prev = hb(1, now - 30_000, now - 30_000)
        val latest = hb(2, now - 25_000, now - 25_000)
        assertFalse(isLive(prev, latest, now))
    }

    @Test
    fun `a catch-up burst of old cadence heartbeats is not live`() {
        val prev = hb(1, now - 240_000, now - 2_000)
        val latest = hb(2, now - 120_000, now - 1_000)
        assertFalse(isLive(prev, latest, now))
    }

    @Test
    fun `a single heartbeat with no predecessor is not live`() {
        assertFalse(isLive(null, hb(2, now - 1_000, now - 1_000), now))
    }

    @Test
    fun `older senders reporting the live interval still read as live`() {
        assertTrue(isLive(null, hb(2, now - 1_000, now - 1_000, intervalSec = 5L), now))
    }

    @Test
    fun `a 30 second live stream reads as live between updates`() {
        val prev = hb(1, now - 55_000, now - 55_000)
        val latest = hb(2, now - 25_000, now - 25_000)
        assertTrue(isLive(prev, latest, now))
    }

    @Test
    fun `a one minute cadence is not mistaken for live`() {
        val prev = hb(1, now - 61_000, now - 61_000, intervalSec = 60L)
        val latest = hb(2, now - 1_000, now - 1_000, intervalSec = 60L)
        assertFalse(isLive(prev, latest, now))
    }

    @Test
    fun `sos heartbeats are never shown as live`() {
        val prev = hb(1, now - 16_000, now - 16_000).copy(isSos = true)
        val latest = hb(2, now - 1_000, now - 1_000).copy(isSos = true)
        assertFalse(isLive(prev, latest, now))
    }

    @Test
    fun `liveUntil is two arrival gaps after the latest heartbeat, at least 20 seconds`() {
        val prev = hb(1, now - 6_000, now - 6_000)
        val latest = hb(2, now - 1_000, now - 1_000)
        assertEquals(now - 1_000 + 20_000, LivePinDetector.liveUntil(prev, latest))
        val slowPrev = hb(3, now - 31_000, now - 31_000)
        assertEquals(now - 1_000 + 60_000, LivePinDetector.liveUntil(slowPrev, latest))
    }

    @Test
    fun `liveUntil is null for a cadence pair`() {
        assertNull(LivePinDetector.liveUntil(hb(1, now - 121_000, now - 121_000), hb(2, now, now)))
    }
}
