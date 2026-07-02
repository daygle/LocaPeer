package com.locapeer.invite

import android.app.NotificationManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.dao.PendingRequestDao
import com.locapeer.data.entity.PeerEntity
import com.locapeer.nostr.NostrEvent
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.settings.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

sealed class IncomingRequestState {
    object Idle : IncomingRequestState()
    object Loading : IncomingRequestState()
    object Done : IncomingRequestState()
}

@HiltViewModel
class IncomingShareRequestViewModel @Inject constructor(
    private val peerDao: PeerDao,
    private val pendingRequestDao: PendingRequestDao,
    private val keyManager: KeyManager,
    private val relayClient: NostrRelayClient,
    private val crypto: CryptoUtils,
    private val prefs: AppPreferences,
    private val notificationManager: NotificationManager
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow<IncomingRequestState>(IncomingRequestState.Idle)
    val state: StateFlow<IncomingRequestState> = _state

    fun accept(senderPubkey: String, senderName: String, senderRelay: String, locationRole: String, messagingEnabled: Boolean) {
        notificationManager.cancel(senderPubkey, com.locapeer.subscriber.NOTIF_ID_TRACK_REQUEST)
        viewModelScope.launch {
            _state.value = IncomingRequestState.Loading
            val existing = peerDao.getPeer(senderPubkey)
            peerDao.upsertPeer(
                PeerEntity(
                    deviceId = senderPubkey,
                    displayName = existing?.displayName ?: senderName,
                    publicKeyHex = senderPubkey,
                    relayUrl = senderRelay,
                    locationRole = locationRole,
                    messagingEnabled = messagingEnabled,
                    addedAt = existing?.addedAt ?: System.currentTimeMillis()
                )
            )
            relayClient.connect(senderRelay)
            sendTrackAccept(senderPubkey, senderRelay, locationRole)
            pendingRequestDao.deleteByPubkey(senderPubkey)
            _state.value = IncomingRequestState.Done
        }
    }

    fun decline(senderPubkey: String, senderRelay: String, isRoleChange: Boolean = false) {
        notificationManager.cancel(senderPubkey, com.locapeer.subscriber.NOTIF_ID_TRACK_REQUEST)
        viewModelScope.launch {
            _state.value = IncomingRequestState.Loading
            relayClient.connect(senderRelay)
            sendTrackDecline(senderPubkey, isRoleChange)
            pendingRequestDao.deleteByPubkey(senderPubkey)
            _state.value = IncomingRequestState.Done
        }
    }

    private suspend fun sendTrackDecline(recipientPubkey: String, isRoleChange: Boolean = false) {
        val (privHex, pubHex) = keyManager.ensureKeypair()
        val settings = prefs.settings.first()
        val payload = TrackDeclinePayload(
            declinerPublicKeyHex = pubHex,
            declinerDisplayName = settings.displayName.ifBlank { "Someone" },
            declinerDeviceId = pubHex,
            isRoleChange = isRoleChange
        )
        val encrypted = crypto.nip44Encrypt(
            crypto.hexToBytes(privHex),
            recipientPubkey,
            json.encodeToString(payload)
        )
        relayClient.publishEvent(
            NostrEvent.build(
                privKeyHex = privHex,
                pubKeyHex = pubHex,
                kind = NostrEventKind.TRACK_DECLINE,
                content = encrypted,
                tags = listOf(listOf("p", recipientPubkey)),
                crypto = crypto
            )
        )
    }

    private suspend fun sendTrackAccept(recipientPubkey: String, recipientRelay: String, locationRole: String) {
        val (privHex, pubHex) = keyManager.ensureKeypair()
        val settings = prefs.settings.first()
        val myRelay = settings.customRelays.firstOrNull() ?: "wss://relay.daygle.net"
        val payload = TrackAcceptPayload(
            acceptorPublicKeyHex = pubHex,
            acceptorDisplayName = settings.displayName.ifBlank { "Someone" },
            acceptorDeviceId = pubHex,
            acceptorRelayUrl = myRelay,
            acceptedRole = locationRole
        )
        val encrypted = crypto.nip44Encrypt(
            crypto.hexToBytes(privHex),
            recipientPubkey,
            json.encodeToString(payload)
        )
        relayClient.publishEvent(
            NostrEvent.build(
                privKeyHex = privHex,
                pubKeyHex = pubHex,
                kind = NostrEventKind.TRACK_ACCEPT,
                content = encrypted,
                tags = listOf(listOf("p", recipientPubkey)),
                crypto = crypto
            )
        )
    }
}
