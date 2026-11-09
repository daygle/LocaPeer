package com.locapeer.invite

import android.app.NotificationManager
import android.util.Log
import com.locapeer.data.dao.PendingRequestDao
import com.locapeer.data.entity.PendingRequestEntity
import com.locapeer.settings.AppPreferences
import com.locapeer.subscriber.NOTIF_ID_TRACK_REQUEST
import com.locapeer.supervised.ContactAddApprovalManager
import kotlinx.coroutines.flow.first
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
    private val pendingRequestDao: PendingRequestDao,
    private val trackResponseSender: TrackResponseSender,
    private val notificationManager: NotificationManager,
    private val prefs: AppPreferences,
    private val contactAddApprovalManager: ContactAddApprovalManager,
    private val executor: ContactAddExecutor,
) {
    /**
     * Adds (or updates) the requester as a contact with [locationRole], answers with a
     * TRACK_ACCEPT and clears the request.
     *
     * While supervised, accepting an inbound track request establishes a NEW sharing
     * destination, so it goes through the supervisor's per-action approval queue (kind
     * 1067/1068): the queued intent is durable, and the actual accept is executed by
     * [ContactAddExecutor] once the supervisor answers (or straight away when unsupervised).
     */
    suspend fun accept(request: PendingRequestEntity, locationRole: String, messagingEnabled: Boolean) {
        if (prefs.settings.first().supervisionActive) {
            contactAddApprovalManager.requestAdd(
                contactPubkeyHex = request.senderPubkey,
                contactName = request.senderName,
                action = ContactAddApprovalManager.AddAction.InboundAccept(
                    request = request,
                    locationRole = locationRole,
                    messagingEnabled = messagingEnabled
                ),
            ) { /* UI feedback comes from the queue badge + Room flows; no screen callback */ }
            return
        }
        executor.completeInboundAccept(request, locationRole, messagingEnabled)
    }

    /** Answers with a TRACK_DECLINE and clears the request. */
    suspend fun decline(request: PendingRequestEntity) {
        if (pendingRequestDao.deleteIfUnchanged(request) == 0) return
        notificationManager.cancel(request.senderPubkey, NOTIF_ID_TRACK_REQUEST)
        try {
            // Recorded before publishing and replayed until it is handed to the relay client:
            // a decline lost here would leave the requester waiting on the answer for good.
            trackResponseSender.sendDecline(request.senderPubkey, request.senderRelayUrl, request.isRoleChange)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record track decline", e)
        }
    }
}
