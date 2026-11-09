package com.locapeer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathTest {

    @Test
    fun `zero distance for identical points`() {
        assertEquals(0.0, GeoMath.haversineMetres(-33.8688, 151.2093, -33.8688, 151.2093), 0.001)
    }

    @Test
    fun `known city pair distance is right to within five kilometres`() {
        // Sydney Opera House to Melbourne CBD ≈ 713 km great-circle.
        val d = GeoMath.haversineMetres(-33.8568, 151.2153, -37.8136, 144.9631)
        assertEquals(713_000.0, d, 5_000.0)
    }

    @Test
    fun `distance is symmetric`() {
        val ab = GeoMath.haversineMetres(-33.86, 151.21, -33.87, 151.22)
        val ba = GeoMath.haversineMetres(-33.87, 151.22, -33.86, 151.21)
        assertEquals(ab, ba, 0.000001)
    }

    @Test
    fun `short distances are accurate at street scale`() {
        // ~111 m per 0.001° of latitude.
        val d = GeoMath.haversineMetres(-33.8680, 151.2093, -33.8690, 151.2093)
        assertEquals(111.0, d, 1.0)
    }

    @Test
    fun `offsetLatitude shifts north by the expected degrees`() {
        // ~111 m per 0.001° of latitude, so 111 m north ≈ +0.001°.
        val lat = -33.8688
        assertEquals(lat + 0.001, GeoMath.offsetLatitude(lat, 111.0), 0.00001)
    }

    @Test
    fun `offsetLatitude moves south for negative metres`() {
        val lat = -33.8688
        assertTrue(GeoMath.offsetLatitude(lat, -111.0) < lat)
        assertEquals(0.0, GeoMath.offsetLatitude(lat, 0.0) - lat, 0.0)
    }

    @Test
    fun `offsetLatitude distance round-trips through haversine`() {
        // A point offset 250 m north should measure 250 m away by great-circle.
        val lat = -33.8688
        val lng = 151.2093
        val northLat = GeoMath.offsetLatitude(lat, 250.0)
        assertEquals(250.0, GeoMath.haversineMetres(lat, lng, northLat, lng), 0.5)
    }

    @Test
    fun `hysteresis requires clearing radius plus buffer to leave`() {
        val radius = 500.0
        val buffer = 100.0
        // Outside → inside only under the plain radius.
        assertFalse(GeoMath.isInsideWithHysteresis(550.0, radius, buffer, wasInside = false))
        assertTrue(GeoMath.isInsideWithHysteresis(500.0, radius, buffer, wasInside = false))
        // Inside → stays inside through the dead band, leaves past radius+buffer.
        assertTrue(GeoMath.isInsideWithHysteresis(550.0, radius, buffer, wasInside = true))
        assertTrue(GeoMath.isInsideWithHysteresis(600.0, radius, buffer, wasInside = true))
        assertFalse(GeoMath.isInsideWithHysteresis(601.0, radius, buffer, wasInside = true))
    }

    @Test
    fun `seed needs a strictly older observation inside the gap`() {
        val maxGap = 10 * 60_000L
        val now = 1_000_000_000L
        // Recently preceding fix: usable.
        assertTrue(GeoMath.canSeedFromPrevious(now, now - 60_000L, maxGap))
        // Exactly at the gap: still the same alerting session.
        assertTrue(GeoMath.canSeedFromPrevious(now, now - maxGap, maxGap))
        // Past the gap (app killed, peer dark for hours): seed nothing.
        assertFalse(GeoMath.canSeedFromPrevious(now, now - maxGap - 1, maxGap))
        // Same fix, or an out-of-order newer one, cannot establish a direction.
        assertFalse(GeoMath.canSeedFromPrevious(now, now, maxGap))
        assertFalse(GeoMath.canSeedFromPrevious(now, now + 1, maxGap))
    }

    @Test
    fun `proximity uncertainty grows with peer fix age and reported speed`() {
        // Fresh pulse from a stationary peer: only the two accuracy circles plus a few
        // seconds of walking drift.
        assertEquals(
            30.0 + 1.5 * 5,
            GeoMath.proximityUncertaintyM(10.0, 20.0, 0.0, 5_000L),
            0.001
        )
        // Four-minute-old stationary fix: the peer could have walked away by now.
        assertEquals(
            30.0 + 1.5 * 240,
            GeoMath.proximityUncertaintyM(10.0, 20.0, 0.0, 4 * 60_000L),
            0.001
        )
        // A delayed fix from a mover carries their own speed on top of the drift.
        assertEquals(
            30.0 + (1.5 + 15.0) * 60,
            GeoMath.proximityUncertaintyM(10.0, 20.0, 15.0, 60_000L),
            0.001
        )
        // A negative age (clock skew) never shrinks the accuracy term.
        assertEquals(
            30.0,
            GeoMath.proximityUncertaintyM(10.0, 20.0, 0.0, -60_000L),
            0.001
        )
    }

    @Test
    fun `boundary jitter cannot flap membership`() {
        val radius = 100.0
        val buffer = 50.0
        var inside = false
        // Fixes oscillating across the plain radius but within the buffer band.
        val distances = listOf(95.0, 110.0, 98.0, 120.0, 104.0, 130.0)
        val states = distances.map { d ->
            inside = GeoMath.isInsideWithHysteresis(d, radius, buffer, inside)
            inside
        }
        // Enters at 95 and never leaves - no distance exceeds radius+buffer (150).
        assertEquals(listOf(true, true, true, true, true, true), states)
    }
}
