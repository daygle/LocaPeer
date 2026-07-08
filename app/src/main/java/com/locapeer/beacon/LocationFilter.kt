package com.locapeer.beacon

import com.locapeer.util.GeoMath
import kotlin.math.max

/**
 * Rejects implausible location fixes before they reach history and peers, so a
 * one-off coarse cell fix or GPS glitch doesn't paint a point kilometres from
 * where the device actually is. Pure Kotlin (no Android types) so it can be
 * unit-tested on the JVM; HeartbeatService owns one instance per service life.
 *
 * Two independent rejection rules, each with an escape hatch so the filter can
 * never wedge the position permanently:
 *
 * 1. Accuracy gate - a fix far less accurate than the one we already have adds
 *    no information about a device that hasn't provably moved; holding the
 *    better position beats wobbling to the worse one. The gate is relative
 *    (see [ACCURACY_GATE_FACTOR]) so that in genuinely coarse-only coverage,
 *    where every fix is cell-grade, fixes still flow.
 *
 * 2. Jump rejection - a fix implying faster-than-plausible travel from the
 *    last accepted position is a glitch... unless the *next* fix agrees with
 *    it, in which case the device really moved (or the anchor was the outlier)
 *    and that corroborating fix is accepted. The first rejected point stays
 *    dropped; only the position from the corroborating fix onward is reported.
 *
 * Escape hatch for both: once nothing has been accepted for [STALE_ACCEPT_MS],
 * the next fix is taken at face value. A wrong recent point is worth
 * suppressing; a wrong *stale* anchor must never censor reality forever
 * (device flown across the country, long GPS outage, etc.).
 */
class LocationFilter {

    companion object {
        /**
         * Fastest travel treated as plausible between fixes (~360 km/h covers
         * high-speed rail). Implied speed is computed with both fixes'
         * accuracy subtracted, so honest uncertainty doesn't read as motion.
         */
        const val MAX_PLAUSIBLE_SPEED_MPS = 100f

        /** A fix this much less accurate than the last accepted one is gated. */
        const val ACCURACY_GATE_FACTOR = 4f

        /**
         * Gate floor: fixes at least this accurate always pass the accuracy
         * gate, however good the previous fix was - a 60m fix after a 5m GPS
         * fix is normal indoors, not an outlier.
         */
        const val ACCURACY_GATE_FLOOR_M = 100f

        /** With no acceptance for this long, take the next fix unconditionally. */
        const val STALE_ACCEPT_MS = 5 * 60_000L
    }

    private var hasAccepted = false
    private var acceptedLat = 0.0
    private var acceptedLng = 0.0
    private var acceptedAccM = 0f
    private var acceptedElapsedNs = 0L

    // Most recent jump-rejected fix, kept so a second fix that agrees with it
    // can confirm a genuine relocation.
    private var hasRejectedJump = false
    private var rejectedLat = 0.0
    private var rejectedLng = 0.0
    private var rejectedAccM = 0f
    private var rejectedElapsedNs = 0L

    /**
     * Decide whether a fix should become the device's reported position.
     * Accepted fixes update the internal baseline; rejected ones leave it
     * untouched (except for jump-candidate bookkeeping).
     */
    fun accept(lat: Double, lng: Double, accuracyM: Float, elapsedRealtimeNs: Long): Boolean {
        if (!hasAccepted) {
            recordAccepted(lat, lng, accuracyM, elapsedRealtimeNs)
            return true
        }

        // Staleness and ordering are judged in integer nanoseconds; float
        // conversion happens only once a speed actually needs computing.
        val dtNs = elapsedRealtimeNs - acceptedElapsedNs
        if (dtNs >= STALE_ACCEPT_MS * 1_000_000L) {
            recordAccepted(lat, lng, accuracyM, elapsedRealtimeNs)
            return true
        }
        // Out-of-order or duplicate timestamp: no time base for a speed check.
        if (dtNs <= 0L) return false
        val dtSec = dtNs / 1_000_000_000f

        if (accuracyM > max(ACCURACY_GATE_FLOOR_M, ACCURACY_GATE_FACTOR * acceptedAccM)) {
            // Not a jump candidate: a coarse fix "far away" is expected noise,
            // not evidence of relocation.
            return false
        }

        if (impliedSpeedMps(acceptedLat, acceptedLng, acceptedAccM, lat, lng, accuracyM, dtSec)
            <= MAX_PLAUSIBLE_SPEED_MPS
        ) {
            recordAccepted(lat, lng, accuracyM, elapsedRealtimeNs)
            return true
        }

        // Implausible jump. Does it corroborate the previous rejected jump?
        if (hasRejectedJump) {
            val dtRejNs = elapsedRealtimeNs - rejectedElapsedNs
            if (dtRejNs > 0L &&
                impliedSpeedMps(
                    rejectedLat, rejectedLng, rejectedAccM, lat, lng, accuracyM,
                    dtRejNs / 1_000_000_000f
                ) <= MAX_PLAUSIBLE_SPEED_MPS
            ) {
                recordAccepted(lat, lng, accuracyM, elapsedRealtimeNs)
                return true
            }
        }
        hasRejectedJump = true
        rejectedLat = lat
        rejectedLng = lng
        rejectedAccM = accuracyM
        rejectedElapsedNs = elapsedRealtimeNs
        return false
    }

    private fun recordAccepted(lat: Double, lng: Double, accuracyM: Float, elapsedNs: Long) {
        hasAccepted = true
        acceptedLat = lat
        acceptedLng = lng
        acceptedAccM = accuracyM
        acceptedElapsedNs = elapsedNs
        hasRejectedJump = false
    }

    private fun impliedSpeedMps(
        lat1: Double, lng1: Double, acc1M: Float,
        lat2: Double, lng2: Double, acc2M: Float,
        dtSec: Float
    ): Float {
        val distM = GeoMath.haversineMetres(lat1, lng1, lat2, lng2).toFloat()
        return (distM - acc1M - acc2M).coerceAtLeast(0f) / dtSec
    }
}
