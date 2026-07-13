package com.locapeer.util

import android.content.Context
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Process-wide cache of display-formatting preferences (speed units and 12/24-hour clock).
 *
 * These preferences are read from many synchronous formatters scattered across screens and
 * view models (list rows, map popups, "last seen" labels, notification text). Threading the
 * values through every call site would be invasive, so instead the holder is populated once
 * from [com.locapeer.settings.AppPreferences] in the Application and read directly wherever a
 * value is formatted. It is read-mostly: writes happen only when the user flips a setting.
 *
 * Screens that observe settings reactively still update live; the holder simply guarantees a
 * correct value for the non-reactive formatters on their next composition/emission.
 */
object DisplayFormat {
    @Volatile var useImperialSpeed: Boolean = false
    @Volatile var use24HourTime: Boolean = true
    @Volatile var useImperialElevation: Boolean = false
    @Volatile var useImperialDistance: Boolean = false

    @android.annotation.SuppressLint("StaticFieldLeak") // application context only; outlives everything
    @Volatile private var appContext: Context? = null

    /** Seed the holder from the Application: stores the app context that backs the localized
     *  fragments below, and the device clock setting as the 24-hour default. */
    fun init(context: Context) {
        appContext = context.applicationContext
        use24HourTime = DateFormat.is24HourFormat(context)
    }

    /** Localized string via the app context, or null before [init] (e.g. JVM unit tests),
     *  in which case callers fall back to their English literal. */
    internal fun appString(resId: Int, vararg formatArgs: Any): String? =
        appContext?.getString(resId, *formatArgs)

    /** Clock-time pattern honouring the user's 12/24-hour choice. */
    fun timePattern(withSeconds: Boolean = false): String = when {
        use24HourTime && withSeconds -> "HH:mm:ss"
        use24HourTime -> "HH:mm"
        withSeconds -> "h:mm:ss a"
        else -> "h:mm a"
    }

    fun timeFormat(withSeconds: Boolean = false): SimpleDateFormat =
        SimpleDateFormat(timePattern(withSeconds), Locale.getDefault())

    /** Speed value converted from metres/second to the user's unit, e.g. "12 km/h" or "8 mph". */
    fun speedValue(speedMps: Float): String {
        val converted = if (useImperialSpeed) speedMps * 2.236936f else speedMps * 3.6f
        val unit = if (useImperialSpeed) "mph" else "km/h"
        return "${converted.toInt()} $unit"
    }

    /** Elevation value converted from metres to the user's unit, e.g. "412 m" or "1352 ft". */
    fun elevationValue(altitudeMeters: Double): String {
        val converted = if (useImperialElevation) altitudeMeters * 3.28084 else altitudeMeters
        val unit = if (useImperialElevation) "ft" else "m"
        return "${Math.round(converted)} $unit"
    }

    /**
     * Linear distance (accuracy, geofence/proximity radius, thinning threshold) converted from
     * metres to the user's unit. Small values stay in feet/metres for precision and roll up to
     * miles/kilometres once large: e.g. "42 m"/"138 ft", "1.2 km"/"1.2 mi".
     */
    fun distanceValue(meters: Double): String {
        if (useImperialDistance) {
            val feet = meters * 3.28084
            return if (feet < 5280) "${Math.round(feet)} ft"
                   else "${"%.1f".format(feet / 5280.0)} mi"
        }
        return if (meters < 1000) "${Math.round(meters)} m"
               else "${"%.1f".format(meters / 1000.0)} km"
    }

    /**
     * Relative "last seen" label: "Just now" / "5m ago" within the hour, a clock time within
     * the last 24 hours, "d MMM, <time>" for older same-year timestamps, and "d MMM yyyy"
     * once the year differs. Shared by the map and contacts screens.
     */
    fun relativeTimestamp(millis: Long): String {
        val diffMs = System.currentTimeMillis() - millis
        return when {
            diffMs < 60_000 ->
                appString(com.locapeer.R.string.time_just_now) ?: "Just now"
            diffMs < 3_600_000 ->
                appString(com.locapeer.R.string.time_minutes_ago, diffMs / 60_000) ?: "${diffMs / 60_000}m ago"
            diffMs < 86_400_000 -> timeFormat().format(java.util.Date(millis))
            else -> {
                val cal = java.util.Calendar.getInstance().also { it.timeInMillis = millis }
                val today = java.util.Calendar.getInstance()
                val fmt = if (cal.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR))
                    SimpleDateFormat("d MMM, ${timePattern()}", Locale.getDefault())
                else
                    SimpleDateFormat("d MMM yyyy", Locale.getDefault())
                fmt.format(java.util.Date(millis))
            }
        }
    }

    /** Direction string from a 0–360 degree bearing, e.g. "N", "SW". */
    fun bearingToCardinal(bearing: Float): String {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val normalized = ((bearing % 360f) + 360f) % 360f
        return dirs[((normalized + 22.5f) / 45f).toInt() % 8]
    }

    /** "47m" / "2h 5m" / "1d 3h" type human-readable duration from seconds remaining. */
    fun humanizeRemaining(secsLeft: Long): String {
        if (secsLeft <= 0) return "-"
        val totalMin = secsLeft / 60
        val days = totalMin / (24 * 60)
        val hours = (totalMin % (24 * 60)) / 60
        val mins = totalMin % 60
        return buildString {
            if (days > 0) append("${days}d ")
            if (days > 0 || hours > 0) append("${hours}h ")
            append("${mins}m")
        }.trim()
    }
}
