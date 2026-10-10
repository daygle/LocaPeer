package com.locapeer.map

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/** Pure map maths shared by the map screens (no Android or MapLibre types, so it is unit-tested). */
object MapGeometry {
    private const val EARTH_RADIUS_M = 6_371_008.8
    private const val EARTH_CIRCUMFERENCE_M = 40_075_016.686

    /**
     * Zoom levels saved by earlier versions (map start zoom setting, last map position) are in
     * osmdroid's scale, which used 256 px tiles. MapLibre uses 512 px tiles, so the same view is
     * one level lower. Stored values stay in the old scale; convert at the map boundary.
     */
    const val LEGACY_ZOOM_OFFSET = 1.0

    fun fromLegacyZoom(zoom: Double): Double = zoom - LEGACY_ZOOM_OFFSET
    fun toLegacyZoom(zoom: Double): Double = zoom + LEGACY_ZOOM_OFFSET

    /**
     * Points on a circle of [radiusMetres] around ([lat], [lng]) as (lat, lng) pairs, closed (the
     * last point equals the first), for drawing geofences as polygons.
     */
    fun circle(lat: Double, lng: Double, radiusMetres: Double, segments: Int = 64): List<Pair<Double, Double>> {
        val angular = radiusMetres / EARTH_RADIUS_M
        val lat1 = Math.toRadians(lat)
        val lng1 = Math.toRadians(lng)
        val points = (0 until segments).map { i ->
            val bearing = 2 * PI * i / segments
            val lat2 = asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(bearing))
            val lng2 = lng1 + atan2(sin(bearing) * sin(angular) * cos(lat1), cos(angular) - sin(lat1) * sin(lat2))
            Math.toDegrees(lat2) to normaliseLng(Math.toDegrees(lng2))
        }
        return points + points.first()
    }

    private fun normaliseLng(lng: Double): Double = ((lng + 540.0) % 360.0) - 180.0

    /** Ground distance covered by one dp of screen at [lat] and MapLibre [zoom] (512-dp world at z0). */
    fun metresPerDp(lat: Double, zoom: Double): Double =
        EARTH_CIRCUMFERENCE_M * cos(Math.toRadians(lat)) / (512.0 * 2.0.pow(zoom))

    /** The largest "round" distance (1, 2 or 5 × 10ⁿ metres) not longer than [maxMetres]. */
    fun niceScaleMetres(maxMetres: Double): Double {
        if (maxMetres <= 0.0 || !maxMetres.isFinite()) return 0.0
        val magnitude = 10.0.pow(floor(log10(maxMetres)))
        val leading = maxMetres / magnitude
        val nice = when {
            leading >= 5 -> 5.0
            leading >= 2 -> 2.0
            else -> 1.0
        }
        return nice * magnitude
    }

    /**
     * Scale bar for at most [maxMetres] of screen: the bar's length in metres and its label, in
     * metres/kilometres or, when [imperial], feet/miles (switching unit at 1 km / 1 mi).
     */
    fun scaleBar(maxMetres: Double, imperial: Boolean): Pair<Double, String> {
        if (imperial) {
            val maxFeet = maxMetres * FEET_PER_METRE
            return if (maxFeet < FEET_PER_MILE) {
                val feet = niceScaleMetres(maxFeet)
                feet / FEET_PER_METRE to "${feet.toLong()} ft"
            } else {
                val miles = niceScaleMetres(maxFeet / FEET_PER_MILE)
                miles * FEET_PER_MILE / FEET_PER_METRE to "${trim(miles)} mi"
            }
        }
        return if (maxMetres < 1000) {
            val metres = niceScaleMetres(maxMetres)
            metres to "${metres.toLong()} m"
        } else {
            val km = niceScaleMetres(maxMetres / 1000)
            km * 1000 to "${trim(km)} km"
        }
    }

    private const val FEET_PER_METRE = 3.28084
    private const val FEET_PER_MILE = 5280.0

    private fun trim(value: Double): String =
        if (value == floor(value)) value.toLong().toString() else value.toString()
}
