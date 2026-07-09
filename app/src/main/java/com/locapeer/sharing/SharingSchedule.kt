package com.locapeer.sharing

import com.locapeer.util.DisplayFormat
import java.util.Calendar

object SharingSchedule {

    val DAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    /** Current local (dayIndex, minuteOfDay) in the rule matcher's convention: Monday = 0. */
    fun nowDayMinute(): Pair<Int, Int> {
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
        val currentMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return dayIndex to currentMinute
    }

    fun isActive(rules: List<ScheduleRule>): Boolean {
        if (rules.isEmpty()) return true
        val (dayIndex, currentMinute) = nowDayMinute()
        return isActive(rules, dayIndex, currentMinute)
    }

    fun isActive(rules: List<ScheduleRule>, dayIndex: Int, currentMinute: Int): Boolean {
        if (rules.isEmpty()) return true
        return rules.any { isActive(it.days, it.startMinute, it.endMinute, dayIndex, currentMinute) }
    }

    /**
     * Checks if the schedule is active for a specific rule and time.
     * Uses inclusive comparison for the end minute so that a rule ending at 23:59 (1439)
     * covers the full last minute of the day.
     */
    fun isActive(days: Int, startMinute: Int, endMinute: Int, dayIndex: Int, currentMinute: Int): Boolean {
        if (days and (1 shl dayIndex) == 0) return false
        return if (startMinute <= endMinute) currentMinute in startMinute..endMinute
        else currentMinute >= startMinute || currentMinute <= endMinute
    }

    /**
     * Formats a minute-of-day schedule boundary via the app's central time formatter, so the
     * labels honour the user's 12/24-hour setting and match the locale-aware AM/PM strings used
     * everywhere else.
     */
    fun formatTime(minuteOfDay: Int): String {
        val time = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
            set(Calendar.MINUTE, minuteOfDay % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.time
        return DisplayFormat.timeFormat().format(time)
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
