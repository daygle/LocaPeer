package com.locapeer.invite

import android.app.NotificationManager
import android.util.Log
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.dao.PendingRequestDao
import com.locapeer.data.entity.PeerEntity
import com.locapeer.data.entity.PendingRequestEntity
import com.locapeer.subscriber.NOTIF_ID_TRACK_REQUEST
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PendingRequestActions"

/**
 * Accepts or declines a stored incoming track request. Shared by the request review screen and
 * the pending-requests list so both apply the same contact update and response; the notification
 * decline action goes through [TrackRequestReceiver], which only ever declines.
 */
@Singleton
class PendingRequestActions @Inject constructor(
    private val peerDao: PeerDao,
    private val pendingRequestDao: PendingRequestDao,
    private val trackResponseSender: TrackResponseSender,
    private val notificationManager: NotificationManager,
) {
    /**
     * Adds (or updates) the requester as a contact with [locationRole], answers with a
     * TRACK_ACCEPT and clears the request. An existing contact keeps its name, archive state and
     * added-at time; only the role, messaging flag and relay change.
     */
    suspend fun accept(request: PendingRequestEntity, locationRole: String, messagingEnabled: Boolean) {
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
        // The contact is saved either way; a failed send (offline events are queued by the
        // relay client, so this is a key/crypto failure) must not crash the caller.
        try {
            trackResponseSender.sendAccept(request.senderPubkey, request.senderRelayUrl, locationRole)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send track accept", e)
        }
        pendingRequestDao.deleteByPubkey(request.senderPubkey)
    }

    /** Answers with a TRACK_DECLINE and clears the request. */
    suspend fun decline(request: PendingRequestEntity) {
        notificationManager.cancel(request.senderPubkey, NOTIF_ID_TRACK_REQUEST)
        try {
            trackResponseSender.sendDecline(request.senderPubkey, request.senderRelayUrl, request.isRoleChange)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send track decline", e)
        }
        pendingRequestDao.deleteByPubkey(request.senderPubkey)
    }
}
