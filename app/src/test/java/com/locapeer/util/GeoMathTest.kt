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
        // Enters at 95 and never leaves — no distance exceeds radius+buffer (150).
        assertEquals(listOf(true, true, true, true, true, true), states)
    }
}
