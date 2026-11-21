package com.locapeer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeofencePolygonTest {

    /** ~1.1 km square around the origin, (lat, lng) pairs, first point not repeated. */
    private fun square(side: Double = 0.01) = listOf(
        0.0 to 0.0,
        0.0 to side,
        side to side,
        side to 0.0
    )

    @Test
    fun `point inside the square is inside`() {
        assertTrue(GeoMath.pointInRing(0.005, 0.005, square()))
    }

    @Test
    fun `point outside the square is outside`() {
        assertFalse(GeoMath.pointInRing(0.02, 0.02, square()))
        assertFalse(GeoMath.pointInRing(-0.001, 0.005, square()))
    }

    @Test
    fun `distance to the nearest edge is measured in metres`() {
        // The centre of a 0.01° square is ~556 m from every edge.
        assertEquals(556.0, GeoMath.distanceToRingMetres(0.005, 0.005, square()), 5.0)
    }

    @Test
    fun `distance is zero for a point on an edge`() {
        assertEquals(0.0, GeoMath.distanceToRingMetres(0.0, 0.005, square()), 1.0)
    }

    @Test
    fun `polygon hysteresis keeps a former insider within the buffer`() {
        assertTrue(GeoMath.isInsidePolygonWithHysteresis(false, 20.0, 50.0, wasInside = true))
        assertFalse(GeoMath.isInsidePolygonWithHysteresis(false, 60.0, 50.0, wasInside = true))
        assertFalse(GeoMath.isInsidePolygonWithHysteresis(false, 20.0, 50.0, wasInside = false))
        assertTrue(GeoMath.isInsidePolygonWithHysteresis(true, 500.0, 0.0, wasInside = false))
    }

    @Test
    fun `centroid and bounding radius describe the ring`() {
        val (lat, lng) = GeoMath.ringCentroid(square())
        assertEquals(0.005, lat, 1e-9)
        assertEquals(0.005, lng, 1e-9)
        // Half the diagonal of the ~1.1 km square.
        assertEquals(787.0, GeoMath.ringBoundingRadiusMetres(square()), 25.0)
    }

    @Test
    fun `simple square ring is accepted`() {
        assertTrue(GeoMath.isRingSimple(square()))
    }

    @Test
    fun `crossing edges are rejected`() {
        val bowTie = listOf(0.0 to 0.0, 0.01 to 0.01, 0.01 to 0.0, 0.0 to 0.01)
        assertFalse(GeoMath.isRingSimple(bowTie))
    }

    @Test
    fun `a ring needs three distinct points`() {
        assertFalse(GeoMath.isRingSimple(emptyList()))
        assertFalse(GeoMath.isRingSimple(listOf(0.0 to 0.0, 0.01 to 0.0)))
        assertFalse(
            GeoMath.isRingSimple(listOf(0.0 to 0.0, 0.0 to 0.0, 0.01 to 0.0, 0.01 to 0.01))
        )
    }

    @Test
    fun `out of range coordinates are rejected`() {
        val ring = listOf(0.0 to 0.0, 0.0 to 0.01, 91.0 to 0.01)
        assertFalse(GeoMath.isRingSimple(ring))
    }

    @Test
    fun `ring codec round-trips a polygon`() {
        val ring = square()
        assertEquals(ring, GeofencePolygon.decode(GeofencePolygon.encode(ring)))
    }

    @Test
    fun `closed rings are accepted and stored open`() {
        val closed = square() + square().first()
        assertEquals(square(), GeofencePolygon.decode(GeofencePolygon.encode(closed)))
        assertEquals(GeofencePolygon.encode(square()), GeofencePolygon.encode(closed))
        assertEquals(4, GeofencePolygon.decode(GeofencePolygon.encode(closed))!!.size)
    }

    @Test
    fun `ring codec rejects unusable values`() {
        assertNull(GeofencePolygon.decode(null))
        assertNull(GeofencePolygon.decode(""))
        assertNull(GeofencePolygon.decode("0,0;0,1"))
        assertNull(GeofencePolygon.decode("0,0;0,1;nope,2"))
        assertNull(GeofencePolygon.decode("0,0;0,1;95,2"))
        assertNull(GeofencePolygon.decode("0,0;1,1;1,0;0,1"))
        assertNull(GeofencePolygon.decode("0,0;0,1;0.5;1,1"))
    }
}
