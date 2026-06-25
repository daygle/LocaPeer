package com.locapeer.settings

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locapeer.beacon.HeartbeatService
import com.locapeer.beacon.ACTION_STOP
import com.locapeer.beacon.PurgeRequestPayload
import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.HeartbeatDao
import com.locapeer.data.dao.MessageDao
import com.locapeer.data.dao.PeerDao
import com.locapeer.invite.InviteData
import com.locapeer.invite.QrCodeGenerator
import com.locapeer.nostr.NostrEvent
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrRelayClient
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
    private val crypto: CryptoUtils,
    private val peerDao: PeerDao,
    private val heartbeatDao: HeartbeatDao,
    private val messageDao: MessageDao,
    private val qrGenerator: QrCodeGenerator,
    private val relayClient: NostrRelayClient
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

    fun setRetentionDays(days: Int) {
        viewModelScope.launch { prefs.setRetentionDays(days) }
    }

    fun setMessageRetentionDays(days: Int) {
        viewModelScope.launch { prefs.setMessageRetentionDays(days) }
    }

    /** Immediately sends a MESSAGE_PURGE_REQUEST Nostr event to all peers. */
    fun sendMessagePurgeNow() {
        viewModelScope.launch {
            val settings = prefs.settings.first()
            if (settings.messageRetentionDays == 0) return@launch
            val deleteBeforeMs = System.currentTimeMillis() - settings.messageRetentionDays * 24 * 3600 * 1000L
            val (privHex, pubHex) = keyManager.ensureKeypair()
            val payload = Json.encodeToString(
                PurgeRequestPayload(deviceId = pubHex, deleteOlderThanMs = deleteBeforeMs)
            )
            val allPeers = peerDao.getAllPeers().first()
            allPeers.forEach { peer ->
                val encrypted = crypto.nip04Encrypt(
                    senderPrivKey = crypto.hexToBytes(privHex),
                    recipientXOnlyHex = peer.publicKeyHex,
                    plaintext = payload
                )
                val event = NostrEvent.build(
                    privKeyHex = privHex,
                    pubKeyHex = pubHex,
                    kind = NostrEventKind.MESSAGE_PURGE_REQUEST,
                    content = encrypted,
                    tags = listOf(listOf("p", peer.publicKeyHex)),
                    crypto = crypto
                )
                relayClient.publishEvent(event)
            }
        }
    }

    /** Immediately sends a PURGE_REQUEST Nostr event to all current subscribers. */
    fun sendPurgeNow() {
        viewModelScope.launch {
            val settings = prefs.settings.first()
            if (settings.retentionDays == 0) return@launch
            val deleteBeforeMs = System.currentTimeMillis() - settings.retentionDays * 24 * 3600 * 1000L
            val (privHex, pubHex) = keyManager.ensureKeypair()
            val payload = Json.encodeToString(
                PurgeRequestPayload(deviceId = pubHex, deleteOlderThanMs = deleteBeforeMs)
            )
            val subscribers = peerDao.getSubscribers().first()
            subscribers.forEach { sub ->
                val encrypted = crypto.nip04Encrypt(
                    senderPrivKey = crypto.hexToBytes(privHex),
                    recipientXOnlyHex = sub.publicKeyHex,
                    plaintext = payload
                )
                val event = NostrEvent.build(
                    privKeyHex = privHex,
                    pubKeyHex = pubHex,
                    kind = NostrEventKind.PURGE_REQUEST,
                    content = encrypted,
                    tags = listOf(listOf("p", sub.publicKeyHex)),
                    crypto = crypto
                )
                relayClient.publishEvent(event)
            }
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
