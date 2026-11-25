package com.locapeer.history

import com.locapeer.data.entity.HeartbeatEntity
import com.locapeer.data.entity.ReadingStatus
import com.locapeer.data.entity.RejectReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class HistoryThinningTest {

    // ~0.001° latitude ≈ 111m; tests sit on the equator so the conversion is uniform.
    private fun point(
        id: Long,
        northMetres: Double,
        isSos: Boolean = false,
        accuracy: Float = 10f,
        accepted: Boolean = true,
    ) = HeartbeatEntity(
        id = id,
        deviceId = "dev",
        displayName = "Test",
        timestamp = id,
        lat = northMetres / 111_000.0,
        lng = 0.0,
        accuracy = accuracy,
        battery = 100,
        motionState = "STATIONARY",
        isSos = isSos,
        expectedIntervalSeconds = 900,
        status = if (accepted) ReadingStatus.ACCEPTED.dbValue else ReadingStatus.REJECTED.dbValue,
        rejectReason = if (accepted) null else RejectReason.TOO_IMPRECISE.code
    )

    private fun ids(points: List<HeartbeatEntity>) = points.map { it.id }

    @Test
    fun `zero threshold shows every point`() {
        val points = listOf(point(1, 0.0), point(2, 5.0), point(3, 10.0))
        assertSame(points, HistoryThinning.process(points, 0, 0))
    }

    @Test
    fun `dwell cloud collapses to arrival point`() {
        // Parked: wobble of a few metres around the same spot, then the newest ping.
        val points = listOf(
            point(1, 0.0), point(2, 8.0), point(3, 3.0), point(4, 12.0), point(5, 6.0)
        )
        // Arrival point kept, wobble hidden, newest always shown.
        assertEquals(listOf(1L, 5L), ids(HistoryThinning.process(points, 0, 50)))
    }

    @Test
    fun `distance is measured from the last shown point, not the previous ping`() {
        // Creeping 60m per ping with a 100m threshold: every second ping clears it.
        val points = listOf(
            point(1, 0.0), point(2, 60.0), point(3, 120.0), point(4, 180.0), point(5, 240.0)
        )
        assertEquals(listOf(1L, 3L, 5L), ids(HistoryThinning.process(points, 0, 100)))
    }

    @Test
    fun `sos pings are never hidden`() {
        val points = listOf(
            point(1, 0.0), point(2, 5.0, isSos = true), point(3, 10.0), point(4, 300.0)
        )
        assertEquals(listOf(1L, 2L, 4L), ids(HistoryThinning.process(points, 0, 100)))
    }

    @Test
    fun `newest ping is always shown even inside the threshold`() {
        val points = listOf(point(1, 0.0), point(2, 200.0), point(3, 210.0))
        assertEquals(listOf(1L, 2L, 3L), ids(HistoryThinning.process(points, 0, 100)))
    }

    @Test
    fun `single point and empty lists pass through`() {
        assertEquals(0, HistoryThinning.process(emptyList(), 0, 100).size)
        val single = listOf(point(1, 0.0))
        assertSame(single, HistoryThinning.process(single, 0, 100))
    }

    @Test
    fun `moving trail is untouched when spacing already exceeds the threshold`() {
        val points = listOf(point(1, 0.0), point(2, 150.0), point(3, 300.0), point(4, 450.0))
        assertEquals(listOf(1L, 2L, 3L, 4L), ids(HistoryThinning.process(points, 0, 100)))
    }

    @Test
    fun `zero accuracy threshold keeps every point`() {
        val points = listOf(point(1, 0.0, accuracy = 5f), point(2, 5.0, accuracy = 800f))
        assertSame(points, HistoryThinning.process(points, 0, 0))
    }

    @Test
    fun `accuracy filter drops points coarser than the threshold`() {
        val points = listOf(
            point(1, 0.0, accuracy = 8f),
            point(2, 5.0, accuracy = 250f),
            point(3, 10.0, accuracy = 100f),
            point(4, 15.0, accuracy = 600f)
        )
        // 100m threshold: the 8m and 100m fixes survive (<=), the 250m and 600m are hidden.
        // The last point (4) is always kept regardless of accuracy in this test case because it's last.
        // Wait, if accuracy filter is on, should it drop the last point too?
        // In my current implementation: if (p.isSos || isLast) { kept += p; ... }
        // So the last point IS kept even if coarse.
        assertEquals(listOf(1L, 3L, 4L), ids(HistoryThinning.process(points, 100, 0)))
    }

    @Test
    fun `accuracy filter never hides sos pings`() {
        val points = listOf(
            point(1, 0.0, accuracy = 8f),
            point(2, 5.0, isSos = true, accuracy = 900f),
            point(3, 10.0, accuracy = 700f)
        )
        // 1 is good, 2 is SOS (kept), 3 is last (kept).
        assertEquals(listOf(1L, 2L, 3L), ids(HistoryThinning.process(points, 100, 0)))
    }

    @Test
    fun `rejected readings are kept but never anchor the accepted trail`() {
        val points = listOf(
            point(1, 0.0),                               // accepted anchor
            point(2, 80.0, accuracy = 900f, accepted = false), // rejected, would anchor wrongly
            point(3, 120.0),                             // accepted, 120m from the real anchor
            point(4, 400.0)                              // latest accepted
        )

        // If the rejected point became the distance anchor, point 3 (40m from it) would be
        // thinned away. It must survive, and the rejected point must still be listed.
        assertEquals(listOf(1L, 2L, 3L, 4L), ids(HistoryThinning.process(points, 0, 100)))
    }

    @Test
    fun `accuracy filter also hides coarse rejected readings`() {
        val points = listOf(
            point(1, 0.0),
            point(2, 50.0, accuracy = 900f, accepted = false),
            point(3, 60.0, accuracy = 40f, accepted = false),
            point(4, 400.0)
        )
        // "Hide low-accuracy points" promises to hide every point coarser than the threshold,
        // rejected or not; a sharp-enough rejected reading is still listed.
        assertEquals(listOf(1L, 3L, 4L), ids(HistoryThinning.process(points, 100, 0)))
    }

    @Test
    fun `a lone coarse rejected reading is hidden by the accuracy filter`() {
        val points = listOf(point(1, 50.0, accuracy = 900f, accepted = false))
        assertEquals(emptyList<Long>(), ids(HistoryThinning.process(points, 100, 0)))
    }

    @Test
    fun `accuracy filter keeps rejected attempts that have no position`() {
        val noFix = point(2, 0.0, accuracy = 900f, accepted = false)
            .copy(rejectReason = RejectReason.NO_FIX.code)
        val points = listOf(point(1, 0.0), noFix, point(3, 400.0))
        // A no-fix row's accuracy is a placeholder, so the threshold says nothing about it.
        assertEquals(listOf(1L, 2L, 3L), ids(HistoryThinning.process(points, 100, 0)))
    }

    @Test
    fun `a rejected final reading does not hide the latest accepted point`() {
        val points = listOf(
            point(1, 0.0),
            point(2, 20.0),
            point(3, 25.0, accuracy = 900f, accepted = false)
        )
        // The latest accepted point (2) must be kept even though the newest row is rejected.
        assertEquals(listOf(1L, 2L, 3L), ids(HistoryThinning.process(points, 0, 100)))
    }

    @Test
    fun `combined pass handles both rules correctly`() {
        val points = listOf(
            point(1, 0.0, accuracy = 10f),    // Kept (start)
            point(2, 5.0, accuracy = 500f),   // Dropped (coarse)
            point(3, 150.0, accuracy = 10f),  // Kept (moved > 100m)
            point(4, 160.0, accuracy = 10f),  // Dropped (too close to 3)
            point(5, 400.0, accuracy = 10f)   // Kept (last)
        )
        assertEquals(listOf(1L, 3L, 5L), ids(HistoryThinning.process(points, 100, 100)))
    }
}
