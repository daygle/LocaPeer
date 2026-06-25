package com.locapeer.sos

import android.content.Context
import android.content.Intent
import android.os.Build
import com.locapeer.beacon.ACTION_SOS_OFF
import com.locapeer.beacon.ACTION_SOS_ON
import com.locapeer.beacon.HeartbeatService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SosManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _isSosActive = MutableStateFlow(false)
    val isSosActive: StateFlow<Boolean> = _isSosActive.asStateFlow()

    fun activateSos() {
        _isSosActive.value = true
        val intent = Intent(context, HeartbeatService::class.java).apply {
            action = ACTION_SOS_ON
        }
        startService(intent)
    }

    fun deactivateSos() {
        _isSosActive.value = false
        val intent = Intent(context, HeartbeatService::class.java).apply {
            action = ACTION_SOS_OFF
        }
        startService(intent)
    }

    private fun startService(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
