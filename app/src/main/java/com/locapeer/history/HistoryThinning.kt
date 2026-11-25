package com.locapeer.history

import com.locapeer.data.entity.HeartbeatEntity
import com.locapeer.util.GeoMath

/**
 * Display-time thinning of a history trail: hides points closer than the
 * user's minimum distance to the previously shown one, so a long dwell in one
 * place reads as a single point instead of a cloud of near-duplicates.
 *
 * Applied only when rendering - every ping stays stored, so liveness features
 * (missed-heartbeat alerts, overdue pins) and retention are untouched, and
 * changing the setting re-filters existing history retroactively.
 *
 * Rejected readings are passed through untouched and never become the distance
 * anchor: they are kept as evidence of an attempt (the history view renders them
 * dimmed), but they must not move or thin the accepted breadcrumb.
 */
object HistoryThinning {

    /**
     * Applies accuracy filtering and distance thinning in a single pass to minimize
     * allocations. [points] must be in chronological order.
     */
    fun process(
        points: List<HeartbeatEntity>,
        maxAccuracyM: Int,
        minDistanceM: Int
    ): List<HeartbeatEntity> {
        if (points.size <= 1) return points
        val filterAcc = maxAccuracyM > 0
        val filterDist = minDistanceM > 0
        if (!filterAcc && !filterDist) return points

        val kept = ArrayList<HeartbeatEntity>(points.size)
        var anchor: HeartbeatEntity? = null
        // The latest accepted point is the trail's live tip. A rejected attempt after it
        // must not let that tip be thinned away as "just another point" inside the radius.
        val lastAcceptedIndex = points.indexOfLast { it.isAccepted }

        for (i in points.indices) {
            val p = points[i]

            // Keep rejected attempts, but keep them out of the accepted trail's geometry.
            if (!p.isAccepted) {
                kept += p
                continue
            }
            val isLatestAccepted = i == lastAcceptedIndex

            // SOS and the latest accepted point are always kept.
            if (p.isSos || isLatestAccepted) {
                kept += p
                anchor = p
                continue
            }

            // Accuracy filter: skip coarse points.
            if (filterAcc && p.accuracy > maxAccuracyM) continue

            // Distance thinning: skip points too close to the last kept one.
            if (filterDist && anchor != null) {
                val dist = GeoMath.haversineMetres(anchor.lat, anchor.lng, p.lat, p.lng)
                if (dist < minDistanceM) continue
            }

            kept += p
            anchor = p
        }
        return kept
    }
}
