package com.locapeer.settings

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locapeer.beacon.HeartbeatService
import com.locapeer.beacon.ACTION_STOP
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.HeartbeatDao
import com.locapeer.data.dao.MessageDao
import com.locapeer.data.dao.PeerDao
import com.locapeer.invite.InviteData
import com.locapeer.invite.QrCodeGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val prefs: AppPreferences,
    private val keyManager: KeyManager,
    private val peerDao: PeerDao,
    private val heartbeatDao: HeartbeatDao,
    private val messageDao: MessageDao,
    private val qrGenerator: QrCodeGenerator
) : ViewModel() {

    val settings: StateFlow<AppSettings> = prefs.settings
        .stateIn(viewModelScope, SharingStarted.Lazily, AppSettings())

    val peers = peerDao.getAllPeers()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _publicKeyHex = MutableStateFlow("")
    val publicKeyHex: StateFlow<String> = _publicKeyHex

    private val _profileQr = MutableStateFlow<Bitmap?>(null)
    val profileQr: StateFlow<Bitmap?> = _profileQr

    init {
        viewModelScope.launch {
            val (_, pubHex) = keyManager.ensureKeypair()
            _publicKeyHex.value = pubHex
            val s = prefs.settings.first()
            val json = Json.encodeToString(
                InviteData(publicKeyHex = pubHex, displayName = s.displayName, relayUrl = s.relayUrl, deviceId = pubHex)
            )
            _profileQr.value = qrGenerator.generate(json)
        }
    }

    fun updateDisplayName(name: String) {
        viewModelScope.launch { prefs.updateDisplayName(name) }
    }

    fun updateRelayUrl(url: String) {
        viewModelScope.launch { prefs.updateRelayUrl(url) }
    }

    fun setHeartbeatEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setHeartbeatEnabled(enabled)
            if (enabled) {
                val intent = Intent(context, HeartbeatService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    context.startForegroundService(intent)
                else
                    context.startService(intent)
            } else {
                context.startService(Intent(context, HeartbeatService::class.java).apply {
                    action = ACTION_STOP
                })
            }
        }
    }

    fun removePeer(deviceId: String) {
        viewModelScope.launch { peerDao.deletePeerById(deviceId) }
    }

    fun clearLocationHistory() {
        viewModelScope.launch { heartbeatDao.deleteOlderThan(System.currentTimeMillis()) }
    }

    fun clearMessageHistory() {
        viewModelScope.launch { messageDao.deleteOlderThan(System.currentTimeMillis()) }
    }

    fun exportPrivateKey(onKey: (String) -> Unit) {
        viewModelScope.launch {
            val key = keyManager.exportPrivateKeyHex()
            onKey(key)
        }
    }

    fun setGlobalScheduleEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setGlobalScheduleEnabled(enabled) }
    }

    fun updateGlobalSchedule(days: Int? = null, startMinute: Int? = null, endMinute: Int? = null) {
        viewModelScope.launch { prefs.updateGlobalSchedule(days, startMinute, endMinute) }
    }

    fun updateIntervals(
        stationary: Int? = null,
        walking: Int? = null,
        driving: Int? = null,
        lowBattery: Int? = null
    ) {
        viewModelScope.launch {
            prefs.updateIntervals(stationary, walking, driving, lowBattery)
        }
    }
}
