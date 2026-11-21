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
     * Latitude offset by a north/south distance in metres (positive = north).
     * Used to place a geofence's label on its circle edge instead of its centre,
     * so stacked fences sharing a centre don't hide each other's labels.
     */
    fun offsetLatitude(lat: Double, metresNorth: Double): Double =
        lat + Math.toDegrees(metresNorth / EARTH_RADIUS_M)

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

    /**
     * Whether a stored observation at [previousTimestampMs] may seed the inside/outside
     * state for the observation at [currentTimestampMs]. The seed must be strictly older -
     * a replayed or out-of-order beat describes an earlier moment and cannot say where the
     * person came from - and no more than [maxGapMs] older. Past that gap the alerting
     * pipeline was not running (a force-stopped app, a peer who went dark, a week of
     * backfill), so seeding from it would invent a transition nobody observed rather than
     * merely miss one.
     */
    fun canSeedFromPrevious(
        currentTimestampMs: Long,
        previousTimestampMs: Long,
        maxGapMs: Long
    ): Boolean =
        previousTimestampMs < currentTimestampMs &&
            currentTimestampMs - previousTimestampMs <= maxGapMs

    /**
     * Walking-speed allowance per second of peer-fix age. A peer who reported stationary
     * when that fix was taken may have started moving since, and a stationary sender only
     * checks in every few minutes, so waiting is itself evidence the position may have
     * changed.
     */
    private const val PEER_DRIFT_MPS = 1.5

    /**
     * Distance uncertainty for a proximity judgement: both accuracy circles, plus how far
     * the peer could have travelled since their fix. [peerSpeedMps] covers a mover whose
     * beats were delayed; the walking drift covers the gap between "was stationary at the
     * last report" and "is moving now", which no reported value can reveal.
     */
    fun proximityUncertaintyM(
        ownAccuracyM: Double,
        peerAccuracyM: Double,
        peerSpeedMps: Double,
        peerFixAgeMs: Long
    ): Double {
        val ageSeconds = peerFixAgeMs.coerceAtLeast(0L) / 1000.0
        return ownAccuracyM + peerAccuracyM +
            (PEER_DRIFT_MPS + peerSpeedMps.coerceAtLeast(0.0)) * ageSeconds
    }

    // ── Polygon geofences ────────────────────────────────────────────────────
    // Rings are lists of (lat, lng) pairs. The first point is not repeated at the
    // end; every helper here closes the ring implicitly.

    private const val METRES_PER_DEGREE_LAT = 111_320.0

    /**
     * Ray-casting membership test for [ring]. Uses the (lat, lng) plane, which is
     * exact enough for a street-scale fence; a ring crossing the antimeridian would
     * need unwrapping first, which the editor prevents by drawing in view.
     */
    fun pointInRing(lat: Double, lng: Double, ring: List<Pair<Double, Double>>): Boolean {
        if (ring.size < 3) return false
        var inside = false
        var j = ring.lastIndex
        for (i in ring.indices) {
            val (latI, lngI) = ring[i]
            val (latJ, lngJ) = ring[j]
            if ((lngI > lng) != (lngJ > lng) &&
                lat < (latJ - latI) * (lng - lngI) / (lngJ - lngI) + latI
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    /**
     * Shortest distance in metres from ([lat], [lng]) to the ring's edges (0 when the
     * point lies on an edge). Projected to metres around the query point, so it stays
     * accurate at fence scale without a full geodesic segment solver.
     */
    fun distanceToRingMetres(lat: Double, lng: Double, ring: List<Pair<Double, Double>>): Double {
        if (ring.isEmpty()) return Double.POSITIVE_INFINITY
        if (ring.size == 1) return haversineMetres(lat, lng, ring[0].first, ring[0].second)
        val metresPerDegLng = METRES_PER_DEGREE_LAT * cos(Math.toRadians(lat))
        fun x(p: Pair<Double, Double>) = (p.second - lng) * metresPerDegLng
        fun y(p: Pair<Double, Double>) = (p.first - lat) * METRES_PER_DEGREE_LAT
        var best = Double.POSITIVE_INFINITY
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            // x()/y() are already relative to the query point, which is the origin.
            best = minOf(best, pointToSegmentMetres(0.0, 0.0, x(a), y(a), x(b), y(b)))
        }
        return best
    }

    private fun pointToSegmentMetres(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0.0) return sqrt((px - ax) * (px - ax) + (py - ay) * (py - ay))
        val t = (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        return sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
    }

    /**
     * Hysteresis for a polygon: as long as the point is inside, or was inside and is
     * still within [bufferMetres] of an edge, it counts as inside. Leaving beyond the
     * buffer re-arms the fence exactly like the circle version.
     */
    fun isInsidePolygonWithHysteresis(
        inPolygon: Boolean,
        distanceToEdgeMetres: Double,
        bufferMetres: Double,
        wasInside: Boolean
    ): Boolean = inPolygon || (wasInside && distanceToEdgeMetres <= bufferMetres)

    /** Area centroid of the ring as the mean of its vertices; good enough for a label point. */
    fun ringCentroid(ring: List<Pair<Double, Double>>): Pair<Double, Double> {
        require(ring.isNotEmpty()) { "A ring needs at least one point" }
        return ring.map { it.first }.average() to ring.map { it.second }.average()
    }

    /** Greatest distance from [ring]'s centroid to any vertex: the circle that bounds it. */
    fun ringBoundingRadiusMetres(ring: List<Pair<Double, Double>>): Double {
        if (ring.isEmpty()) return 0.0
        val (lat, lng) = ringCentroid(ring)
        return ring.maxOf { haversineMetres(lat, lng, it.first, it.second) }
    }

    /**
     * Whether [ring] is a simple polygon the engine can evaluate: at least three
     * distinct points, all coordinates finite and in range, and no two non-adjacent
     * edges crossing (a bow-tie has no inside).
     */
    fun isRingSimple(ring: List<Pair<Double, Double>>): Boolean {
        if (ring.size < 3) return false
        if (ring.any { !it.first.isFinite() || !it.second.isFinite() }) return false
        if (ring.any { it.first !in -90.0..90.0 || it.second !in -180.0..180.0 }) return false
        val n = ring.size
        for (i in ring.indices) {
            if (ring[i] == ring[(i + 1) % n]) return false
        }
        for (i in 0 until n) {
            val a1 = ring[i]
            val a2 = ring[(i + 1) % n]
            for (j in i + 1 until n) {
                // Adjacent edges share an endpoint by construction; only distinct
                // edges can cross.
                if (j == i + 1 || (i == 0 && j == n - 1)) continue
                val b1 = ring[j]
                val b2 = ring[(j + 1) % n]
                if (segmentsCross(a1, a2, b1, b2)) return false
            }
        }
        return true
    }

    private fun segmentsCross(
        a1: Pair<Double, Double>, a2: Pair<Double, Double>,
        b1: Pair<Double, Double>, b2: Pair<Double, Double>
    ): Boolean {
        fun cross(o: Pair<Double, Double>, p: Pair<Double, Double>, q: Pair<Double, Double>): Double =
            (p.second - o.second) * (q.first - o.first) - (p.first - o.first) * (q.second - o.second)

        fun onSegment(p: Pair<Double, Double>, q: Pair<Double, Double>, r: Pair<Double, Double>): Boolean =
            r.first in minOf(p.first, q.first)..maxOf(p.first, q.first) &&
                r.second in minOf(p.second, q.second)..maxOf(p.second, q.second)

        val d1 = cross(b1, b2, a1)
        val d2 = cross(b1, b2, a2)
        val d3 = cross(a1, a2, b1)
        val d4 = cross(a1, a2, b2)
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) &&
            ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
        ) return true
        // Collinear/touching cases count as crossing: a pinched ring is not a usable area.
        if (d1 == 0.0 && onSegment(b1, b2, a1)) return true
        if (d2 == 0.0 && onSegment(b1, b2, a2)) return true
        if (d3 == 0.0 && onSegment(a1, a2, b1)) return true
        if (d4 == 0.0 && onSegment(a1, a2, b2)) return true
        return false
    }
}
