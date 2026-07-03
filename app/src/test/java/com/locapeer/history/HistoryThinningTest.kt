package com.locapeer.history

import com.locapeer.data.entity.HeartbeatEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class HistoryThinningTest {

    // ~0.001° latitude ≈ 111m; tests sit on the equator so the conversion is uniform.
    private fun point(id: Long, northMetres: Double, isSos: Boolean = false) = HeartbeatEntity(
        id = id,
        deviceId = "dev",
        displayName = "Test",
        timestamp = id,
        lat = northMetres / 111_000.0,
        lng = 0.0,
        accuracy = 10f,
        battery = 100,
        motionState = "STATIONARY",
        isSos = isSos
    )

    private fun ids(points: List<HeartbeatEntity>) = points.map { it.id }

    @Test
    fun `zero threshold shows every point`() {
        val points = listOf(point(1, 0.0), point(2, 5.0), point(3, 10.0))
        assertSame(points, HistoryThinning.thin(points, 0))
    }

    @Test
    fun `dwell cloud collapses to arrival point`() {
        // Parked: wobble of a few metres around the same spot, then the newest ping.
        val points = listOf(
            point(1, 0.0), point(2, 8.0), point(3, 3.0), point(4, 12.0), point(5, 6.0)
        )
        // Arrival point kept, wobble hidden, newest always shown.
        assertEquals(listOf(1L, 5L), ids(HistoryThinning.thin(points, 50)))
    }

    @Test
    fun `distance is measured from the last shown point, not the previous ping`() {
        // Creeping 60m per ping with a 100m threshold: every second ping clears it.
        val points = listOf(
            point(1, 0.0), point(2, 60.0), point(3, 120.0), point(4, 180.0), point(5, 240.0)
        )
        assertEquals(listOf(1L, 3L, 5L), ids(HistoryThinning.thin(points, 100)))
    }

    @Test
    fun `sos pings are never hidden`() {
        val points = listOf(
            point(1, 0.0), point(2, 5.0, isSos = true), point(3, 10.0), point(4, 300.0)
        )
        assertEquals(listOf(1L, 2L, 4L), ids(HistoryThinning.thin(points, 100)))
    }

    @Test
    fun `newest ping is always shown even inside the threshold`() {
        val points = listOf(point(1, 0.0), point(2, 200.0), point(3, 210.0))
        assertEquals(listOf(1L, 2L, 3L), ids(HistoryThinning.thin(points, 100)))
    }

    @Test
    fun `single point and empty lists pass through`() {
        assertEquals(0, HistoryThinning.thin(emptyList(), 100).size)
        val single = listOf(point(1, 0.0))
        assertSame(single, HistoryThinning.thin(single, 100))
    }

    @Test
    fun `moving trail is untouched when spacing already exceeds the threshold`() {
        val points = listOf(point(1, 0.0), point(2, 150.0), point(3, 300.0), point(4, 450.0))
        assertEquals(listOf(1L, 2L, 3L, 4L), ids(HistoryThinning.thin(points, 100)))
    }
}
