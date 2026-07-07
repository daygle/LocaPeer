package com.locapeer.history

import com.locapeer.data.entity.HeartbeatEntity
import com.locapeer.util.GeoMath

/**
 * Display-time thinning of a history trail: hides points closer than the
 * user's minimum distance to the previously shown one, so a long dwell in one
 * place reads as a single point instead of a cloud of near-duplicates.
 *
 * Applied only when rendering — every ping stays stored, so liveness features
 * (missed-heartbeat alerts, overdue pins) and retention are untouched, and
 * changing the setting re-filters existing history retroactively.
 */
object HistoryThinning {

    /**
     * Display-time accuracy filter: hides points whose reported accuracy radius is
     * larger than [maxAccuracyM], so a coarse cell fix doesn't drag the trail off to
     * a spot the device never really was. Non-destructive like [thin] — every ping
     * stays stored, and SOS pings are always kept whatever their accuracy.
     * 0 (or negative) shows every point.
     */
    fun filterByAccuracy(points: List<HeartbeatEntity>, maxAccuracyM: Int): List<HeartbeatEntity> {
        if (maxAccuracyM <= 0) return points
        // The common case is that nothing is coarse enough to drop, so scan first and
        // return the original list untouched — no allocation, and downstream
        // distinctUntilChanged / == checks can short-circuit on identity.
        val firstDrop = points.indexOfFirst { !it.isSos && it.accuracy > maxAccuracyM }
        if (firstDrop < 0) return points
        val kept = ArrayList<HeartbeatEntity>(points.size)
        kept.addAll(points.subList(0, firstDrop))
        for (i in firstDrop + 1 until points.size) {
            val p = points[i]
            if (p.isSos || p.accuracy <= maxAccuracyM) kept += p
        }
        return kept
    }

    /** [points] must be in chronological order, as the history queries return them. */
    fun thin(points: List<HeartbeatEntity>, minDistanceM: Int): List<HeartbeatEntity> {
        if (minDistanceM <= 0 || points.size <= 1) return points
        val kept = ArrayList<HeartbeatEntity>(points.size)
        var anchor = points.first()
        kept += anchor
        for (i in 1 until points.size) {
            val p = points[i]
            // SOS pings are never hidden, whatever their spacing.
            if (p.isSos ||
                GeoMath.haversineMetres(anchor.lat, anchor.lng, p.lat, p.lng) >= minDistanceM
            ) {
                kept += p
                anchor = p
            }
        }
        // The newest ping is the device's latest known position; always show it
        // even when it hasn't cleared the distance threshold yet.
        if (kept.last() !== points.last()) kept += points.last()
        return kept
    }
}
