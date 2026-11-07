package com.locapeer.map

import com.locapeer.data.entity.HeartbeatEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePinDetectorTest {

    private val now = 1_000_000_000L

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
        assertTrue(LivePinDetector.isLive(prev, latest, now))
    }

    @Test
    fun `heartbeats at the normal cadence are not live`() {
        val prev = hb(1, now - 121_000, now - 121_000)
        val latest = hb(2, now - 1_000, now - 1_000)
        assertFalse(LivePinDetector.isLive(prev, latest, now))
    }

    @Test
    fun `a stale stream is no longer live`() {
        val prev = hb(1, now - 30_000, now - 30_000)
        val latest = hb(2, now - 25_000, now - 25_000)
        assertFalse(LivePinDetector.isLive(prev, latest, now))
    }

    @Test
    fun `a catch-up burst of old cadence heartbeats is not live`() {
        val prev = hb(1, now - 240_000, now - 2_000)
        val latest = hb(2, now - 120_000, now - 1_000)
        assertFalse(LivePinDetector.isLive(prev, latest, now))
    }

    @Test
    fun `a single heartbeat with no predecessor is not live`() {
        assertFalse(LivePinDetector.isLive(null, hb(2, now - 1_000, now - 1_000), now))
    }

    @Test
    fun `older senders reporting the live interval still read as live`() {
        assertTrue(LivePinDetector.isLive(null, hb(2, now - 1_000, now - 1_000, intervalSec = 5L), now))
    }
}
