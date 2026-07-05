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
}
