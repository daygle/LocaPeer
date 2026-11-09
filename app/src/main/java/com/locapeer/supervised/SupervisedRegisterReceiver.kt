package com.locapeer.supervised

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.locapeer.R
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

const val ACTION_SUPERVISED_REGISTER_ACCEPT = "com.locapeer.SUPERVISED_REGISTER_ACCEPT"
const val ACTION_SUPERVISED_REGISTER_DECLINE = "com.locapeer.SUPERVISED_REGISTER_DECLINE"
const val EXTRA_REQUESTER_PUBKEY = "requester_pubkey"
const val EXTRA_REQUESTER_NAME = "requester_name"
const val EXTRA_REQUESTER_RELAY = "requester_relay"
/** The request's own creation time (epoch seconds), echoed in the answer. */
const val EXTRA_REGISTER_REQUESTED_AT = "register_requested_at"

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SupervisedRegisterReceiverEntryPoint {
    /** Owns the durable record, the supervisor-side configuration and the answer's publish. */
    fun supervisionResponseSender(): SupervisionResponseSender
}

class SupervisedRegisterReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val requesterPubkey = intent.getStringExtra(EXTRA_REQUESTER_PUBKEY) ?: return
        val requesterName = intent.getStringExtra(EXTRA_REQUESTER_NAME) ?: return
        val requesterRelay = intent.getStringExtra(EXTRA_REQUESTER_RELAY) ?: ""
        val requestedAt = intent.getLongExtra(EXTRA_REGISTER_REQUESTED_AT, 0L).takeIf { it > 0 }

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(
            requesterPubkey,
            com.locapeer.subscriber.NOTIF_ID_SUPERVISED_REGISTER
        )

        val ep = EntryPointAccessors
            .fromApplication(context.applicationContext, SupervisedRegisterReceiverEntryPoint::class.java)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_SUPERVISED_REGISTER_ACCEPT -> {
                        Log.d("SupervisedRegisterReceiver", "Accepted supervised registration from $requesterName")
                        // Records the answer BEFORE the supervisor writes its own flags
                        // (forced missed-heartbeat alerts, receive-capable role), then
                        // configures, then publishes. A crash at any step leaves a record the
                        // next start finishes, so the device is never told about a supervision
                        // the supervisor did not commit to, and the answer is never lost.
                        ep.supervisionResponseSender().accept(requesterPubkey, requesterRelay, requestedAt)
                        launch(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.toast_now_supervising, requesterName), Toast.LENGTH_SHORT).show()
                        }
                    }
                    ACTION_SUPERVISED_REGISTER_DECLINE -> {
                        Log.d("SupervisedRegisterReceiver", "Declined supervised registration from $requesterName")
                        ep.supervisionResponseSender().decline(requesterPubkey, requesterRelay, requestedAt)
                        launch(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.toast_declined_supervision, requesterName), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("SupervisedRegisterReceiver", "Error handling supervised registration action", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

}
