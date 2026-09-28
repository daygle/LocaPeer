package com.locapeer.beacon

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.locapeer.settings.AppPreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var prefs: AppPreferences

    override fun onReceive(context: Context, intent: Intent) {
        // MY_PACKAGE_REPLACED: an app update kills the running service, and without this it
        // stayed down until the user next opened the app. Both broadcasts are exempt from
        // the background foreground-service start restrictions.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = prefs.settings.first()
                if (settings.heartbeatEnabled) {
                    val hasLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                                     ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    // Starting a location-type FGS from the background (which a boot
                    // receiver is) requires background location on Android 14+; with only
                    // a while-in-use grant the start is rejected by the system and the
                    // service's catch-all masks it as a silent stop. Check up front so
                    // the failure is visible in logs; the exact-alarm watchdog path
                    // (MissedHeartbeatWorker) remains the recovery once the app is used.
                    val hasBackgroundLocation = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

                    if (hasLocation && hasBackgroundLocation) {
                        val serviceIntent = Intent(context, HeartbeatService::class.java)
                        context.startForegroundService(serviceIntent)
                        Log.i("BootReceiver", "Heartbeat service restarted after ${intent.action}")
                    } else {
                        Log.w(
                            "BootReceiver",
                            "Heartbeat enabled but not restarted at boot: " +
                                "location=$hasLocation backgroundLocation=$hasBackgroundLocation"
                        )
                    }
                }
            } catch (e: Exception) {
                // e.g. ForegroundServiceStartNotAllowedException; an uncaught throw here would
                // crash the process from a coroutine with no handler.
                Log.e("BootReceiver", "Failed to restart heartbeat service", e)
            } finally {
                pending.finish()
            }
        }
    }
}
