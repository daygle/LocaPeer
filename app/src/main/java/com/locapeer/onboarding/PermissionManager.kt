package com.locapeer.onboarding

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.util.Log
import androidx.core.net.toUri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

object PermissionManager {

    /**
     * The location permissions, always requested together: Android 12+ ignores a request for
     * fine location on its own, and the user may grant either precise or approximate. Either
     * grant is enough for the app to work (see [hasLocationPermission]), so that is all
     * onboarding requires.
     */
    val LOCATION_PERMISSIONS: List<String> = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    /**
     * Permissions we request alongside location but do not gate on: the app works without
     * them. POST_NOTIFICATIONS (Android 13+) only affects whether alerts are shown, CAMERA is
     * only needed to scan QR invites (the invite screen asks for it on demand), and
     * ACTIVITY_RECOGNITION (Android 10+) refines motion classification with a GPS-only
     * fallback. Below API 29 ACTIVITY_RECOGNITION is an install-time permission and needs no
     * runtime request. Exposed as immutable lists so no caller can change what others request.
     */
    val OPTIONAL_PERMISSIONS: List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACTIVITY_RECOGNITION)
    }

    /** Whether exact alarms may be scheduled. They keep the heartbeat on time through Doze and
     *  let the watchdog restart a stopped service; pre-granted below Android 14, user-granted
     *  ("Alarms & reminders") from Android 14 on. Always true below Android 12. */
    fun canScheduleExactAlarms(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(android.app.AlarmManager::class.java)?.canScheduleExactAlarms() == true

    /** Opens the system "Alarms & reminders" screen for this app (Android 12+). */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    fun openExactAlarmSettings(context: Context) {
        startSettings(
            context,
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri())
        )
    }

    fun hasBackgroundLocation(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    /**
     * Whether the device's location (GPS/network) master switch is on. This is independent of the
     * app's location permission: a user can grant the permission yet leave the system location
     * toggle off, in which case no fixes are ever produced and the app would otherwise appear to do
     * nothing. Surfaced to the user so they can turn it back on.
     */
    fun isLocationServicesEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    /** Open the system Location settings screen so the user can flip the master toggle on. */
    fun openLocationSettings(context: Context) {
        startSettings(context, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }

    /** Open the app's own system-settings page, where "Allow all the time" (background
     *  location) lives on Android 11+. A runtime request for the background permission
     *  is silently denied there, so settings is the only reliable route. */
    fun openAppDetailsSettings(context: Context) {
        startSettings(
            context,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
        )
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    @android.annotation.SuppressLint("BatteryLife")
    fun requestBatteryOptimizationExemption(context: Context) {
        val intent = if (isIgnoringBatteryOptimizations(context)) {
            // If already ignored, jump to the general settings list so the user can see it
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        } else {
            // Otherwise, show the direct request dialog (requires REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission)
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:${context.packageName}".toUri()
            }
        }
        startSettings(context, intent)
    }

    /**
     * Starts a system-settings [intent], falling back to the app's details page and then to
     * the top-level Settings screen. Some OEM and Android Go builds ship without the battery
     * optimisation or location-source activities, and an unhandled ActivityNotFoundException
     * there would crash the onboarding and permissions screens.
     */
    private fun startSettings(context: Context, intent: Intent) {
        val candidates = listOf(
            intent,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (candidate in candidates) {
            try {
                context.startActivity(candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: ActivityNotFoundException) {
                Log.w("PermissionManager", "No activity for ${candidate.action}", e)
            }
        }
    }
}
