package com.locapeer.sharing

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.MessageDao
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.dao.PeerSharingConfigDao
import com.locapeer.data.dao.ProximityAlertDao
import com.locapeer.data.entity.PeerEntity
import com.locapeer.data.entity.PeerSharingConfig
import com.locapeer.data.entity.PrecisionMode
import com.locapeer.data.entity.ProximityAlertEntity
import com.locapeer.invite.TrackRequestPayload
import com.locapeer.nostr.NostrEvent
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.peer.PeerManager
import com.locapeer.settings.AppPreferences
import com.locapeer.settings.HARDCODED_RELAYS
import com.locapeer.supervised.SupervisionChangeReporter
import com.locapeer.supervised.SupervisionEndedSender
import com.locapeer.supervised.SupervisionInviteSender
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit
import javax.inject.Inject


data class PeerSharingUiState(
    val peer: PeerEntity? = null,
    val config: PeerSharingConfig? = null,
    val proximityAlert: ProximityAlertEntity? = null,
    val heartbeat: com.locapeer.data.entity.HeartbeatEntity? = null,
    /**
     * True when this peer is the user's supervisor and supervised mode is on. The user
     * may not stop sharing their location with their supervisor (disable sharing, or
     * drop the SEND role) while supervised - that would defeat supervision from a screen
     * the settings gate doesn't cover. Lifting supervised mode (gated in Settings behind
     * supervisor approval) is the only way to release this.
     */
    val supervisorLocked: Boolean = false,
)

/** Outcome of a manual remote-purge command, typed so the UI doesn't sniff message text. */
data class PurgeResult(val message: String, val isError: Boolean = false)

