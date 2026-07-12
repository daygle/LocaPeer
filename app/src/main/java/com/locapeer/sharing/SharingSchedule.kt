package com.locapeer.sharing

import com.locapeer.util.DisplayFormat
import java.util.Calendar

object SharingSchedule {

    /**
     * Localized short weekday labels, Monday-first to match the rule matcher's day
     * indexing. Read from the platform's locale data so day names follow the user's
     * language without maintaining 50 translations of our own.
     */
    fun dayLabels(): List<String> {
        val symbols = java.text.DateFormatSymbols(java.util.Locale.getDefault()).shortWeekdays
        return listOf(
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
            Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY,
        ).map { symbols[it] }
    }

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
     * Per-peer "should I share right now" check that respects a one-off temporary share
     * alongside the recurring schedule. The temp share, when active, overrides the
     * schedule so the user can push a quick "share for the next hour" without changing
     * their normal weekly hours. Order matters: the temporary share is the LAST gate
     * because it is a deliberate override - if it is set, sharing is allowed regardless
     * of the schedule, but sharingEnabled / precision / role checks upstream in the
     * caller still apply. null endsAt means no active temp share, so the schedule
     * decides.
     */
    fun isPeerSharingActive(
        rules: List<ScheduleRule>,
        dayIndex: Int,
        currentMinute: Int,
        tempEndsAtEpochSeconds: Long?,
        nowEpochSeconds: Long
    ): Boolean {
        if (tempEndsAtEpochSeconds != null && nowEpochSeconds < tempEndsAtEpochSeconds) return true
        return isActive(rules, dayIndex, currentMinute)
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

    // English fallbacks only apply before DisplayFormat.init (e.g. JVM unit tests);
    // in the app the Application seeds the context first.
    fun formatDays(days: Int): String = when (days) {
        0b1111111 -> DisplayFormat.appString(com.locapeer.R.string.days_every_day) ?: "Every day"
        0b0011111 -> DisplayFormat.appString(com.locapeer.R.string.days_weekdays) ?: "Weekdays"
        0b1100000 -> DisplayFormat.appString(com.locapeer.R.string.days_weekends) ?: "Weekends"
        0          -> DisplayFormat.appString(com.locapeer.R.string.days_none) ?: "No days"
        else -> dayLabels().filterIndexed { i, _ -> (days shr i) and 1 == 1 }.joinToString(", ")
    }

    fun toSuburbPrecision(lat: Double, lng: Double): Pair<Double, Double> =
        Math.round(lat * 100) / 100.0 to Math.round(lng * 100) / 100.0
}
