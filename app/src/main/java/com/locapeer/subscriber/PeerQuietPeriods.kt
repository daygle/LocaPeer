package com.locapeer.subscriber

import android.content.Context
import androidx.core.content.edit
import com.locapeer.sharing.SharingSchedule

/**
 * Per-contact "updates stopped on purpose until" times, as told by the contact's own app (in a
 * heartbeat's quietUntilMs, or a kind-1072 pause notice). [MissedHeartbeatWorker] holds back its
 * alert while a contact is in such a planned gap. Kept in SharedPreferences next to the worker's
 * own alert state: it is a small, per-contact hint, not data worth a database migration.
 *
 * Each entry also stores the sender-clock time it was announced at, so a notice delivered late
 * (relay catch-up) cannot overwrite a newer one.
 */
object PeerQuietPeriods {
    private const val PREFS = "peer_quiet_periods"

    fun record(context: Context, deviceId: String, quietUntilMs: Long, announcedAtMs: Long) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = parse(sp.getString(deviceId, null))
        if (previous != null && previous.second > announcedAtMs) return
        sp.edit {
            if (quietUntilMs == 0L) remove(deviceId) else putString(deviceId, "$quietUntilMs,$announcedAtMs")
        }
    }

    /** The contact's announced quiet-until time (epoch ms), or 0 when none is known. */
    fun quietUntil(context: Context, deviceId: String): Long =
        parse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(deviceId, null))?.first ?: 0L

    fun clear(context: Context, deviceId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(deviceId) }
    }

    private fun parse(value: String?): Pair<Long, Long>? {
        val parts = value?.split(',') ?: return null
        if (parts.size != 2) return null
        val until = parts[0].toLongOrNull() ?: return null
        val at = parts[1].toLongOrNull() ?: return null
        return until to at
    }
}

/**
 * True while a missed-update alert should be held back: the contact announced a planned gap
 * ending at [quietUntilMs] (0 = none), and either it hasn't ended or less than [graceMs] has
 * passed since, which leaves the first heartbeat after the gap time to arrive.
 */
internal fun isInPlannedGap(quietUntilMs: Long, nowMs: Long, graceMs: Long): Boolean = when (quietUntilMs) {
    0L -> false
    SharingSchedule.QUIET_INDEFINITELY -> true
    // Rearranged so a large peer-supplied value can't overflow the sum.
    else -> nowMs - graceMs < quietUntilMs
}
