package com.locapeer.invite

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

const val ACTION_TRACK_DECLINE = "com.locapeer.TRACK_DECLINE"
const val EXTRA_SENDER_PUBKEY = "sender_pubkey"
const val EXTRA_SENDER_NAME = "sender_name"
const val EXTRA_SENDER_RELAY = "sender_relay"
const val EXTRA_IS_ROLE_CHANGE = "is_role_change"
const val EXTRA_REQUESTED_ROLE = "requested_role"

class TrackRequestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val senderPubkey = intent.getStringExtra(EXTRA_SENDER_PUBKEY) ?: return
        val senderName = intent.getStringExtra(EXTRA_SENDER_NAME) ?: return

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(senderPubkey.hashCode() + 20000)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_TRACK_DECLINE -> {
                        Log.d("TrackRequestReceiver", "Declined track request from $senderName")
                        launch(Dispatchers.Main) {
                            Toast.makeText(context, "Declined location sharing from $senderName", Toast.LENGTH_SHORT).show()
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
