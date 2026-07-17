package com.locapeer.beacon

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks which peers are currently *viewing* this device's location, so the heartbeat
 * loop can broadcast at a fast "live" cadence only while at least one contact is actually
 * looking at the map (see [LIVE_VIEW_INTERVAL_MS]).
 *
 * Each viewer's interest is a short, self-expiring lease: a viewer re-sends a
 * `LIVE_VIEW_REQUEST` every [LIVE_VIEW_RESEND_MS] while their map
 * is open, and each request grants a [LIVE_VIEW_LEASE_MS] lease keyed by the viewer's
 * pubkey. When a viewer closes the map (or goes offline) their re-sends stop, the lease
 * lapses, and this device falls back to its normal motion-adaptive cadence with no
 * explicit "stop" event required. Leases are keyed by pubkey so multiple viewers refresh
 * independently and one leaving doesn't cut short another's live view.
 *
 * A [Singleton] shared by the receiver (which grants leases) and the interval manager /
 * heartbeat service (which read them). All timing uses [SystemClock.elapsedRealtime] so
 * wall-clock adjustments can't extend or expire a lease.
 */
@Singleton
class LiveViewRegistry @Inject constructor() {

    // viewer pubkey -> lease expiry (elapsedRealtime millis). ConcurrentHashMap so the
    // grant path (relay IO thread) and the read path (heartbeat main-thread loop) never
    // need a shared lock.
    private val leases = ConcurrentHashMap<String, Long>()

    /**
     * Record (or refresh) a live-view lease for [viewerPubkey], valid for [leaseMs].
     * Returns true when this call transitions the registry from having no active lease to
     * having one, so the caller can nudge the heartbeat service to break out of a long
     * sleep and start broadcasting immediately rather than waiting up to a full interval.
     */
    fun grant(viewerPubkey: String, leaseMs: Long = LIVE_VIEW_LEASE_MS): Boolean {
        val now = SystemClock.elapsedRealtime()
        val wasActive = isActive(now)
        leases[viewerPubkey] = now + leaseMs
        return !wasActive
    }

    /** True when at least one unexpired lease exists. Prunes expired entries as it scans. */
    fun isActive(now: Long = SystemClock.elapsedRealtime()): Boolean {
        var active = false
        val it = leases.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value <= now) it.remove() else active = true
        }
        return active
    }
}

/** How long a single `LIVE_VIEW_REQUEST` keeps this device in live mode for that viewer.
 *  Comfortably longer than the viewer's re-send period so one dropped request doesn't
 *  visibly stutter the cadence, yet short enough that live mode ends within seconds of the
 *  viewer closing the map. */
const val LIVE_VIEW_LEASE_MS = 45_000L

/** Broadcast interval while any viewer is actively watching - the [LiveViewRegistry] floor
 *  the interval manager applies. Matches the heartbeat loop's hard minimum. */
const val LIVE_VIEW_INTERVAL_MS = 5_000L
