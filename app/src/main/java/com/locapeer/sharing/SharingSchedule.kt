package com.locapeer.sharing

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
            else               -> return true
        }
        if (days and (1 shl dayIndex) == 0) return false
        val current = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return if (startMinute <= endMinute) current in startMinute..endMinute
        else current >= startMinute || current <= endMinute
    }

    fun formatTime(minuteOfDay: Int): String =
        "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

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
