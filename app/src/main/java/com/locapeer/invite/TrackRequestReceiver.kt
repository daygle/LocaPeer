package com.locapeer.invite

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.locapeer.R
import com.locapeer.data.dao.PendingRequestDao
import com.locapeer.data.entity.PendingRequestEntity
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

const val ACTION_TRACK_DECLINE = "com.locapeer.TRACK_DECLINE"
const val EXTRA_SENDER_PUBKEY = "sender_pubkey"
const val EXTRA_SENDER_NAME = "sender_name"
const val EXTRA_SENDER_RELAY = "sender_relay"
const val EXTRA_IS_ROLE_CHANGE = "is_role_change"
const val EXTRA_REQUESTED_ROLE = "requested_role"
const val EXTRA_REQUEST_RECEIVED_AT = "request_received_at"

@EntryPoint
@InstallIn(SingletonComponent::class)
interface TrackRequestReceiverEntryPoint {
    fun pendingRequestDao(): PendingRequestDao
    fun trackResponseSender(): TrackResponseSender
}

class TrackRequestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val senderPubkey = intent.getStringExtra(EXTRA_SENDER_PUBKEY) ?: return
        val senderName = intent.getStringExtra(EXTRA_SENDER_NAME) ?: return
        val senderRelay = intent.getStringExtra(EXTRA_SENDER_RELAY) ?: ""
        val isRoleChange = intent.getBooleanExtra(EXTRA_IS_ROLE_CHANGE, false)
        if (!intent.hasExtra(EXTRA_REQUEST_RECEIVED_AT)) return
        val request = PendingRequestEntity(
            senderPubkey = senderPubkey,
            senderName = senderName,
            senderRelayUrl = senderRelay,
            isRoleChange = isRoleChange,
            requestedRole = intent.getStringExtra(EXTRA_REQUESTED_ROLE),
            receivedAt = intent.getLongExtra(EXTRA_REQUEST_RECEIVED_AT, 0),
        )

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val ep = EntryPointAccessors
            .fromApplication(context.applicationContext, TrackRequestReceiverEntryPoint::class.java)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_TRACK_DECLINE -> {
                        // Only the request this notification was for: a replacement is not declined.
                        if (ep.pendingRequestDao().getByPubkey(senderPubkey) != request) return@launch
                        notificationManager.cancel(senderPubkey, com.locapeer.subscriber.NOTIF_ID_TRACK_REQUEST)
                        Log.d("TrackRequestReceiver", "Declined track request from $senderName")
                        // Record the decline BEFORE consuming the request, so a process death in
                        // between leaves the request answerable rather than gone with no answer.
                        try {
                            ep.trackResponseSender().sendDecline(senderPubkey, senderRelay, isRoleChange)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w("TrackRequestReceiver", "Failed to send track decline", e)
                        }
                        ep.pendingRequestDao().deleteIfUnchanged(request)
                        launch(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.toast_declined_contact, senderName), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("TrackRequestReceiver", "Error handling track action", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
