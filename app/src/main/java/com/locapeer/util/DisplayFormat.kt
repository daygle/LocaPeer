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

    /** Seed the clock default from the device before the settings flow first emits. */
    fun initClockDefault(context: Context) {
        use24HourTime = DateFormat.is24HourFormat(context)
    }

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

    /** Direction string from a 0–360 degree bearing, e.g. "N", "SW". */
    fun bearingToCardinal(bearing: Float): String {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val normalized = ((bearing % 360f) + 360f) % 360f
        return dirs[((normalized + 22.5f) / 45f).toInt() % 8]
    }
}
