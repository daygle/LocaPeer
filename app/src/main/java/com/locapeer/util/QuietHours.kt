package com.locapeer.util

import java.time.Instant
import java.time.ZoneId

/**
 * Evaluates the notification quiet-hours window.
 *
 * The window is expressed as minutes-of-day in the user's current local time (start inclusive,
 * end exclusive). A start later than the end wraps across midnight - "quiet from 22:00 until
 * 08:00" is the expected configuration. A zero-length window (start == end) is treated as no
 * window at all so an unset range can never accidentally silence the whole day.
 */
object QuietHours {

    fun isQuietNow(
        startMinute: Int,
        endMinute: Int,
        zone: ZoneId = ZoneId.systemDefault(),
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        // Start never reaches 1440 (that would make the wrap branch unreachable); the end
        // may be 1440, meaning "until midnight".
        val start = startMinute.coerceIn(0, MINUTES_PER_DAY - 1)
        val end = endMinute.coerceIn(0, MINUTES_PER_DAY)
        // Zero-length window: nothing is defined. Using it as "always quiet" would be too
        // surprising a consequence of editing one of the two pickers onto the other.
        if (start == end) return false

        val minuteOfDay = Instant.ofEpochMilli(nowMillis).atZone(zone)
            // Local clock snaps to whole minutes; DST onset/offset can produce e.g. 23:59.9
            // staying 23:59 twice or vanishing - both harmless for a minute-resolution check.
            .let { zdt -> zdt.hour * MINUTES_PER_HOUR + zdt.minute }

        return if (start < end) {
            // Normal window, e.g. 08:00 .. 22:00: quiet within [start, end).
            minuteOfDay in start until end
        } else {
            // Window wraps midnight: quiet from start through 24:00/00:00 up to end.
            minuteOfDay >= start || minuteOfDay < end
        }
    }

    private const val MINUTES_PER_HOUR = 60
    private const val MINUTES_PER_DAY = 24 * 60
}
