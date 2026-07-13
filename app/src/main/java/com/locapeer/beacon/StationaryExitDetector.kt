package com.locapeer.beacon

import com.locapeer.util.GeoMath

/**
 * Displacement-based escape hatch for the STATIONARY state, with glitch corroboration.
 *
 * The low-power fixes used while stationary can be so coarse that speed-based motion
 * detection reads 0 for an entire drive, so the exit decision compares each fix against
 * the *anchor* - the place where the device became stationary: total displacement grows
 * without bound while driving, so once it clears the combined uncertainty plus a buffer
 * the device has provably moved (see [MotionMath.shouldExitStationary]).
 *
 * A single fix beyond that threshold is NOT trusted on its own: at the stationary poll
 * cadence the [LocationFilter]'s stale-accept window has always elapsed, so a one-off
 * sharp glitch kilometres away sails through the filter and would previously exit the
 * state and paint a far-away pin that peers see and history keeps. Instead the first
 * out-of-threshold fix becomes an exit *candidate* ([Verdict.CANDIDATE]) that the caller
 * withholds entirely - from the pin, the speed baseline and classification - while
 * boosting GPS so the verdict lands quickly. The exit is confirmed ([Verdict.EXIT]) by a
 * second out-of-threshold fix that is travel-consistent with the candidate (implied
 * speed within [LocationFilter.MAX_PLAUSIBLE_SPEED_MPS], mirroring the filter's own
 * jump-corroboration rule); a fix back inside the threshold discards the candidate as
 * the glitch it was. Real relocation therefore reports one boost-cadence fix
 * (~15-30s) later than an uncorroborated exit would; a glitch never reports at all.
 *
 * Candidates never expire by age: a late corroborating fix still proves displacement
 * (the device is far from the anchor on two independent readings), and a lingering
 * candidate holds nothing hostage - the pin simply stays on the anchor it is
 * presumably still at. Anchor tightening (a strictly better fix confirming the same
 * place, [MotionMath.shouldTightenAnchor]) also discards any pending candidate, since
 * it is sharp evidence the device never left.
 *
 * Pure Kotlin (no Android types) so it can be unit-tested on the JVM, mirroring
 * [LocationFilter] and [FixSelector]. HeartbeatService owns one instance per service
 * life and feeds it only filter-accepted fixes while the classifier is STATIONARY.
 */
class StationaryExitDetector {

    enum class Verdict {
        /** No anchor is set; the caller isn't tracking a stationary stay. */
        NO_ANCHOR,
        /** Fix is consistent with the anchor; still stationary. Any pending candidate
         *  was a glitch and has been discarded. */
        HELD,
        /** A strictly better fix confirmed the same place; the anchor tightened (and
         *  any pending candidate was discarded). Process the fix normally. */
        TIGHTENED,
        /** Fix implies an exit but is uncorroborated; the caller must withhold it
         *  (pin, speed baseline, classification) and await the next fix. */
        CANDIDATE,
        /** Exit corroborated: the device has provably left the anchor, which has been
         *  cleared. The caller exits STATIONARY and processes this fix normally. */
        EXIT,
    }

    private var anchorSet = false
    private var anchorLat = 0.0
    private var anchorLng = 0.0
    private var anchorAccM = 0f

    private var candidateSet = false
    private var candidateLat = 0.0
    private var candidateLng = 0.0
    private var candidateAccM = 0f
    private var candidateElapsedNs = 0L

    /** True while an uncorroborated exit candidate is pending - the caller keeps GPS
     *  boosted through this window so the corroborating fix arrives quickly. */
    val hasPendingCandidate: Boolean get() = candidateSet

    /** True while a stationary stay is anchored (cleared by a confirmed exit). */
    val hasAnchor: Boolean get() = anchorSet

    /** Anchor the current stay at the reported resting position. */
    fun setAnchor(lat: Double, lng: Double, accuracyM: Float) {
        anchorSet = true
        anchorLat = lat
        anchorLng = lng
        anchorAccM = accuracyM
        candidateSet = false
    }

    fun clearAnchor() {
        anchorSet = false
        candidateSet = false
    }

    /** Judge a filter-accepted fix against the anchor; see [Verdict] for the outcomes. */
    fun evaluate(lat: Double, lng: Double, accuracyM: Float, elapsedRealtimeNs: Long): Verdict {
        if (!anchorSet) return Verdict.NO_ANCHOR
        val distM = GeoMath.haversineMetres(anchorLat, anchorLng, lat, lng).toFloat()
        if (MotionMath.shouldTightenAnchor(distM, anchorAccM, accuracyM)) {
            anchorLat = lat
            anchorLng = lng
            anchorAccM = accuracyM
            candidateSet = false
            return Verdict.TIGHTENED
        }
        if (!MotionMath.shouldExitStationary(distM, anchorAccM, accuracyM)) {
            candidateSet = false
            return Verdict.HELD
        }
        if (candidateSet) {
            val dtSec = (elapsedRealtimeNs - candidateElapsedNs) / 1_000_000_000f
            if (dtSec > 0f) {
                val fromCandidateM =
                    GeoMath.haversineMetres(candidateLat, candidateLng, lat, lng).toFloat()
                val impliedSpeed =
                    (fromCandidateM - candidateAccM - accuracyM).coerceAtLeast(0f) / dtSec
                if (impliedSpeed <= LocationFilter.MAX_PLAUSIBLE_SPEED_MPS) {
                    clearAnchor()
                    return Verdict.EXIT
                }
            }
        }
        // First candidate, or a fix travel-inconsistent with the pending one (two
        // glitches in different directions): (re)start corroboration from this fix.
        candidateSet = true
        candidateLat = lat
        candidateLng = lng
        candidateAccM = accuracyM
        candidateElapsedNs = elapsedRealtimeNs
        return Verdict.CANDIDATE
    }
}