@HiltViewModel
class PeerSharingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val peerDao: PeerDao,
    private val configDao: PeerSharingConfigDao,
    private val messageDao: MessageDao,
    private val workManager: WorkManager,
    private val proximityAlertDao: ProximityAlertDao,
    private val keyManager: KeyManager,
    private val crypto: CryptoUtils,
    private val relayClient: NostrRelayClient,
    private val prefs: AppPreferences,
    private val peerManager: PeerManager,
    private val heartbeatDao: com.locapeer.data.dao.HeartbeatDao,
    private val changeReporter: SupervisionChangeReporter,
    private val endedSender: SupervisionEndedSender,
    private val inviteSender: SupervisionInviteSender
) : ViewModel() {

    /** Display name of the peer this VM is bound to (for change summaries). */
    private suspend fun peerName(): String? = peerDao.getPeer(currentPeerId)?.displayName

    private val json = Json { ignoreUnknownKeys = true }
    private var currentPeerId: String = ""
    private var collectJob: Job? = null

    private val _uiState = MutableStateFlow(PeerSharingUiState())
    val uiState: StateFlow<PeerSharingUiState> = _uiState.asStateFlow()

    private val _lastPurgeResult = MutableStateFlow<PurgeResult?>(null)
    val lastPurgeResult: StateFlow<PurgeResult?> = _lastPurgeResult.asStateFlow()

    private val _roleChangeResult = MutableStateFlow<String?>(null)
    val roleChangeResult: StateFlow<String?> = _roleChangeResult.asStateFlow()
    fun clearRoleChangeResult() { _roleChangeResult.value = null }

    fun init(peerId: String) {
        if (currentPeerId == peerId) return
        currentPeerId = peerId
        collectJob?.cancel()
        collectJob = viewModelScope.launch {
            val peerFlow = peerDao.observePeer(peerId)
            val configFlow = configDao.observeForPeer(peerId)
            val alertFlow = proximityAlertDao.observeForPeer(peerId)
            val heartbeatFlow = heartbeatDao.observeLatestHeartbeat(peerId)
            val supervisorLockedFlow = prefs.settings.map {
                it.supervisionActive && it.supervisorPubkey.equals(peerId, ignoreCase = true)
            }

            combine(peerFlow, configFlow, alertFlow, heartbeatFlow, supervisorLockedFlow) {
                peer, config, alert, hb, locked ->
                PeerSharingUiState(peer, config, alert, hb, locked)
            }.collect {
                _uiState.value = it
            }
        }
    }

    private fun defaultConfig() = PeerSharingConfig(peerDeviceId = currentPeerId)

    fun setSharingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            // Can't stop sharing with the supervisor while supervised (see supervisorLocked).
            if (!enabled && isSupervisorLocked()) return@launch
            val existing = configDao.getForPeer(currentPeerId)
            if (existing != null) configDao.setSharingEnabled(currentPeerId, enabled)
            else configDao.upsert(defaultConfig().copy(sharingEnabled = enabled))
            // Visibility: the supervisor sees pause/resume of a destination.
            changeReporter.report(
                if (enabled) SupervisionChangeReporter.Areas.RESUME else SupervisionChangeReporter.Areas.PAUSE,
                peerName()
            )
        }
    }

    /** True when the current peer is the supervisor and supervised mode is active - the
     *  location-sharing controls toward that peer are then locked (see supervisorLocked). */
    private suspend fun isSupervisorLocked(): Boolean {
        val settings = prefs.settings.first()
        return settings.supervisionActive &&
            settings.supervisorPubkey.equals(currentPeerId, ignoreCase = true)
    }

    fun setMessagingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val peer = peerDao.getPeer(currentPeerId) ?: return@launch
            peerDao.upsertPeer(peer.copy(messagingEnabled = enabled))
            if (enabled) messageDao.unblockMessagesFromPeer(currentPeerId)
            // Messaging is not location visibility; no summary entry.
        }
    }

    fun setPrecisionMode(mode: PrecisionMode) {
        viewModelScope.launch {
            val existing = configDao.getForPeer(currentPeerId)
            if (existing != null) configDao.setPrecisionMode(currentPeerId, mode.name)
            else configDao.upsert(defaultConfig().copy(precisionMode = mode.name))
            changeReporter.report(SupervisionChangeReporter.Areas.PRECISION, peerName())
        }
    }

    fun setSosContact(enabled: Boolean) {
        viewModelScope.launch {
            val cfg = configDao.getForPeer(currentPeerId) ?: defaultConfig()
            configDao.upsert(cfg.copy(isSosContact = enabled))
            changeReporter.report(SupervisionChangeReporter.Areas.SOS_CONTACT, peerName())
        }
    }

    /**
     * Start (enable=true) or end (enable=false) supervising the current peer.
     *
     * Both directions are in-band from here on: enabling applies the same side effects
     * the registration-notification accept path applies (missed-heartbeat alerts on,
     * receive role promoted) AND sends a kind-1061 invite the device must consent to;
     * disabling sends kind-1063 so the far device clears its supervised state instead
     * of being orphaned behind an unlock gate that can never be answered again.
     */
    fun setIsMySupervised(enabled: Boolean) {
        viewModelScope.launch {
            val peer = peerDao.getPeer(currentPeerId) ?: return@launch
            if (enabled) {
                val existing = configDao.getForPeer(currentPeerId)
                if (existing != null) {
                    configDao.setIsMySupervised(currentPeerId, true)
                    configDao.setNotifyOnMissedHeartbeat(currentPeerId, true)
                } else {
                    configDao.upsert(
                        PeerSharingConfig(
                            peerDeviceId = currentPeerId,
                            isMySupervised = true,
                            notifyOnMissedHeartbeat = true
                        )
                    )
                }
                val promotedRole = PeerEntity.roleWithReceive(peer.locationRole)
                if (promotedRole != peer.locationRole) {
                    peerDao.upsertPeer(peer.copy(locationRole = promotedRole))
                }
                // Turning supervision back on withdraws an ended notice still waiting for
                // the relay: delivered after the new invite, it would clear the supervision
                // the two devices just re-established.
                endedSender.cancel(peer.publicKeyHex)
                sendSupervisionInvite(peer)
            } else {
                val existing = configDao.getForPeer(currentPeerId)
                if (existing != null) {
                    configDao.setIsMySupervised(currentPeerId, false)
                    // Explicitly release the forced-on alert, bypassing the toggle guard
                    // that keeps setNotifyOnMissedHeartbeat(false) blocked while supervised.
                    configDao.setNotifyOnMissedHeartbeat(currentPeerId, false)
                }
                // Withdraw an invitation the peer has not received yet: it must not arrive
                // after the user already turned the switch back off.
                inviteSender.cancel(peer.publicKeyHex)
                sendSupervisionEnded(peer)
            }
        }
    }

    /**
     * Kind 1061: ask the peer to consent to being supervised by us. Recorded before publishing
     * and replayed until a relay accepts it: the switch is already on locally, and an invite
     * lost here would show a supervision the other device was never asked about.
     */
    private suspend fun sendSupervisionInvite(peer: PeerEntity) {
        inviteSender.send(peer.publicKeyHex, peer.relayUrl)
    }

    /**
     * Kind 1063: tell the peer we stopped supervising them. Recorded before publishing and
     * replayed until a relay accepts it: a lost notice leaves the other device supervised
     * behind an unlock gate it can no longer answer, while our switch already reads as off.
     */
    private suspend fun sendSupervisionEnded(peer: PeerEntity) {
        endedSender.send(peer.publicKeyHex, peer.relayUrl)
    }

    fun setNotifyOnMissedHeartbeat(enabled: Boolean) {
        viewModelScope.launch {
            val existing = configDao.getForPeer(currentPeerId)
            // Missed-heartbeat alerts stay forced on for a device this user supervises -
            // it's the only reliable signal that a supervised device has gone dark.
            if (!enabled && (existing?.isMySupervised == true)) return@launch
            if (existing != null) configDao.setNotifyOnMissedHeartbeat(currentPeerId, enabled)
            else configDao.upsert(defaultConfig().copy(notifyOnMissedHeartbeat = enabled))
        }
    }

    fun setProximityAlertEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val existing = proximityAlertDao.getForPeer(currentPeerId)
            if (existing != null) {
                proximityAlertDao.setActive(currentPeerId, enabled)
            } else if (enabled) {
                proximityAlertDao.upsert(ProximityAlertEntity(peerDeviceId = currentPeerId, active = true))
            }
        }
    }

    fun setProximityAlertRadius(radius: Int) {
        viewModelScope.launch {
            val existing = proximityAlertDao.getForPeer(currentPeerId)
            if (existing != null) {
                proximityAlertDao.setRadius(currentPeerId, radius)
            } else {
                proximityAlertDao.upsert(ProximityAlertEntity(peerDeviceId = currentPeerId, radiusMetres = radius))
            }
        }
    }

    fun setProximityAlertSchedule(rules: List<ScheduleRule>) {
        viewModelScope.launch {
            val rulesJson = Json.encodeToString(rules)
            val existing = proximityAlertDao.getForPeer(currentPeerId)
            if (existing != null) {
                proximityAlertDao.upsert(existing.copy(scheduleRules = rulesJson))
            } else {
                proximityAlertDao.upsert(
                    ProximityAlertEntity(peerDeviceId = currentPeerId, scheduleRules = rulesJson, active = false)
                )
            }
        }
    }

    fun setRetentionDaysLocation(days: Int) {
        viewModelScope.launch {
            val existing = configDao.getForPeer(currentPeerId)
            if (existing != null) configDao.setRetentionDaysLocation(currentPeerId, days)
            else configDao.upsert(defaultConfig().copy(retentionDaysLocation = days))
        }
    }

    fun setRetentionDaysMessages(days: Int) {
        viewModelScope.launch {
            val existing = configDao.getForPeer(currentPeerId)
            if (existing != null) configDao.setRetentionDaysMessages(currentPeerId, days)
            else configDao.upsert(defaultConfig().copy(retentionDaysMessages = days))
        }
    }

    fun setSendRole(enabled: Boolean) {
        viewModelScope.launch {
            // Dropping the SEND role toward the supervisor stops location reaching them,
            // which supervised mode must prevent (see supervisorLocked).
            if (!enabled && isSupervisorLocked()) return@launch
            val peer = peerDao.getPeer(currentPeerId) ?: return@launch
            val wasSharing = peer.locationRole == PeerEntity.ROLE_SEND ||
                             peer.locationRole == PeerEntity.ROLE_SEND_RECEIVE
            val newRole = when (peer.locationRole) {
                PeerEntity.ROLE_NONE -> if (enabled) PeerEntity.ROLE_SEND else PeerEntity.ROLE_NONE
                PeerEntity.ROLE_SEND -> if (enabled) PeerEntity.ROLE_SEND else PeerEntity.ROLE_NONE
                PeerEntity.ROLE_RECEIVE -> if (enabled) PeerEntity.ROLE_SEND_RECEIVE else PeerEntity.ROLE_RECEIVE
                PeerEntity.ROLE_SEND_RECEIVE -> if (enabled) PeerEntity.ROLE_SEND_RECEIVE else PeerEntity.ROLE_RECEIVE
                else -> peer.locationRole
            }
            peerDao.upsertPeer(peer.copy(locationRole = newRole))
            val nowSharing = newRole == PeerEntity.ROLE_SEND || newRole == PeerEntity.ROLE_SEND_RECEIVE
            // Immediately command the peer to clear our stored location data when we revoke access,
            // so they don't continue to see a stale pin until heartbeat timeout.
            if (wasSharing && !nowSharing) {
                peerManager.sendDeleteMyLocation(currentPeerId)
            }
            changeReporter.report(SupervisionChangeReporter.Areas.ROLE, peerName())
        }
    }

    fun requestLocationAccess() {
        // Ask the peer to grant us RECEIVE access (i.e., for them to SEND their location to us)
        sendRoleChangeRequest(null)
    }

    fun sendRoleChangeRequest(requestedRole: String? = null) {
        viewModelScope.launch {
          // A key or crypto failure must surface as a log line, not crash the screen.
          try {
            val peer = peerDao.getPeer(currentPeerId) ?: return@launch
            val (privHex, pubHex) = keyManager.ensureKeypair()
            val settings = prefs.settings.first()
            val myRelay = HARDCODED_RELAYS.first()
            val payload = TrackRequestPayload(
                senderPublicKeyHex = pubHex,
                senderDisplayName = settings.displayName.ifBlank { "Someone" },
                senderDeviceId = pubHex,
                senderRelayUrl = myRelay,
                isRoleChange = true,
                requestedRole = requestedRole
            )
            val encrypted = crypto.nip44Encrypt(
                crypto.hexToBytes(privHex),
                peer.publicKeyHex,
                json.encodeToString(payload)
            )
            relayClient.connect(peer.relayUrl)
            relayClient.publishEvent(
                NostrEvent.build(
                    privKeyHex = privHex,
                    pubKeyHex = pubHex,
                    kind = NostrEventKind.TRACK_REQUEST,
                    content = encrypted,
                    tags = listOf(listOf("p", peer.publicKeyHex)),
                    crypto = crypto
                )
            )
            _roleChangeResult.value =
                context.getString(com.locapeer.R.string.peer_role_change_sent, peer.displayName)
          } catch (e: Exception) {
            android.util.Log.e("PeerSharingViewModel", "Failed to send role change request", e)
          }
        }
    }

    /** Manually command this peer to delete ALL of our location data from their device,
     *  regardless of their configured retention limit. */
    fun sendLocationPurgeNow() {
        viewModelScope.launch {
            val peer = peerDao.getPeer(currentPeerId)
            if (peer == null) {
                _lastPurgeResult.value =
                    PurgeResult(context.getString(com.locapeer.R.string.peer_not_found), isError = true)
                return@launch
            }
            peerManager.sendDeleteMyLocation(currentPeerId)
            _lastPurgeResult.value =
                PurgeResult(context.getString(com.locapeer.R.string.peer_purge_location_sent, peer.displayName))
        }
    }

    /** Manually command this peer to delete ALL of our messages from their device,
     *  regardless of their configured retention limit. */
    fun sendMessagePurgeNow() {
        viewModelScope.launch {
            val peer = peerDao.getPeer(currentPeerId)
            if (peer == null) {
                _lastPurgeResult.value =
                    PurgeResult(context.getString(com.locapeer.R.string.peer_not_found), isError = true)
                return@launch
            }
            peerManager.sendDeleteMyMessages(currentPeerId)
            _lastPurgeResult.value =
                PurgeResult(context.getString(com.locapeer.R.string.peer_purge_messages_sent, peer.displayName))
        }
    }

    /**
     * Set a one-off temporary share for the current peer. Overrides any recurring schedule
     * rules for the duration. Also schedules a WorkManager one-time job to clear the field
     * at the expiry wall-clock time, so the share stops working even if the app is closed
     * when it elapses.
     */
    fun setTemporaryShare(durationMinutes: Int) {
        viewModelScope.launch {
            val nowSec = System.currentTimeMillis() / 1000L
            val endsAtSec = nowSec + durationMinutes.coerceAtLeast(1) * 60L
            // setTemporaryShareEndsAt is an UPDATE: for a contact that has never had a
            // config row it matched nothing and the temporary share silently never started.
            if (configDao.getForPeer(currentPeerId) != null) {
                configDao.setTemporaryShareEndsAt(currentPeerId, endsAtSec)
            } else {
                configDao.upsert(defaultConfig().copy(temporaryShareEndsAtEpochSeconds = endsAtSec))
            }

            // Cancel any previous expiry worker for this peer - the latest call wins.
            // REPLACE in enqueueUniqueWork also handles the case where the user extended
            // the temp share via a new chip press.
            val request = OneTimeWorkRequestBuilder<TempShareExpiryWorker>()
                .setInitialDelay(durationMinutes.coerceAtLeast(1) * 60L, TimeUnit.SECONDS)
                .setInputData(
                    androidx.work.Data.Builder()
                        .putString("peerDeviceId", currentPeerId)
                        .build()
                )
                .build()
            workManager.enqueueUniqueWork(
                TempShareExpiryWorker.workNameFor(currentPeerId),
                ExistingWorkPolicy.REPLACE,
                request
            )
            changeReporter.report(SupervisionChangeReporter.Areas.TEMP_SHARE_START, peerName())
        }
    }

    /**
     * Cancel an active temporary share. Clears the field AND the pending worker so a
     * half-elapsed share doesn't re-fire after the user has explicitly revoked it.
     */
    fun clearTemporaryShare() {
        viewModelScope.launch {
            configDao.setTemporaryShareEndsAt(currentPeerId, null)
            workManager.cancelUniqueWork(TempShareExpiryWorker.workNameFor(currentPeerId))
            changeReporter.report(SupervisionChangeReporter.Areas.TEMP_SHARE_END, peerName())
        }
    }
}
