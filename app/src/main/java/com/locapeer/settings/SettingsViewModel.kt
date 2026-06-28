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
import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.GeofenceDao
import com.locapeer.data.dao.HeartbeatDao
import com.locapeer.data.dao.MessageDao
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.entity.GeofenceEntity
import com.locapeer.data.entity.PeerEntity
import com.locapeer.invite.InviteData
import com.locapeer.invite.QrCodeGenerator
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.sharing.ScheduleRule
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

enum class BackupSection { PRIVATE_KEY, CONTACTS, GEOFENCES, SETTINGS }

@Serializable
data class LocaPeerBackup(
    val version: Int = 2,
    val privateKeyHex: String? = null,
    val contacts: List<ContactBackup>? = null,
    val geofences: List<GeofenceBackup>? = null,
    val settings: SettingsBackup? = null
)

@Serializable
data class ContactBackup(
    val deviceId: String,
    val displayName: String,
    val publicKeyHex: String,
    val relayUrl: String,
    val locationRole: String = "SEND_RECEIVE",
    val messagingEnabled: Boolean = true,
    /** Legacy field from backup format v2 — used as fallback when locationRole is absent. */
    val role: String = ""
)

@Serializable
data class GeofenceBackup(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radiusMetres: Int,
    val trackedDeviceId: String,
    val triggerOn: String,
    val active: Boolean
)

@Serializable
data class SettingsBackup(
    val displayName: String,
    val stationaryIntervalMinutes: Int,
    val walkingIntervalMinutes: Int,
    val runningIntervalMinutes: Int,
    val cyclingIntervalMinutes: Int,
    val drivingIntervalMinutes: Int,
    val lowBatteryIntervalMinutes: Int,
    val navTabIds: List<String>,
    val startRoute: String
)

