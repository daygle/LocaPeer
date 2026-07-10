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
import com.locapeer.data.entity.PeerSharingConfig
import com.locapeer.invite.InviteData
import com.locapeer.invite.QrCodeGenerator
import com.locapeer.sharing.ScheduleRule
import com.locapeer.supervised.SupervisedModeManager
import com.locapeer.settings.HARDCODED_RELAYS
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

private const val TAG = "SettingsViewModel"

enum class BackupSection { PRIVATE_KEY, CONTACTS, GEOFENCES, SETTINGS }

@Serializable
data class LocaPeerBackup(
    val version: Int = 3,
    /** Base64-encoded encrypted backup payload (AES-GCM). Only present if encrypted. */
    val ciphertext: String? = null,
    /** Base64-encoded 12-byte IV for GCM. */
    val iv: String? = null,
    /** Base64-encoded 16-byte salt for PBKDF2. */
    val salt: String? = null,
    // Original fields below are null when 'ciphertext' is present
    val privateKeyHex: String? = null,
    val contacts: List<ContactBackup>? = null,
    val geofences: List<GeofenceBackup>? = null,
    val geofenceAssignments: List<GeofenceAssignmentBackup>? = null,
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
    val addedAt: Long? = null,
    val isArchived: Boolean = false,
    val archivedAt: Long = 0,
    val sharingConfig: SharingConfigBackup? = null
)

@Serializable
data class SharingConfigBackup(
    val sharingEnabled: Boolean,
    val precisionMode: String,
    val scheduleRulesJson: String,
    val isSosContact: Boolean,
    val retentionDaysLocation: Int,
    val retentionDaysMessages: Int,
    val isMySupervised: Boolean = false,
    val notifyOnMissedHeartbeat: Boolean = false
)

@Serializable
data class GeofenceBackup(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radiusMetres: Int
)

@Serializable
data class GeofenceAssignmentBackup(
    val id: String,
    val geofenceId: String,
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
    val startRoute: String,
    val pinColor: String = "",
    val localLocationRetentionDays: Int = 90,
    val localMessageRetentionDays: Int = 90,
    val historyMinDistanceMeters: Int = 0,
    val historyMaxAccuracyMeters: Int = 0,
    val sendMaxAccuracyMeters: Int = 0,
    val heartbeatEnabled: Boolean = true,
    val onboardingComplete: Boolean = true,
    val globalScheduleRules: List<ScheduleRule> = emptyList(),
    val useImperialSpeed: Boolean = false,
    val use24HourTime: Boolean = true,
    val useImperialElevation: Boolean = false,
    val useImperialDistance: Boolean = false,
    val notifyOnTrackingAlerts: Boolean = false,
    val reverseGeocodingEnabled: Boolean = false
)

/** Outcome message shown in the Keys & Backup card; [isError] drives its colour. */
data class BackupResult(val message: String, val isError: Boolean = false)

