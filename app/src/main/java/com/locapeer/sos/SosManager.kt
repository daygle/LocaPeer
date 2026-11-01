package com.locapeer.sos

import android.content.Context
import android.util.Log
import com.locapeer.util.backgroundScope
import android.content.Intent
import com.locapeer.beacon.ACTION_SOS_OFF
import com.locapeer.beacon.ACTION_SOS_ON
import com.locapeer.beacon.HeartbeatService
import com.locapeer.data.dao.PeerSharingConfigDao
import com.locapeer.settings.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
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
    private val prefs: AppPreferences,
    private val sharingConfigDao: PeerSharingConfigDao
) {
    private val scope = backgroundScope("SosManager")
    private val _isSosActive = MutableStateFlow(false)
    val isSosActive: StateFlow<Boolean> = _isSosActive.asStateFlow()
    /** Set by an explicit activate/deactivate so the async restore below can't undo it. */
    @Volatile private var commandIssued = false

    init {
        // Restore SOS state after process death (service is sticky, so it may restart mid-SOS).
        scope.launch {
            if (commandIssued) return@launch
            val persisted = prefs.settings.first().sosActive
            if (persisted && !hasSosRecipient()) {
                // Stuck flag: the process died mid-SOS and every possible SosListener
                // contact row (isSosContact) was subsequently removed (or the flag was left
                // set by a mismatched data restore). Nobody would have received anything, and
                // a plain restore would re-arm a "live" SOS whose broadcast never reaches
                // anyone. Clear it instead of trusting the next manual deactivate.
                Log.w(TAG, "Clearing persisted SOS state: no SOS contacts configured")
                prefs.setSosActive(false)
                return@launch
            }
            _isSosActive.value = persisted
        }
    }

    /** True while at least one contact row is designated as an SOS (kind 1041) recipient. */
    private suspend fun hasSosRecipient(): Boolean = sharingConfigDao.getAll().any { it.isSosContact }

    private companion object {
        private const val TAG = "SosManager"
    }

    fun activateSos() {
        val hasLocation = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                         context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasLocation) {
            // Can't start service without location permission
            return
        }
        commandIssued = true
        _isSosActive.value = true
        scope.launch { prefs.setSosActive(true) }
        startService(Intent(context, HeartbeatService::class.java).apply { action = ACTION_SOS_ON })
    }

    fun deactivateSos() {
        commandIssued = true
        _isSosActive.value = false
        scope.launch { prefs.setSosActive(false) }
        startService(Intent(context, HeartbeatService::class.java).apply { action = ACTION_SOS_OFF })
    }

    private fun startService(intent: Intent) {
        context.startForegroundService(intent)
    }
}