/** Parsed backup file ready for selective restore. */
data class PendingRestore(
    val backup: LocaPeerBackup,
    val availableSections: Set<BackupSection>
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val prefs: AppPreferences,
    private val keyManager: KeyManager,
    private val crypto: CryptoUtils,
    private val peerDao: PeerDao,
    private val geofenceDao: GeofenceDao,
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
                    relayUrl = s.customRelays.firstOrNull() ?: "wss://relay.daygle.net",
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

    fun exportBackup(uri: Uri, sections: Set<BackupSection>) {
        viewModelScope.launch {
            try {
                val s = settings.value
                val backup = LocaPeerBackup(
                    privateKeyHex = if (BackupSection.PRIVATE_KEY in sections)
                        keyManager.exportPrivateKeyHex() else null,
                    contacts = if (BackupSection.CONTACTS in sections)
                        peerDao.getAllPeers().first().map { p ->
                            ContactBackup(p.deviceId, p.displayName, p.publicKeyHex, p.relayUrl, p.locationRole, p.messagingEnabled)
                        } else null,
                    geofences = if (BackupSection.GEOFENCES in sections)
                        geofenceDao.getAllGeofences().first().map { g ->
                            GeofenceBackup(g.id, g.name, g.lat, g.lng, g.radiusMetres, g.trackedDeviceId, g.triggerOn, g.active)
                        } else null,
                    settings = if (BackupSection.SETTINGS in sections)
                        SettingsBackup(
                            displayName = s.displayName,
                            stationaryIntervalMinutes = s.stationaryIntervalMinutes,
                            walkingIntervalMinutes = s.walkingIntervalMinutes,
                            runningIntervalMinutes = s.runningIntervalMinutes,
                            cyclingIntervalMinutes = s.cyclingIntervalMinutes,
                            drivingIntervalMinutes = s.drivingIntervalMinutes,
                            lowBatteryIntervalMinutes = s.lowBatteryIntervalMinutes,
                            navTabIds = s.navTabIds,
                            startRoute = s.startRoute
                        ) else null
                )
                val json = jsonExport.encodeToString(backup)
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                val parts = sections.map { it.name.lowercase().replaceFirstChar { c -> c.uppercaseChar() } }
                _backupResult.value = "Backup saved: ${parts.joinToString(", ")}"
            } catch (e: Exception) {
                Log.e(TAG, "Backup export failed", e)
                _backupResult.value = "Backup failed: ${e.message}"
            }
        }
    }

    /** Parse backup file and return a PendingRestore so the UI can show a section picker. */
    fun loadBackupForRestore(uri: Uri) {
        viewModelScope.launch {
            try {
                val json = context.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                } ?: run { _backupResult.value = "Could not read file"; return@launch }
                val backup = jsonImport.decodeFromString<LocaPeerBackup>(json)
                val available = buildSet {
                    if (backup.privateKeyHex != null) add(BackupSection.PRIVATE_KEY)
                    if (!backup.contacts.isNullOrEmpty()) add(BackupSection.CONTACTS)
                    if (!backup.geofences.isNullOrEmpty()) add(BackupSection.GEOFENCES)
                    if (backup.settings != null) add(BackupSection.SETTINGS)
                }
                _pendingRestore.value = PendingRestore(backup, available)
            } catch (e: Exception) {
                Log.e(TAG, "Backup load failed", e)
                _backupResult.value = "Could not read backup: ${e.message}"
            }
        }
    }

    fun applyRestore(sections: Set<BackupSection>) {
        val pending = _pendingRestore.value ?: return
        viewModelScope.launch {
            try {
                val backup = pending.backup
                val restored = mutableListOf<String>()
                if (BackupSection.PRIVATE_KEY in sections && backup.privateKeyHex != null) {
                    keyManager.importPrivateKey(backup.privateKeyHex)
                    restored += "identity"
                }
                if (BackupSection.CONTACTS in sections && backup.contacts != null) {
                    backup.contacts.forEach { c ->
                        peerDao.upsertPeer(PeerEntity(
                            deviceId = c.deviceId,
                            displayName = c.displayName,
                            publicKeyHex = c.publicKeyHex,
                            relayUrl = c.relayUrl,
                            locationRole = c.locationRole.ifEmpty { c.role }.ifEmpty { "SEND_RECEIVE" },
                            messagingEnabled = c.messagingEnabled
                        ))
                    }
                    restored += "${backup.contacts.size} contacts"
                }
                if (BackupSection.GEOFENCES in sections && backup.geofences != null) {
                    backup.geofences.forEach { g ->
                        geofenceDao.upsert(GeofenceEntity(g.id, g.name, g.lat, g.lng, g.radiusMetres, g.trackedDeviceId, g.triggerOn, g.active))
                    }
                    restored += "${backup.geofences.size} geofences"
                }
                if (BackupSection.SETTINGS in sections && backup.settings != null) {
                    val s = backup.settings
                    prefs.updateDisplayName(s.displayName)
                    prefs.updateIntervals(s.stationaryIntervalMinutes, s.walkingIntervalMinutes,
                        s.runningIntervalMinutes, s.cyclingIntervalMinutes, s.drivingIntervalMinutes, s.lowBatteryIntervalMinutes)
                    prefs.setNavTabIds(s.navTabIds)
                    prefs.setStartRoute(s.startRoute)
                    restored += "settings"
                }
                _backupResult.value = "Restored: ${restored.joinToString(", ")}"
            } catch (e: Exception) {
                Log.e(TAG, "Restore failed", e)
                _backupResult.value = "Restore failed: ${e.message}"
            } finally {
                _pendingRestore.value = null
            }
        }
    }

    fun dismissPendingRestore() { _pendingRestore.value = null }
    fun clearBackupResult() { _backupResult.value = null }

    private val _backupResult = MutableStateFlow<String?>(null)
    val backupResult: StateFlow<String?> = _backupResult

    private val _pendingRestore = MutableStateFlow<PendingRestore?>(null)
    val pendingRestore: StateFlow<PendingRestore?> = _pendingRestore

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

    fun setGlobalScheduleRules(rules: List<ScheduleRule>) {
        viewModelScope.launch { prefs.setGlobalScheduleRules(rules) }
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

    fun setLocalLocationRetentionDays(days: Int) {
        viewModelScope.launch { prefs.setLocalLocationRetentionDays(days) }
    }

    fun setLocalMessageRetentionDays(days: Int) {
        viewModelScope.launch { prefs.setLocalMessageRetentionDays(days) }
    }

    companion object {
        private val jsonExport = Json { encodeDefaults = true }
        private val jsonImport = Json { ignoreUnknownKeys = true }
    }

}
