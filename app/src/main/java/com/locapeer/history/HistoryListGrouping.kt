package com.locapeer.history

import com.locapeer.data.entity.HeartbeatEntity
import com.locapeer.data.entity.RejectReason

/** One row of the history list: a single reading, or a collapsed run of attempts with no position. */
sealed interface HistoryListEntry {
    /** Stable LazyColumn key; gap keys are negative so they never collide with a reading id. */
    val key: Long

    data class Reading(val ping: HeartbeatEntity) : HistoryListEntry {
        override val key: Long get() = ping.id
    }

    /**
     * Consecutive attempts that produced no position for the same [reason] (location off,
     * permission withdrawn), from [firstMs] to [lastMs]. Shown as one line explaining the gap
     * instead of one identical row per update interval.
     */
    data class Gap(
        val reason: RejectReason?,
        val firstMs: Long,
        val lastMs: Long,
        val count: Int,
        override val key: Long,
    ) : HistoryListEntry
}

/** Pure Kotlin so the grouping is unit-testable without Compose. */
object HistoryListGrouping {

    /**
     * Groups [chronological] readings (oldest first) for the list. Runs of no-position attempts
     * with the same reason collapse into one [HistoryListEntry.Gap]; any reading with a position
     * (accepted or rejected) ends the run and is listed on its own. Order is preserved.
     */
    fun group(chronological: List<HeartbeatEntity>): List<HistoryListEntry> {
        val out = ArrayList<HistoryListEntry>(chronological.size)
        var runStart: HeartbeatEntity? = null
        var runEnd: HeartbeatEntity? = null
        var runCount = 0

        fun flush() {
            val start = runStart ?: return
            out += HistoryListEntry.Gap(
                reason = start.parsedRejectReason,
                firstMs = start.timestamp,
                lastMs = runEnd!!.timestamp,
                count = runCount,
                key = -start.id - 1,
            )
            runStart = null
            runEnd = null
            runCount = 0
        }

        for (ping in chronological) {
            if (ping.hasMappedPosition()) {
                flush()
                out += HistoryListEntry.Reading(ping)
                continue
            }
            if (runStart != null && runStart!!.parsedRejectReason != ping.parsedRejectReason) flush()
            if (runStart == null) runStart = ping
            runEnd = ping
            runCount++
        }
        flush()
        return out
    }
}

/**
 * False for a rejected attempt whose coordinates are only a placeholder: no fix, or location
 * permission withdrawn at the attempt. Such a row explains a gap but has no place on the map,
 * no address and no accuracy radius.
 */
internal fun HeartbeatEntity.hasMappedPosition(): Boolean =
    isAccepted || when (parsedRejectReason) {
        RejectReason.NO_FIX, RejectReason.PERMISSION_DENIED -> false
        else -> lat != 0.0 || lng != 0.0
    }
