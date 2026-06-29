package com.locapeer.invite

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.PendingRequestDao
import com.locapeer.nostr.NostrEvent
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.settings.AppPreferences
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val ACTION_TRACK_DECLINE = "com.locapeer.TRACK_DECLINE"
const val EXTRA_SENDER_PUBKEY = "sender_pubkey"
const val EXTRA_SENDER_NAME = "sender_name"
const val EXTRA_SENDER_RELAY = "sender_relay"
const val EXTRA_IS_ROLE_CHANGE = "is_role_change"
const val EXTRA_REQUESTED_ROLE = "requested_role"

@EntryPoint
@InstallIn(SingletonComponent::class)
interface TrackRequestReceiverEntryPoint {
    fun pendingRequestDao(): PendingRequestDao
    fun keyManager(): KeyManager
    fun relayClient(): NostrRelayClient
    fun crypto(): CryptoUtils
    fun prefs(): AppPreferences
}

class TrackRequestReceiver : BroadcastReceiver() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun onReceive(context: Context, intent: Intent) {
        val senderPubkey = intent.getStringExtra(EXTRA_SENDER_PUBKEY) ?: return
        val senderName = intent.getStringExtra(EXTRA_SENDER_NAME) ?: return
        val senderRelay = intent.getStringExtra(EXTRA_SENDER_RELAY) ?: ""
        val isRoleChange = intent.getBooleanExtra(EXTRA_IS_ROLE_CHANGE, false)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(senderPubkey.hashCode() + 20000)

        val ep = EntryPointAccessors
            .fromApplication(context.applicationContext, TrackRequestReceiverEntryPoint::class.java)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_TRACK_DECLINE -> {
                        Log.d("TrackRequestReceiver", "Declined track request from $senderName")
                        sendTrackDecline(ep, senderPubkey, senderRelay, isRoleChange)
                        ep.pendingRequestDao().deleteByPubkey(senderPubkey)
                        launch(Dispatchers.Main) {
                            Toast.makeText(context, "Declined contact request from $senderName", Toast.LENGTH_SHORT).show()
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

    private suspend fun sendTrackDecline(
        ep: TrackRequestReceiverEntryPoint,
        recipientPubkey: String,
        recipientRelay: String,
        isRoleChange: Boolean
    ) {
        try {
            val keyManager = ep.keyManager()
            val relayClient = ep.relayClient()
            val crypto = ep.crypto()
            val prefs = ep.prefs()
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
            if (recipientRelay.isNotBlank()) relayClient.connect(recipientRelay)
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
        } catch (e: Exception) {
            Log.w("TrackRequestReceiver", "Failed to send track decline", e)
        }
    }
}
