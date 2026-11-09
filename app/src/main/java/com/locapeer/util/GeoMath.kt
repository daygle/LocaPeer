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
}
