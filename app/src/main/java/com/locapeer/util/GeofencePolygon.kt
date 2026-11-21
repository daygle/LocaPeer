package com.locapeer.util

/**
 * Codec for a polygon geofence's ring, stored in the fence row as text.
 *
 * The format is deliberately plain (`lat,lng;lat,lng;...`) so it is independent of the
 * serialization libraries and can be validated on restore: a ring is accepted only if
 * every coordinate is finite and in range, there are at least three points, and the
 * polygon is simple (no crossing edges).
 */
object GeofencePolygon {

    /** Upper bound on vertices: far more than a hand-drawn area needs, small enough to encode safely. */
    const val MAX_POINTS = 200

    /**
     * Encodes [ring], omitting a repeated closing point: stored rings are always open
     * (the last vertex is implicit), which is the form the geometry helpers expect.
     */
    fun encode(ring: List<Pair<Double, Double>>): String {
        val vertices = if (ring.size >= 2 && ring.first() == ring.last()) ring.dropLast(1)
                       else ring
        return vertices.joinToString(";") { (lat, lng) -> "$lat,$lng" }
    }

    /** Decoded ring, or null when [raw] is absent, malformed, or not a usable polygon. */
    fun decode(raw: String?): List<Pair<Double, Double>>? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        if (trimmed.length > MAX_POINTS * 64) return null
        val decoded = ArrayList<Pair<Double, Double>>()
        for (entry in trimmed.split(';')) {
            val parts = entry.split(',')
            if (parts.size != 2) return null
            val lat = parts[0].trim().toDoubleOrNull() ?: return null
            val lng = parts[1].trim().toDoubleOrNull() ?: return null
            if (!lat.isFinite() || !lng.isFinite()) return null
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
            decoded += lat to lng
            if (decoded.size > MAX_POINTS + 1) return null
        }
        // Accept a GeoJSON-style closed ring (first point repeated at the end) but keep
        // the open form internally.
        val points = if (decoded.size >= 2 && decoded.first() == decoded.last()) decoded.dropLast(1)
                     else decoded
        if (points.size < 3 || points.size > MAX_POINTS) return null
        return points.takeIf { GeoMath.isRingSimple(it) }
    }
}
