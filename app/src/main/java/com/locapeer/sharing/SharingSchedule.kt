package com.locapeer.sharing

import java.util.Calendar

object SharingSchedule {

    val DAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    /**
     * Returns true if the current local time falls within the sharing window.
     * Supports overnight schedules (endMinute < startMinute).
     */
    fun isActive(days: Int, startMinute: Int, endMinute: Int): Boolean {
        val now = Calendar.getInstance()
        val dayIndex = when (now.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY    -> 0
            Calendar.TUESDAY   -> 1
            Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY  -> 3
            Calendar.FRIDAY    -> 4
            Calendar.SATURDAY  -> 5
            Calendar.SUNDAY    -> 6
            else               -> return true
        }
        if (days and (1 shl dayIndex) == 0) return false
        val current = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return if (startMinute <= endMinute) current in startMinute..endMinute
        else current >= startMinute || current <= endMinute   // overnight window
    }

    fun formatTime(minuteOfDay: Int): String =
        "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

    /**
     * Fuzz coordinates to ~1 km (0.01°) grid for suburb-level precision.
     */
    fun toSuburbPrecision(lat: Double, lng: Double): Pair<Double, Double> =
        Math.round(lat * 100) / 100.0 to Math.round(lng * 100) / 100.0
}
