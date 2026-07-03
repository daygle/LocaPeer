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
