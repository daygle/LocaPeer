package com.locapeer.invite

import android.util.Log
import androidx.core.net.toUri
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
    val error: String? = null,
    /** Name of a parsed-but-not-yet-confirmed invite awaiting explicit user approval. */
    val pendingName: String? = null,
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

    /** Parsed invite held between scan/deep-link and the user's explicit confirmation. */
    private var pendingInvite: InviteData? = null

    /**
     * Parse and validate an invite, then stage it for explicit confirmation instead of
     * adding the contact immediately. Establishing two-way location sharing must be a
     * deliberate user action — a scanned QR or, more importantly, a `locapeer://` deep
     * link that a webpage or message could trigger, must not silently add a contact.
     */
    fun processQrCode(raw: String) {
        if (processed) return
        processed = true
        try {
            val invite = json.decodeFromString<InviteData>(raw)
            if (!isValidPubKeyHex(invite.publicKeyHex) || !isValidPubKeyHex(invite.deviceId)) {
                _scanState.value = ScanState(error = "Invalid invite: bad public key")
                processed = false
                return
            }
            // A device is identified by its Nostr key: deviceId and publicKeyHex must be the
            // same key. Rejecting a mismatch prevents a crafted invite from upserting an
            // existing peer row (keyed by deviceId) while pointing publicKeyHex at an
            // attacker-controlled key — which would hijack whose key we encrypt to/verify from.
            if (!invite.publicKeyHex.equals(invite.deviceId, ignoreCase = true)) {
                _scanState.value = ScanState(error = "Invalid invite: key mismatch")
                processed = false
                return
            }
            // Nostr serializes pubkeys as lowercase hex; normalize so the stored peer row
            // matches later events (and dedupes against an existing lowercase row).
            val canonicalKey = invite.publicKeyHex.lowercase()
            pendingInvite = invite.copy(publicKeyHex = canonicalKey, deviceId = canonicalKey)
            _scanState.value = ScanState(pendingName = invite.displayName.ifBlank { "this contact" })
        } catch (e: Exception) {
            _scanState.value = ScanState(error = e.message ?: "Unknown error")
            processed = false
        }
    }

    /** Commit the staged invite: add the contact and send the track request. */
    fun confirmAdd() {
        val invite = pendingInvite ?: return
        viewModelScope.launch {
            try {
                val existing = peerDao.getPeer(invite.deviceId)
                val peer = PeerEntity(
                    deviceId = invite.deviceId,
                    displayName = existing?.displayName ?: invite.displayName,
                    publicKeyHex = invite.publicKeyHex,
                    relayUrl = invite.relayUrl,
                    locationRole = PeerEntity.ROLE_SEND_RECEIVE,
                    messagingEnabled = existing?.messagingEnabled ?: true,
                    addedAt = existing?.addedAt ?: System.currentTimeMillis()
                )
                peerDao.upsertPeer(peer)
                relayClient.connect(invite.relayUrl)
                sendTrackRequest(invite)
                pendingInvite = null
                _scanState.value = ScanState(success = true, addedName = invite.displayName)
            } catch (e: Exception) {
                _scanState.value = ScanState(error = e.message ?: "Unknown error")
                processed = false
            }
        }
    }

    private fun isValidPubKeyHex(hex: String): Boolean =
        hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

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
                val uri = input.toUri()
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
        pendingInvite = null
        _scanState.value = ScanState()
    }
}
