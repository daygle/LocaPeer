package com.locapeer.util

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure geodesic math shared by the geofence and proximity engines. */
object GeoMath {

    private const val EARTH_RADIUS_M = 6_371_000.0

    fun haversineMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).let { it * it }
        return EARTH_RADIUS_M * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /**
     * Hysteresis membership test for a circular zone: once inside, a point only
     * counts as having left when it clears the radius plus the buffer. This stops
     * fix jitter at the boundary from flapping between inside and outside.
     */
    fun isInsideWithHysteresis(
        distanceM: Double,
        radiusM: Double,
        bufferM: Double,
        wasInside: Boolean
    ): Boolean = if (wasInside) distanceM <= radiusM + bufferM else distanceM <= radiusM
}
