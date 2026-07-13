package com.locapeer.beacon

import com.locapeer.util.GeoMath

/**
 * Chooses which of the fixes arriving within a pulse window becomes the reported
 * position. The fused provider delivers several fixes between heartbeats (the poll
 * interval is well under the pulse interval in every moving state), so broadcasting
 * whichever fix happened to land last can ship a momentarily coarse reading when a
 * sharper one arrived seconds earlier.
 *
 * This holds the sharper fix, but only while it is still current: a coarser fix wins
 * once the held fix is stale or the device has provably moved past their combined
 * uncertainty. The effect is a stationary pin that stays sharp (coarse network fixes
 * can't drag it) while a moving pin stays fresh (displacement forces the newer fix
 * through), with no extra GPS power - it only reorders the fixes already paid for.
 *
 * Pure Kotlin (no Android types) so it can be unit-tested on the JVM, mirroring
 * [LocationFilter]. HeartbeatService owns one instance per service life and feeds it
 * only fixes that already passed [LocationFilter]; those two roles are distinct - the
 * filter rejects implausible outliers, this picks the best of what remains.
 */
class FixSelector {

    companion object {
        /**
         * Backstop: never hold a fix longer than this. Past it a fresher (if coarser)
         * fix is taken, so the reported position can't lag reality when slow drift
         * stays under the movement threshold on every step.
         *
         * Skipped while the caller reports the device as stationary (see [select]'s
         * `stationaryHold`): at the stationary poll cadence (5 min) every fix arrives
         * past this window, so the backstop would hand the pin to the next coarse
         * network fix and the "stationary pin stays sharp" promise above would never
         * hold beyond 90s. While stationary the age backstop is unnecessary anyway -
         * the position isn't supposed to change, genuine relocation still forces the
         * newer fix through via the movement rule, and the stationary-exit detector
         * (which sees every filter-accepted fix, selected or not) provides the
         * independent escape hatch back to a moving profile.
         */
        const val MAX_HOLD_MS = 90_000L
    }

    private var hasSelected = false
    private var selLat = 0.0
    private var selLng = 0.0
    private var selAccM = 0f
    private var selElapsedNs = 0L

    /**
     * Drop the current selection so the next [select] call takes its fix
     * unconditionally. Used when a position is set outside the live fix stream (the
     * forced one-shot fetch), so the held baseline never outlives its relevance.
     */
    fun reset() {
        hasSelected = false
    }

    /**
     * True when [this fix] should become the reported position - the caller then copies
     * its payload into the broadcast cache. False keeps the currently held fix.
     *
     * Rules, in order: with nothing held, or a fix no less accurate than the held one,
     * take it (newer and at least as sharp is unambiguously better). A newer-but-coarser
     * fix is taken only once the held one is older than [MAX_HOLD_MS] or the device has
     * moved beyond the two fixes' combined accuracy; otherwise the sharper held fix wins.
     *
     * [stationaryHold] disables the age backstop while the caller's motion classifier
     * reports STATIONARY (see the note on [MAX_HOLD_MS]): only a sharper fix or provable
     * movement may then displace the held fix, so the resting pin can't be dragged
     * around by the coarse low-power fixes used for the rest of the stay.
     */
    fun select(
        lat: Double,
        lng: Double,
        accuracyM: Float,
        elapsedRealtimeNs: Long,
        stationaryHold: Boolean = false,
    ): Boolean {
        if (!hasSelected || accuracyM <= selAccM) {
            record(lat, lng, accuracyM, elapsedRealtimeNs)
            return true
        }
        val heldTooOld = !stationaryHold &&
            elapsedRealtimeNs - selElapsedNs >= MAX_HOLD_MS * 1_000_000L
        val distM = GeoMath.haversineMetres(selLat, selLng, lat, lng).toFloat()
        val moved = distM > selAccM + accuracyM
        if (heldTooOld || moved) {
            record(lat, lng, accuracyM, elapsedRealtimeNs)
            return true
        }
        return false
    }

    private fun record(lat: Double, lng: Double, accuracyM: Float, elapsedNs: Long) {
        hasSelected = true
        selLat = lat
        selLng = lng
        selAccM = accuracyM
        selElapsedNs = elapsedNs
    }
}
