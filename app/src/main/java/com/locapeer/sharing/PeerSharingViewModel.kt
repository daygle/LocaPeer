package com.locapeer.sharing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

data class PeerSharingUiState(
    val peer: PeerEntity? = null,
    val config: PeerSharingConfig? = null,
    val proximityAlert: ProximityAlertEntity? = null
)

@HiltViewModel
class PeerSharingViewModel @Inject constructor(
    private val peerDao: PeerDao,
    private val configDao: PeerSharingConfigDao,
    private val messageDao: MessageDao,
    private val proximityAlertDao: ProximityAlertDao,
    private val keyManager: KeyManager,
    private val crypto: CryptoUtils,
    private val relayClient: NostrRelayClient,
    private val prefs: AppPreferences,
    private val peerManager: PeerManager
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }
    private var currentPeerId: String = ""
    private var collectJob: Job? = null

    private val _uiState = MutableStateFlow(PeerSharingUiState())
    val uiState: StateFlow<PeerSharingUiState> = _uiState.asStateFlow()

    private val _lastPurgeResult = MutableStateFlow<String?>(null)
    val lastPurgeResult: StateFlow<String?> = _lastPurgeResult.asStateFlow()

    private val _roleChangeResult = MutableStateFlow<String?>(null)
    val roleChangeResult: StateFlow<String?> = _roleChangeResult.asStateFlow()
    fun clearRoleChangeResult() { _roleChangeResult.value = null }

    fun clearPurgeResult() { _lastPurgeResult.value = null }

    fun init(peerId: String) {
        if (currentPeerId == peerId) return
        currentPeerId = peerId
        collectJob?.cancel()
        collectJob = viewModelScope.launch {
            val peerFlow = peerDao.observePeer(peerId)
            val configFlow = configDao.observeForPeer(peerId)
            val alertFlow = proximityAlertDao.observeForPeer(peerId)

            combine(peerFlow, configFlow, alertFlow) { peer, config, alert ->
                PeerSharingUiState(peer, config, alert)
            }.collect {
                _uiState.value = it
            }
        }
    }

    private fun defaultConfig() = PeerSharingConfig(peerDeviceId = currentPeerId)

    fun setSharingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val existing = configDao.getForPeer(currentPeerId)
            if (existing != null) configDao.setSharingEnabled(currentPeerId, enabled)
            else configDao.upsert(defaultConfig().copy(sharingEnabled = enabled))
        }
    }

    fun setMessagingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val peer = peerDao.getPeer(currentPeerId) ?: return@launch
            peerDao.upsertPeer(peer.copy(messagingEnabled = enabled))
            if (enabled) messageDao.unblockMessagesFromPeer(currentPeerId)
        }
    }

    fun setPrecisionMode(mode: PrecisionMode) {
        viewModelScope.launch {
            val existing = configDao.getForPeer(currentPeerId)
            if (existing != null) configDao.setPrecisionMode(currentPeerId, mode.name)
            else configDao.upsert(defaultConfig().copy(precisionMode = mode.name))
        }
    }

    fun setScheduleRules(rules: List<ScheduleRule>) {
        viewModelScope.launch {
            val rulesJson = Json.encodeToString(rules)
            val existing = configDao.getForPeer(currentPeerId)
            if (existing != null) configDao.setScheduleRules(currentPeerId, rulesJson)
            else configDao.upsert(defaultConfig().copy(scheduleRulesJson = rulesJson))
        }
    }

    fun setSosContact(enabled: Boolean) {
        viewModelScope.launch {
            val cfg = configDao.getForPeer(currentPeerId) ?: defaultConfig()
            configDao.upsert(cfg.copy(isSosContact = enabled))
        }
    }

    fun setIsMySupervised(enabled: Boolean) {
        viewModelScope.launch {
            val existing = configDao.getForPeer(currentPeerId)
            if (existing != null) configDao.setIsMySupervised(currentPeerId, enabled)
            else configDao.upsert(defaultConfig().copy(isMySupervised = enabled))
        }
    }

    fun setNotifyOnMissedHeartbeat(enabled: Boolean) {
        viewModelScope.launch {
            val existing = configDao.getForPeer(currentPeerId)
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
        }
    }

    fun requestLocationAccess() {
        // Ask the peer to grant us RECEIVE access (i.e., for them to SEND their location to us)
        sendRoleChangeRequest(null)
    }

    fun sendRoleChangeRequest(requestedRole: String? = null) {
        viewModelScope.launch {
            val peer = peerDao.getPeer(currentPeerId) ?: return@launch
            val (privHex, pubHex) = keyManager.ensureKeypair()
            val settings = prefs.settings.first()
            val myRelay = settings.customRelays.firstOrNull() ?: "wss://relay.daygle.net"
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
            _roleChangeResult.value = "Role change request sent to ${peer.displayName}"
        }
    }

    /** Manually command this peer to delete ALL of our location data from their device,
     *  regardless of their configured retention limit. */
    fun sendLocationPurgeNow() {
        viewModelScope.launch {
            val peer = peerDao.getPeer(currentPeerId)
            if (peer == null) {
                _lastPurgeResult.value = "Peer not found"
                return@launch
            }
            peerManager.sendDeleteMyLocation(currentPeerId)
            _lastPurgeResult.value = "Remote delete command for ALL locations sent to ${peer.displayName}"
        }
    }

    /** Manually command this peer to delete ALL of our messages from their device,
     *  regardless of their configured retention limit. */
    fun sendMessagePurgeNow() {
        viewModelScope.launch {
            val peer = peerDao.getPeer(currentPeerId)
            if (peer == null) {
                _lastPurgeResult.value = "Peer not found"
                return@launch
            }
            peerManager.sendDeleteMyMessages(currentPeerId)
            _lastPurgeResult.value = "Remote delete command for ALL messages sent to ${peer.displayName}"
        }
    }
}
