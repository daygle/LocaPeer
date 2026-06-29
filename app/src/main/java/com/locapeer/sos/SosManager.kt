package com.locapeer.sos

import android.content.Context
import android.content.Intent
import android.os.Build
import com.locapeer.beacon.ACTION_SOS_OFF
import com.locapeer.beacon.ACTION_SOS_ON
import com.locapeer.beacon.HeartbeatService
import com.locapeer.settings.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SosManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _isSosActive = MutableStateFlow(false)
    val isSosActive: StateFlow<Boolean> = _isSosActive.asStateFlow()

    init {
        // Restore SOS state after process death (service is sticky, so it may restart mid-SOS)
        scope.launch {
            _isSosActive.value = prefs.settings.first().sosActive
        }
    }

    fun activateSos() {
        val hasLocation = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                         context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasLocation) {
            // Can't start service without location permission
            return
        }
        _isSosActive.value = true
        scope.launch { prefs.setSosActive(true) }
        startService(Intent(context, HeartbeatService::class.java).apply { action = ACTION_SOS_ON })
    }

    fun deactivateSos() {
        _isSosActive.value = false
        scope.launch { prefs.setSosActive(false) }
        startService(Intent(context, HeartbeatService::class.java).apply { action = ACTION_SOS_OFF })
    }

    private fun startService(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
