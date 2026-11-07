package com.locapeer.map

import com.locapeer.beacon.LIVE_VIEW_INTERVAL_MS
import com.locapeer.beacon.MAX_LIVE_VIEW_INTERVAL_MS
import com.locapeer.data.entity.HeartbeatEntity

/**
 * Decides whether a contact's pin is streaming live (the "Live" badge).
 *
 * Senders report their *normal* cadence in [HeartbeatEntity.expectedIntervalSeconds] even
 * while streaming live to a watcher, so the overdue and missed-update checks keep working
 * after the watcher closes the map. Live therefore can't be read from that field; it is
 * inferred from the stream itself: the two most recent heartbeats arrived, and were sent,
 * within the slowest selectable live interval (plus slack) of each other, and the newest
 * is still fresh relative to that gap.
 *
 * Older app versions still report the live interval directly while streaming, so a
 * fresh heartbeat claiming an interval at or below the live floor also counts.
 */
object LivePinDetector {

    /**
     * Max gap between consecutive heartbeats that still reads as a live stream: comfortably
     * above the slowest selectable live interval (30s) yet below the shortest normal cadence
     * (1 min), so a contact on their regular schedule is never mistaken for live.
     */
    private const val MAX_LIVE_GAP_MS = MAX_LIVE_VIEW_INTERVAL_MS * 3 / 2

    /** Minimum time the badge persists after the newest heartbeat (a few fastest intervals). */
    private const val MIN_FRESH_MS = LIVE_VIEW_INTERVAL_MS * 4

    fun isLive(previous: HeartbeatEntity?, latest: HeartbeatEntity?, nowMs: Long): Boolean {
        if (latest == null || latest.isSos) return false
        // receivedAt is the receiver's own clock, so freshness is immune to sender skew.
        val sinceLatest = nowMs - latest.receivedAt
        if (latest.expectedIntervalSeconds * 1000L <= LIVE_VIEW_INTERVAL_MS) return sinceLatest < MIN_FRESH_MS
        if (previous == null || previous.id == latest.id) return false
        // Both gaps must be short: arrival alone would also match a relay catch-up burst
        // replaying old cadence heartbeats together, whose send timestamps are far apart.
        val arrivalGap = latest.receivedAt - previous.receivedAt
        val sendGap = latest.timestamp - previous.timestamp
        if (arrivalGap !in 0..MAX_LIVE_GAP_MS || sendGap !in 0..MAX_LIVE_GAP_MS) return false
        // Stay live for a couple of the sender's own intervals, so a slower live stream
        // (e.g. 30s) doesn't flicker off between updates.
        return sinceLatest < maxOf(MIN_FRESH_MS, arrivalGap * 2)
    }
}
