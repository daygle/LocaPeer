package com.locapeer.settings

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Log
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
import com.locapeer.data.entity.PeerEntity
import com.locapeer.invite.InviteData
import com.locapeer.invite.QrCodeGenerator
import com.locapeer.nostr.NostrEvent
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.supervised.SupervisedModeManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

private const val TAG = "SettingsViewModel"

@Serializable
data class LocaPeerBackup(
    val version: Int = 1,
    val privateKeyHex: String,
    val contacts: List<ContactBackup>
)

@Serializable
data class ContactBackup(
    val deviceId: String,
    val displayName: String,
    val publicKeyHex: String,
    val relayUrl: String,
    val role: String
)

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
    private val relayClient: NostrRelayClient,
    private val supervisedModeManager: SupervisedModeManager,
    private val peerManager: com.locapeer.peer.PeerManager
) : ViewModel() {

    val unlockState = supervisedModeManager.unlockState

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
                InviteData(
                    publicKeyHex = pubHex,
                    displayName = s.displayName,
                    relayUrl = "wss://relay.daygle.net",
                    deviceId = pubHex
                )
            )
            _profileQr.value = qrGenerator.generate(json)
        }
    }

    fun updateDisplayName(name: String) {
        viewModelScope.launch { prefs.updateDisplayName(name) }
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
        viewModelScope.launch { peerManager.removePeer(deviceId) }
    }

    fun updatePeerName(deviceId: String, newName: String) {
        viewModelScope.launch {
            val peer = peerDao.getPeer(deviceId) ?: return@launch
            peerDao.upsertPeer(peer.copy(displayName = newName))
        }
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

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            try {
                val privKey = keyManager.exportPrivateKeyHex()
                val contacts = peerDao.getAllPeers().first().map { p ->
                    ContactBackup(p.deviceId, p.displayName, p.publicKeyHex, p.relayUrl, p.role)
                }
                val backup = LocaPeerBackup(privateKeyHex = privKey, contacts = contacts)
                val json = Json.encodeToString(backup)
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                _backupResult.value = "Backup saved successfully (${contacts.size} contacts)"
            } catch (e: Exception) {
                Log.e(TAG, "Backup export failed", e)
                _backupResult.value = "Backup failed: ${e.message}"
            }
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            try {
                val json = context.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                } ?: run {
                    _backupResult.value = "Could not read file"
                    return@launch
                }
                val backup = Json.decodeFromString<LocaPeerBackup>(json)
                keyManager.importPrivateKey(backup.privateKeyHex)
                backup.contacts.forEach { c ->
                    peerDao.upsertPeer(
                        PeerEntity(c.deviceId, c.displayName, c.publicKeyHex, c.relayUrl, c.role)
                    )
                }
                _backupResult.value = "Restored ${backup.contacts.size} contacts"
            } catch (e: Exception) {
                Log.e(TAG, "Backup import failed", e)
                _backupResult.value = "Restore failed: ${e.message}"
            }
        }
    }

    fun clearBackupResult() { _backupResult.value = null }

    private val _backupResult = MutableStateFlow<String?>(null)
    val backupResult: StateFlow<String?> = _backupResult

    fun setRetentionDays(days: Int) {
        viewModelScope.launch { prefs.setRetentionDays(days) }
    }

    fun setMessageRetentionDays(days: Int) {
        viewModelScope.launch { prefs.setMessageRetentionDays(days) }
    }

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
                val encrypted = crypto.nip44Encrypt(
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
                val encrypted = crypto.nip44Encrypt(
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

    fun enableSupervisedMode(supervisorPubkey: String) {
        viewModelScope.launch {
            prefs.setSupervisedMode(enabled = true, supervisorPubkey = supervisorPubkey)
        }
    }

    fun disableSupervisedMode() {
        viewModelScope.launch {
            prefs.clearSupervisedMode()
            supervisedModeManager.reset()
        }
    }

    fun requestSettingsUnlock() = supervisedModeManager.requestAccess()

    fun resetUnlockState() = supervisedModeManager.reset()

    fun setGlobalScheduleEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setGlobalScheduleEnabled(enabled) }
    }

    fun updateGlobalSchedule(days: Int? = null, startMinute: Int? = null, endMinute: Int? = null) {
        viewModelScope.launch { prefs.updateGlobalSchedule(days, startMinute, endMinute) }
    }

    fun updateIntervals(
        stationary: Int? = null,
        walking: Int? = null,
        running: Int? = null,
        cycling: Int? = null,
        driving: Int? = null,
        lowBattery: Int? = null
    ) {
        viewModelScope.launch {
            prefs.updateIntervals(stationary, walking, running, cycling, driving, lowBattery)
        }
    }

    fun setStartRoute(route: String) {
        viewModelScope.launch { prefs.setStartRoute(route) }
    }

}
