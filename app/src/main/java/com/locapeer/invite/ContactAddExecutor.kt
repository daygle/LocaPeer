package com.locapeer.invite

import android.app.NotificationManager
import android.util.Log
import androidx.room.withTransaction
import com.locapeer.data.AppDatabase
import com.locapeer.data.entity.PendingRequestEntity
import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.entity.ContactAddReceiptEntity
import com.locapeer.data.entity.PeerEntity
import com.locapeer.nostr.NostrEvent
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.settings.AppPreferences
import com.locapeer.settings.HARDCODED_RELAYS
import com.locapeer.subscriber.NOTIF_ID_TRACK_REQUEST
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ContactAddExecutor"

/** A receipt outlives a failed delete at most this long; the reply normally clears it. */
private const val RECEIPT_TTL_MS = 24 * 60 * 60_000L

/**
 * Atomically apply consent only if the request is still the one the user reviewed.
 *
 * With [receiptId], a receipt is written INSIDE the same transaction: after a crash, a
 * receipt proves the accept committed even though the pending request row is gone, so the
 * reply can be resent without re-applying or guessing from the peer row.
 */
internal suspend fun applyInboundRequest(
    database: AppDatabase,
    request: PendingRequestEntity,
    locationRole: String,
    messagingEnabled: Boolean,
    receiptId: String? = null,
): Boolean = database.withTransaction {
    val pendingRequestDao = database.pendingRequestDao()
    val peerDao = database.peerDao()
    if (pendingRequestDao.getByPubkey(request.senderPubkey) != request) return@withTransaction false
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
    pendingRequestDao.deleteByPubkey(request.senderPubkey)
    if (receiptId != null) {
        val receiptDao = database.contactAddReceiptDao()
        val now = System.currentTimeMillis()
        receiptDao.upsert(ContactAddReceiptEntity(receiptId, now))
        receiptDao.deleteAppliedBefore(now - RECEIPT_TTL_MS)
    }
    true
}

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
    private val database: AppDatabase,
    private val peerDao: PeerDao,
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
     *  after scanning cannot loop heartbeats back onto this device.
     *
     *  With [receiptId], a published track request is recorded the moment it goes out, so a
     *  resumed add after the crash that follows does not send it to the contact again.
     *
     *  Returns false for a self-invite or when the track request could not be published
     *  (a key or encryption failure). In that case the contact row is put back as it was:
     *  a SEND_RECEIVE row left behind would share this device's location with someone who was
     *  never asked, while the caller tells the user the add failed. */
    suspend fun performOutboundAdd(invite: InviteData, receiptId: String? = null): Boolean {
        val (_, myPubHex) = keyManager.ensureKeypair()
        if (invite.publicKeyHex == myPubHex) return false
        val receiptDao = database.contactAddReceiptDao()
        if (receiptId != null && receiptDao.get(receiptId)?.requestSentAt != null) {
            // Nothing left to do: the add and its track request already went out.
            receiptDao.delete(receiptId)
            return true
        }
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
        val sent = sendTrackRequest(invite)
        if (!sent) {
            if (existing == null) peerDao.deletePeerById(invite.deviceId) else peerDao.upsertPeer(existing)
            return false
        }
        if (receiptId != null) {
            val now = System.currentTimeMillis()
            receiptDao.upsert(ContactAddReceiptEntity(receiptId, requestSentAt = now))
            receiptDao.deleteAppliedBefore(now - RECEIPT_TTL_MS)
        }
        return true
    }

    /**
     * Apply only the request reviewed by the user, including after delayed supervision approval.
     *
     * [receiptId] (the queued intent's id) makes the accept resumable: if a previous attempt
     * committed but died before the reply went out, the receipt is found and only the
     * TRACK_ACCEPT is resent. The reply is at-least-once - a crash between sending it and
     * clearing the receipt can duplicate it - but it is never dropped silently: a failed send
     * keeps the receipt so the next attempt resends just the reply.
     */
    suspend fun completeInboundAccept(
        request: PendingRequestEntity,
        locationRole: String,
        messagingEnabled: Boolean,
        receiptId: String? = null
    ): Boolean {
        val receiptDao = database.contactAddReceiptDao()
        if (receiptId != null && receiptDao.get(receiptId) != null) {
            notificationManager.cancel(request.senderPubkey, NOTIF_ID_TRACK_REQUEST)
            // The receipt is the only proof the accept committed: keep it until the reply is
            // actually out, or a failed resend would drop the answer for good.
            if (!sendAccept(request, locationRole)) return false
            receiptDao.delete(receiptId)
            return true
        }
        val applied = applyInboundRequest(database, request, locationRole, messagingEnabled, receiptId)
        if (!applied) return false
        notificationManager.cancel(request.senderPubkey, NOTIF_ID_TRACK_REQUEST)
        if (!sendAccept(request, locationRole)) return false
        if (receiptId != null) receiptDao.delete(receiptId)
        return true
    }

    /** True while the receipt exists, i.e. the accept committed but its reply is not sent. */
    suspend fun hasReceipt(receiptId: String): Boolean =
        database.contactAddReceiptDao().get(receiptId) != null

    /** Returns false when the reply could not be published; the caller then keeps the receipt. */
    private suspend fun sendAccept(request: PendingRequestEntity, locationRole: String): Boolean =
        try {
            trackResponseSender.sendAccept(request.senderPubkey, request.senderRelayUrl, locationRole)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send track accept", e)
            false
        }

    /** TRACK_REQUEST builder (moved verbatim from ScanViewModel). Returns false when the
     *  request could not be built or handed to the relay client, so the add is reported as
     *  failed instead of claiming the contact was asked. */
    private suspend fun sendTrackRequest(target: InviteData): Boolean {
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
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send track request", e)
            return false
        }
    }
}
