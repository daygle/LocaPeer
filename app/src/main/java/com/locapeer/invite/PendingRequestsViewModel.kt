package com.locapeer.invite

import android.app.NotificationManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.dao.PendingRequestDao
import com.locapeer.data.entity.PeerEntity
import com.locapeer.data.entity.PendingRequestEntity
import com.locapeer.nostr.NostrEvent
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.settings.AppPreferences
import com.locapeer.settings.HARDCODED_RELAYS
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

@HiltViewModel
class PendingRequestsViewModel @Inject constructor(
    private val pendingRequestDao: PendingRequestDao,
    private val peerDao: PeerDao,
    private val keyManager: KeyManager,
    private val relayClient: NostrRelayClient,
    private val crypto: CryptoUtils,
    private val prefs: AppPreferences,
    private val notificationManager: NotificationManager
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }

    val requests: StateFlow<List<PendingRequestEntity>> = pendingRequestDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun accept(request: PendingRequestEntity, locationRole: String, messagingEnabled: Boolean) {
        notificationManager.cancel(request.senderPubkey, com.locapeer.subscriber.NOTIF_ID_TRACK_REQUEST)
        viewModelScope.launch {
            val existing = peerDao.getPeer(request.senderPubkey)
            peerDao.upsertPeer(
                PeerEntity(
                    deviceId = request.senderPubkey,
                    displayName = existing?.displayName ?: request.senderName,
                    publicKeyHex = request.senderPubkey,
                    relayUrl = request.senderRelayUrl,
                    locationRole = locationRole,
                    messagingEnabled = messagingEnabled,
                    addedAt = existing?.addedAt ?: System.currentTimeMillis()
                )
            )
            relayClient.connect(request.senderRelayUrl)
            sendTrackAccept(request.senderPubkey, request.senderRelayUrl, locationRole)
            pendingRequestDao.deleteByPubkey(request.senderPubkey)
        }
    }

    fun decline(request: PendingRequestEntity) {
        notificationManager.cancel(request.senderPubkey, com.locapeer.subscriber.NOTIF_ID_TRACK_REQUEST)
        viewModelScope.launch {
            relayClient.connect(request.senderRelayUrl)
            sendTrackDecline(request.senderPubkey, request.isRoleChange)
            pendingRequestDao.deleteByPubkey(request.senderPubkey)
        }
    }

    private suspend fun sendTrackDecline(recipientPubkey: String, isRoleChange: Boolean) {
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
        val myRelay = HARDCODED_RELAYS.first()
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
