package com.locapeer.onboarding

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.net.toUri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

object PermissionManager {

    /** Exposed as an immutable List: a public mutable list lets any caller add to it and
     *  silently change what other screens request. */
    val REQUIRED_PERMISSIONS: List<String> = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Permissions we request alongside the required set but do not gate on: the app
     * works without them. CAMERA is only needed to scan QR invites (the invite screen
     * re-checks and asks for it on demand), ACTIVITY_RECOGNITION (Android 10+) lets
     * Activity Recognition refine motion classification with a GPS-only fallback.
     * Below API 29 ACTIVITY_RECOGNITION is an install-time permission and needs no
     * runtime request.
     */
    val OPTIONAL_PERMISSIONS: List<String> =
        (listOf(Manifest.permission.CAMERA) +
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                listOf(Manifest.permission.ACTIVITY_RECOGNITION)
            } else {
                emptyList()
            })

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
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Open the app's own system-settings page, where "Allow all the time" (background
     *  location) lives on Android 11+. A runtime request for the background permission
     *  is silently denied there, so settings is the only reliable route. */
    fun openAppDetailsSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:${context.packageName}".toUri()
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
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
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