/** Parsed backup file ready for selective restore. */
data class PendingRestore(
    val backup: LocaPeerBackup,
    val availableSections: Set<BackupSection>,
    val requiresPassword: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val prefs: AppPreferences,
    private val keyManager: KeyManager,
    private val crypto: CryptoUtils,
    private val peerDao: PeerDao,
    private val geofenceDao: GeofenceDao,
    private val geofenceAssignmentDao: com.locapeer.data.dao.GeofenceAssignmentDao,
    private val heartbeatDao: HeartbeatDao,
    private val messageDao: MessageDao,
    private val sharingConfigDao: com.locapeer.data.dao.PeerSharingConfigDao,
    private val qrGenerator: QrCodeGenerator,
    private val supervisedModeManager: SupervisedModeManager
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
        refreshProfile()
    }

    /** Recompute the displayed public key and profile QR from the current identity and settings.
     *  Must be called after anything that changes the keypair or display name (e.g. a restore). */
    private fun refreshProfile() {
        viewModelScope.launch {
            val (_, pubHex) = keyManager.ensureKeypair()
            _publicKeyHex.value = pubHex
            val s = prefs.settings.first()
            val json = Json.encodeToString(
                InviteData(
                    publicKeyHex = pubHex,
                    displayName = s.displayName,
                    relayUrl = HARDCODED_RELAYS.first(),
                    deviceId = pubHex
                )
            )
            _profileQr.value = qrGenerator.generate(json)
        }
    }

    fun updateDisplayName(name: String) {
        viewModelScope.launch {
            prefs.updateDisplayName(name)
            refreshProfile()
        }
    }

    fun setPinColor(hex: String) {
        viewModelScope.launch { prefs.setPinColor(hex) }
    }

    fun setHeartbeatEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                val hasLocation = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                                 context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!hasLocation) {
                    Log.e(TAG, "Cannot enable heartbeat: location permission not granted")
                    return@launch
                }
            }

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

    fun setMapStartZoom(zoom: Double) {
        viewModelScope.launch { prefs.setMapStartZoom(zoom) }
    }

    fun setMapStartingPoint(mode: String) {
        viewModelScope.launch { prefs.setMapStartingPoint(mode) }
    }

    fun setMapFixedLocation(lat: Double, lng: Double) {
        viewModelScope.launch { prefs.setMapFixedLocation(lat, lng) }
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

    fun exportBackup(uri: Uri, sections: Set<BackupSection>, password: String? = null) {
        viewModelScope.launch {
            try {
                val s = prefs.settings.first()
                val plainBackup = LocaPeerBackup(
                    privateKeyHex = if (BackupSection.PRIVATE_KEY in sections)
                        keyManager.exportPrivateKeyHex() else null,
                    contacts = if (BackupSection.CONTACTS in sections) {
                        val sharingConfigs = sharingConfigDao.getAll().associateBy { it.peerDeviceId }
                        peerDao.getAllPeers().first().map { p ->
                            val cfg = sharingConfigs[p.deviceId]
                            ContactBackup(
                                deviceId = p.deviceId,
                                displayName = p.displayName,
                                publicKeyHex = p.publicKeyHex,
                                relayUrl = p.relayUrl,
                                locationRole = p.locationRole,
                                messagingEnabled = p.messagingEnabled,
                                addedAt = p.addedAt,
                                isArchived = p.isArchived,
                                archivedAt = p.archivedAt,
                                sharingConfig = cfg?.let {
                                    SharingConfigBackup(
                                        sharingEnabled = it.sharingEnabled,
                                        precisionMode = it.precisionMode,
                                        scheduleRulesJson = it.scheduleRulesJson,
                                        isSosContact = it.isSosContact,
                                        retentionDaysLocation = it.retentionDaysLocation,
                                        retentionDaysMessages = it.retentionDaysMessages,
                                        isMySupervised = it.isMySupervised,
                                        notifyOnMissedHeartbeat = it.notifyOnMissedHeartbeat
                                    )
                                }
                            )
                        }
                    } else null,
                    geofences = if (BackupSection.GEOFENCES in sections)
                        geofenceDao.getAllGeofences().first().map { g ->
                            GeofenceBackup(g.id, g.name, g.lat, g.lng, g.radiusMetres)
                        } else null,
                    geofenceAssignments = if (BackupSection.GEOFENCES in sections)
                        geofenceAssignmentDao.observeAll().first().map { a ->
                            GeofenceAssignmentBackup(a.id, a.geofenceId, a.trackedDeviceId, a.triggerOn, a.active)
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
                            startRoute = s.startRoute,
                            pinColor = s.pinColor,
                            localLocationRetentionDays = s.localLocationRetentionDays,
                            localMessageRetentionDays = s.localMessageRetentionDays,
                            historyMinDistanceMeters = s.historyMinDistanceMeters,
                            historyMaxAccuracyMeters = s.historyMaxAccuracyMeters,
                            sendMaxAccuracyMeters = s.sendMaxAccuracyMeters,
                            heartbeatEnabled = s.heartbeatEnabled,
                            onboardingComplete = s.onboardingComplete,
                            globalScheduleRules = s.globalScheduleRules,
                            useImperialSpeed = s.useImperialSpeed,
                            use24HourTime = s.use24HourTime,
                            useImperialElevation = s.useImperialElevation,
                            useImperialDistance = s.useImperialDistance,
                            notifyOnTrackingAlerts = s.notifyOnTrackingAlerts,
                            reverseGeocodingEnabled = s.reverseGeocodingEnabled
                        ) else null
                )

                val finalBackup = if (!password.isNullOrBlank()) {
                    val plainJson = jsonExport.encodeToString(plainBackup)
                    val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
                    val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
                    val key = crypto.deriveBackupKey(password, salt)
                    val encrypted = crypto.aesEncrypt(plainJson.toByteArray(Charsets.UTF_8), key, iv)
                    LocaPeerBackup(
                        version = 3,
                        ciphertext = android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP),
                        iv = android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP),
                        salt = android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP)
                    )
                } else {
                    plainBackup
                }

                val json = jsonExport.encodeToString(finalBackup)
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                val parts = sections.joinToString(", ") { context.getString(sectionLabelRes(it)) }
                _backupResult.value = BackupResult(context.getString(
                    if (!password.isNullOrBlank()) com.locapeer.R.string.backup_saved_encrypted
                    else com.locapeer.R.string.backup_saved,
                    parts
                ))
            } catch (e: Exception) {
                Log.e(TAG, "Backup export failed", e)
                _backupResult.value = BackupResult(context.getString(com.locapeer.R.string.backup_failed, e.message ?: ""), isError = true)
            }
        }
    }

    /** Parse backup file and return a PendingRestore so the UI can show a section picker. */
    fun loadBackupForRestore(uri: Uri) {
        viewModelScope.launch {
            try {
                val json = context.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                } ?: run {
                    _backupResult.value = BackupResult(context.getString(com.locapeer.R.string.backup_could_not_read_file), isError = true)
                    return@launch
                }
                val backup = jsonImport.decodeFromString<LocaPeerBackup>(json)
                
                if (backup.ciphertext != null) {
                    // This is an encrypted backup, we need a password before we can show available sections
                    _pendingRestore.value = PendingRestore(backup, emptySet(), requiresPassword = true)
                } else {
                    val available = buildSet {
                        if (backup.privateKeyHex != null) add(BackupSection.PRIVATE_KEY)
                        if (!backup.contacts.isNullOrEmpty()) add(BackupSection.CONTACTS)
                        if (!backup.geofences.isNullOrEmpty()) add(BackupSection.GEOFENCES)
                        if (backup.settings != null) add(BackupSection.SETTINGS)
                    }
                    _pendingRestore.value = PendingRestore(backup, available, requiresPassword = false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Backup load failed", e)
                _backupResult.value = BackupResult(context.getString(com.locapeer.R.string.backup_could_not_read, e.message ?: ""), isError = true)
            }
        }
    }

    /** Attempts to decrypt an encrypted backup using the provided password. */
    fun decryptBackupForRestore(password: String) {
        val pending = _pendingRestore.value ?: return
        val backup = pending.backup
        if (backup.ciphertext == null || backup.iv == null || backup.salt == null) return

        viewModelScope.launch {
            try {
                val salt = android.util.Base64.decode(backup.salt, android.util.Base64.NO_WRAP)
                val iv = android.util.Base64.decode(backup.iv, android.util.Base64.NO_WRAP)
                val ciphertext = android.util.Base64.decode(backup.ciphertext, android.util.Base64.NO_WRAP)
                
                // Key derivation is CPU-heavy (PBKDF2), do it on Default dispatcher
                val decryptedJson = withContext(Dispatchers.Default) {
                    val key = crypto.deriveBackupKey(password, salt)
                    val decryptedBytes = crypto.aesDecrypt(ciphertext, key, iv)
                    String(decryptedBytes, Charsets.UTF_8)
                }

                val decryptedBackup = jsonImport.decodeFromString<LocaPeerBackup>(decryptedJson)
                val available = buildSet {
                    if (decryptedBackup.privateKeyHex != null) add(BackupSection.PRIVATE_KEY)
                    if (!decryptedBackup.contacts.isNullOrEmpty()) add(BackupSection.CONTACTS)
                    if (!decryptedBackup.geofences.isNullOrEmpty()) add(BackupSection.GEOFENCES)
                    if (decryptedBackup.settings != null) add(BackupSection.SETTINGS)
                }
                _pendingRestore.value = PendingRestore(decryptedBackup, available, requiresPassword = false)
                _restorePasswordError.value = null
            } catch (e: Exception) {
                Log.w(TAG, "Backup decryption failed", e)
                _restorePasswordError.value = context.getString(com.locapeer.R.string.backup_incorrect_password)
            }
        }
    }

    private val _restorePasswordError = MutableStateFlow<String?>(null)
    val restorePasswordError: StateFlow<String?> = _restorePasswordError

    fun applyRestore(sections: Set<BackupSection>) {
        val pending = _pendingRestore.value ?: return
        viewModelScope.launch {
            val backup = pending.backup
            val restored = mutableListOf<String>()
            val failed = mutableListOf<String>()
            // Tracked separately from the user-facing [restored] strings so the profile refresh
            // does not depend on message wording.
            var identityRestored = false
            var settingsRestored = false

            // Each section is restored independently so that one bad section (most commonly a
            // malformed private key in a hand-edited backup) does not discard the others the
            // user also selected, and a mid-way failure is reported rather than swallowed. The
            // outer try/finally guards against any unexpected error still clearing the pending
            // state so the UI can never get stuck on the section picker.
            try {
                val identityLabel = context.getString(com.locapeer.R.string.backup_section_private_key)
                if (BackupSection.PRIVATE_KEY in sections && backup.privateKeyHex != null) {
                    val key = backup.privateKeyHex.trim().lowercase()
                    if (!PRIVATE_KEY_REGEX.matches(key)) {
                        Log.e(TAG, "Identity restore failed: private key must be 64 hex characters (0-9, a-f)")
                        failed += identityLabel
                    } else {
                        try {
                            keyManager.importPrivateKey(key)
                            identityRestored = true
                            restored += identityLabel
                        } catch (e: Exception) {
                            Log.e(TAG, "Identity restore failed", e)
                            failed += identityLabel
                        }
                    }
                }
                if (BackupSection.CONTACTS in sections && backup.contacts != null) {
                    try {
                        backup.contacts.forEach { c ->
                            peerDao.upsertPeer(PeerEntity(
                                deviceId = c.deviceId,
                                displayName = c.displayName,
                                publicKeyHex = c.publicKeyHex,
                                relayUrl = c.relayUrl,
                                locationRole = c.locationRole,
                                messagingEnabled = c.messagingEnabled,
                                isArchived = c.isArchived,
                                archivedAt = c.archivedAt,
                                addedAt = c.addedAt ?: System.currentTimeMillis()
                            ))
                            c.sharingConfig?.let { sc ->
                                sharingConfigDao.upsert(PeerSharingConfig(
                                    peerDeviceId = c.deviceId,
                                    sharingEnabled = sc.sharingEnabled,
                                    precisionMode = sc.precisionMode,
                                    scheduleRulesJson = sc.scheduleRulesJson,
                                    isSosContact = sc.isSosContact,
                                    isMySupervised = sc.isMySupervised,
                                    notifyOnMissedHeartbeat = sc.notifyOnMissedHeartbeat,
                                    retentionDaysLocation = sc.retentionDaysLocation,
                                    retentionDaysMessages = sc.retentionDaysMessages
                                ))
                            }
                        }
                        restored += context.resources.getQuantityString(
                            com.locapeer.R.plurals.restore_contacts_count, backup.contacts.size, backup.contacts.size
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Contacts restore failed", e)
                        failed += context.getString(com.locapeer.R.string.backup_section_contacts)
                    }
                }
                if (BackupSection.GEOFENCES in sections && backup.geofences != null) {
                    try {
                        backup.geofences.forEach { g ->
                            geofenceDao.upsert(GeofenceEntity(g.id, g.name, g.lat, g.lng, g.radiusMetres))
                        }
                        backup.geofenceAssignments?.forEach { a ->
                            geofenceAssignmentDao.upsert(
                                com.locapeer.data.entity.GeofenceAssignmentEntity(
                                    id = a.id,
                                    geofenceId = a.geofenceId,
                                    trackedDeviceId = a.trackedDeviceId,
                                    triggerOn = a.triggerOn,
                                    active = a.active
                                )
                            )
                        }
                        restored += context.resources.getQuantityString(
                            com.locapeer.R.plurals.restore_geofences_count, backup.geofences.size, backup.geofences.size
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Geofences restore failed", e)
                        failed += context.getString(com.locapeer.R.string.backup_section_geofences)
                    }
                }
                if (BackupSection.SETTINGS in sections && backup.settings != null) {
                    try {
                        val s = backup.settings
                        prefs.updateDisplayName(s.displayName)
                        prefs.updateIntervals(s.stationaryIntervalMinutes, s.walkingIntervalMinutes,
                            s.runningIntervalMinutes, s.cyclingIntervalMinutes, s.drivingIntervalMinutes, s.lowBatteryIntervalMinutes)
                        prefs.setNavTabIds(s.navTabIds)
                        prefs.setStartRoute(s.startRoute)
                        prefs.setPinColor(s.pinColor)
                        prefs.setLocalLocationRetentionDays(s.localLocationRetentionDays)
                        prefs.setLocalMessageRetentionDays(s.localMessageRetentionDays)
                        prefs.setHistoryMinDistanceMeters(s.historyMinDistanceMeters)
                        prefs.setHistoryMaxAccuracyMeters(s.historyMaxAccuracyMeters)
                        prefs.setSendMaxAccuracyMeters(s.sendMaxAccuracyMeters)
                        setHeartbeatEnabled(s.heartbeatEnabled)
                        prefs.setOnboardingComplete(s.onboardingComplete)
                        prefs.setGlobalScheduleRules(s.globalScheduleRules)
                        prefs.setUseImperialSpeed(s.useImperialSpeed)
                        prefs.setUse24HourTime(s.use24HourTime)
                        prefs.setUseImperialElevation(s.useImperialElevation)
                        prefs.setUseImperialDistance(s.useImperialDistance)
                        prefs.setNotifyOnTrackingAlerts(s.notifyOnTrackingAlerts)
                        prefs.setReverseGeocodingEnabled(s.reverseGeocodingEnabled)
                        settingsRestored = true
                        restored += context.getString(com.locapeer.R.string.backup_section_settings)
                    } catch (e: Exception) {
                        Log.e(TAG, "Settings restore failed", e)
                        failed += context.getString(com.locapeer.R.string.backup_section_settings)
                    }
                }
                // Identity and/or display name may have changed; refresh the shown pubkey and QR.
                if (identityRestored || settingsRestored) {
                    refreshProfile()
                }
                _backupResult.value = BackupResult(
                    message = buildString {
                        if (restored.isNotEmpty()) {
                            append(context.getString(com.locapeer.R.string.restore_result_restored, restored.joinToString(", ")))
                        }
                        if (failed.isNotEmpty()) {
                            if (isNotEmpty()) append(". ")
                            append(context.getString(com.locapeer.R.string.restore_result_failed, failed.joinToString(", ")))
                        }
                        if (isEmpty()) append(context.getString(com.locapeer.R.string.restore_nothing))
                    },
                    isError = failed.isNotEmpty()
                )
            } catch (e: Exception) {
                // Defensive: per-section blocks already catch their own failures, so this only
                // trips on something unexpected. Still surface it and let finally clear the state.
                Log.e(TAG, "Restore failed unexpectedly", e)
                _backupResult.value = BackupResult(context.getString(com.locapeer.R.string.restore_failed, e.message ?: ""), isError = true)
            } finally {
                _pendingRestore.value = null
            }
        }
    }

    fun dismissPendingRestore() { _pendingRestore.value = null }
    fun clearBackupResult() { _backupResult.value = null }

    private val _backupResult = MutableStateFlow<BackupResult?>(null)
    val backupResult: StateFlow<BackupResult?> = _backupResult

    private val _pendingRestore = MutableStateFlow<PendingRestore?>(null)
    val pendingRestore: StateFlow<PendingRestore?> = _pendingRestore

    fun enableSupervisedMode(supervisorPubkey: String) {
        viewModelScope.launch {
            prefs.setSupervisedMode(enabled = true, supervisorPubkey = supervisorPubkey)
            // Supervision only works if the supervisor receives this device's location, so
            // promote the supervisor contact's role to also send (NONE -> SEND,
            // RECEIVE -> SEND_RECEIVE), leaving any existing RECEIVE capability intact.
            // Without this the beacon never targets the supervisor (it only sends to
            // SEND/SEND_RECEIVE peers) and the supervised device shows no pin on their side.
            val supervisor = peerDao.getPeer(supervisorPubkey)
            if (supervisor != null) {
                val promotedRole = PeerEntity.roleWithSend(supervisor.locationRole)
                if (promotedRole != supervisor.locationRole) {
                    peerDao.upsertPeer(supervisor.copy(locationRole = promotedRole))
                }
            }
            supervisedModeManager.sendRegisterRequest(supervisorPubkey)
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

    fun resetIntervals() {
        viewModelScope.launch { prefs.resetIntervals() }
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

    fun setHistoryMinDistanceMeters(meters: Int) {
        viewModelScope.launch { prefs.setHistoryMinDistanceMeters(meters) }
    }

    fun setHistoryMaxAccuracyMeters(meters: Int) {
        viewModelScope.launch { prefs.setHistoryMaxAccuracyMeters(meters) }
    }

    fun setSendMaxAccuracyMeters(meters: Int) {
        viewModelScope.launch { prefs.setSendMaxAccuracyMeters(meters) }
    }

    fun setUseImperialSpeed(imperial: Boolean) {
        viewModelScope.launch { prefs.setUseImperialSpeed(imperial) }
    }

    fun setUse24HourTime(use24Hour: Boolean) {
        viewModelScope.launch { prefs.setUse24HourTime(use24Hour) }
    }

    fun setUseImperialElevation(imperial: Boolean) {
        viewModelScope.launch { prefs.setUseImperialElevation(imperial) }
    }

    fun setUseImperialDistance(imperial: Boolean) {
        viewModelScope.launch { prefs.setUseImperialDistance(imperial) }
    }

    fun setNotifyOnTrackingAlerts(notify: Boolean) {
        viewModelScope.launch { prefs.setNotifyOnTrackingAlerts(notify) }
    }

    fun setReverseGeocodingEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setReverseGeocodingEnabled(enabled) }
    }

    companion object {
        private val jsonExport = Json { encodeDefaults = true }
        private val jsonImport = Json { ignoreUnknownKeys = true }
        /** A restorable identity is a 32-byte secp256k1 scalar written as 64 lowercase hex chars. */
        private val PRIVATE_KEY_REGEX = Regex("^[0-9a-f]{64}$")

        private fun sectionLabelRes(section: BackupSection): Int = when (section) {
            BackupSection.PRIVATE_KEY -> com.locapeer.R.string.backup_section_private_key
            BackupSection.CONTACTS -> com.locapeer.R.string.backup_section_contacts
            BackupSection.GEOFENCES -> com.locapeer.R.string.backup_section_geofences
            BackupSection.SETTINGS -> com.locapeer.R.string.backup_section_settings
        }
    }

}
