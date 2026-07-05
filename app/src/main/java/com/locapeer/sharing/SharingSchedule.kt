package com.locapeer.sharing

import com.locapeer.util.DisplayFormat
import java.util.Calendar

object SharingSchedule {

    val DAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    fun isActive(rules: List<ScheduleRule>): Boolean {
        if (rules.isEmpty()) return true
        return rules.any { isActive(it.days, it.startMinute, it.endMinute) }
    }

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
            else               -> 0
        }
        if (days and (1 shl dayIndex) == 0) return false
        val current = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return if (startMinute <= endMinute) current in startMinute until endMinute
        else current >= startMinute || current < endMinute
    }

    /** Formats a minute-of-day schedule boundary, honouring the user's 12/24-hour setting. */
    fun formatTime(minuteOfDay: Int): String {
        val hour = minuteOfDay / 60
        val minute = minuteOfDay % 60
        if (DisplayFormat.use24HourTime) {
            return "%02d:%02d".format(hour, minute)
        }
        val period = if (hour < 12) "AM" else "PM"
        val hour12 = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        return "%d:%02d %s".format(hour12, minute, period)
    }

    fun formatDays(days: Int): String = when (days) {
        0b1111111 -> "Every day"
        0b0011111 -> "Weekdays"
        0b1100000 -> "Weekends"
        0          -> "No days"
        else -> DAY_LABELS.filterIndexed { i, _ -> (days shr i) and 1 == 1 }.joinToString(", ")
    }

    fun toSuburbPrecision(lat: Double, lng: Double): Pair<Double, Double> =
        Math.round(lat * 100) / 100.0 to Math.round(lng * 100) / 100.0
}
