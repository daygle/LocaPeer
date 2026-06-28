package com.locapeer.invite

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.PeerDao
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

data class ScanState(
    val success: Boolean = false,
    val addedName: String = "",
    val error: String? = null
)

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val peerDao: PeerDao,
    private val keyManager: KeyManager,
    private val relayClient: NostrRelayClient,
    private val crypto: CryptoUtils,
    private val prefs: AppPreferences
) : ViewModel() {

    private val _scanState = MutableStateFlow(ScanState())
    val scanState: StateFlow<ScanState> = _scanState

    private var processed = false
    private val json = Json { ignoreUnknownKeys = true }

    fun processQrCode(raw: String) {
        if (processed) return
        processed = true
        viewModelScope.launch {
            try {
                val invite = json.decodeFromString<InviteData>(raw)
                val existing = peerDao.getPeer(invite.deviceId)
                val newRole = PeerEntity.ROLE_SEND_RECEIVE

                val peer = PeerEntity(
                    deviceId = invite.deviceId,
                    displayName = invite.displayName,
                    publicKeyHex = invite.publicKeyHex,
                    relayUrl = invite.relayUrl,
                    role = newRole
                )
                peerDao.upsertPeer(peer)
                relayClient.connect(invite.relayUrl)
                sendTrackRequest(invite)
                _scanState.value = ScanState(success = true, addedName = invite.displayName)
            } catch (e: Exception) {
                _scanState.value = ScanState(error = e.message ?: "Unknown error")
                processed = false
            }
        }
    }

    private suspend fun sendTrackRequest(target: InviteData) {
        try {
            val (privHex, pubHex) = keyManager.ensureKeypair()
            val settings = prefs.settings.first()
            val myRelay = settings.customRelays.firstOrNull() ?: "wss://relay.daygle.net"
            val myDisplayName = settings.displayName.ifBlank { "Someone" }

            val payload = TrackRequestPayload(
                senderPublicKeyHex = pubHex,
                senderDisplayName = myDisplayName,
                senderDeviceId = pubHex,
                senderRelayUrl = myRelay
            )
            val encrypted = crypto.nip44Encrypt(
                crypto.hexToBytes(privHex),
                target.publicKeyHex,
                json.encodeToString(payload)
            )
            val event = NostrEvent.build(
                privKeyHex = privHex,
                pubKeyHex = pubHex,
                kind = NostrEventKind.TRACK_REQUEST,
                content = encrypted,
                tags = listOf(listOf("p", target.publicKeyHex)),
                crypto = crypto
            )
            relayClient.publishEvent(event)
        } catch (e: Exception) {
            Log.w("ScanViewModel", "Failed to send track request", e)
        }
    }

    fun processInviteLink(input: String) {
        try {
            val base64 = if (input.startsWith("locapeer://")) {
                val uri = android.net.Uri.parse(input)
                uri.getQueryParameter("data") ?: throw Exception("Invalid URL")
            } else {
                input
            }
            val raw = String(android.util.Base64.decode(base64, android.util.Base64.URL_SAFE))
            processQrCode(raw)
        } catch (_: Exception) {
            _scanState.value = ScanState(error = "Invalid invite link")
        }
    }

    fun reset() {
        processed = false
        _scanState.value = ScanState()
    }
}
