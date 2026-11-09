package com.locapeer.invite

import android.app.NotificationManager
import android.util.Log
import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.dao.PendingRequestDao
import com.locapeer.data.entity.PeerEntity
import com.locapeer.nostr.NostrEvent
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.settings.AppPreferences
import com.locapeer.settings.HARDCODED_RELAYS
import com.locapeer.subscriber.NOTIF_ID_TRACK_REQUEST
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ContactAddExecutor"

/**
 * Performs the two physical contact-add actions, decoupled from any ViewModel so an
 * approved per-action add (kind 1068) can complete even after the requesting screen or
 * the whole process is gone:
 *
 *  - [performOutboundAdd]: the scanned-invite flow (ScanViewModel).
 *  - [completeInboundAccept]: accepting an inbound tracking request (PendingRequestActions
 *    shares this so both the gated and ungated paths stay byte-identical).
 */
@Singleton
class ContactAddExecutor @Inject constructor(
    private val peerDao: PeerDao,
    private val pendingRequestDao: PendingRequestDao,
    private val trackResponseSender: TrackResponseSender,
    private val notificationManager: NotificationManager,
    private val keyManager: KeyManager,
    private val crypto: CryptoUtils,
    private val relayClient: NostrRelayClient,
    private val prefs: AppPreferences,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Upsert the staged invite as a SEND_RECEIVE peer, connect their relay, send the
     *  track request. Re-checks the self-invite guard so an approval completed long
     *  after scanning cannot loop heartbeats back onto this device. */
    suspend fun performOutboundAdd(invite: InviteData) {
        val (_, myPubHex) = keyManager.ensureKeypair()
        if (invite.publicKeyHex == myPubHex) return
        val existing = peerDao.getPeer(invite.deviceId)
        val peer = existing?.copy(
            publicKeyHex = invite.publicKeyHex,
            relayUrl = invite.relayUrl,
            locationRole = PeerEntity.ROLE_SEND_RECEIVE,
        ) ?: PeerEntity(
            deviceId = invite.deviceId,
            displayName = invite.displayName,
            publicKeyHex = invite.publicKeyHex,
            relayUrl = invite.relayUrl,
            locationRole = PeerEntity.ROLE_SEND_RECEIVE,
        )
        peerDao.upsertPeer(peer)
        relayClient.connect(invite.relayUrl)
        sendTrackRequest(invite)
    }

    /** Complete the accept of a stored inbound track request, or no-op if the row is
     *  already gone (e.g. declined from the list meanwhile). */
    suspend fun completeInboundAccept(senderPubkey: String, locationRole: String, messagingEnabled: Boolean) {
        val request = pendingRequestDao.getByPubkey(senderPubkey) ?: return
        notificationManager.cancel(request.senderPubkey, NOTIF_ID_TRACK_REQUEST)
        val existing = peerDao.getPeer(request.senderPubkey)
        peerDao.upsertPeer(
            existing?.copy(
                relayUrl = request.senderRelayUrl,
                locationRole = locationRole,
                messagingEnabled = messagingEnabled,
            ) ?: PeerEntity(
                deviceId = request.senderPubkey,
                displayName = request.senderName,
                publicKeyHex = request.senderPubkey,
                relayUrl = request.senderRelayUrl,
                locationRole = locationRole,
                messagingEnabled = messagingEnabled,
            )
        )
        try {
            trackResponseSender.sendAccept(request.senderPubkey, request.senderRelayUrl, locationRole)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send track accept", e)
        }
        pendingRequestDao.deleteByPubkey(request.senderPubkey)
    }

    /** TRACK_REQUEST builder (moved verbatim from ScanViewModel). */
    private suspend fun sendTrackRequest(target: InviteData) {
        try {
            val (privHex, pubHex) = keyManager.ensureKeypair()
            val settings = prefs.settings.first()
            val myRelay = HARDCODED_RELAYS.first()
            val myDisplayName = settings.displayName.ifBlank { "Someone" }

            val payload = TrackRequestPayload(
                senderPublicKeyHex = pubHex,
                senderDisplayName = myDisplayName,
                senderDeviceId = pubHex,
                senderRelayUrl = myRelay,
            )
            val encrypted = crypto.nip44Encrypt(
                crypto.hexToBytes(privHex),
                target.publicKeyHex,
                json.encodeToString(payload),
            )
            val event = NostrEvent.build(
                privKeyHex = privHex,
                pubKeyHex = pubHex,
                kind = NostrEventKind.TRACK_REQUEST,
                content = encrypted,
                tags = listOf(listOf("p", target.publicKeyHex)),
                crypto = crypto,
            )
            relayClient.publishEvent(event)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send track request", e)
        }
    }
}
