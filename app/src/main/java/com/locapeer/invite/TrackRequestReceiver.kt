package com.locapeer.invite

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.entity.PeerEntity
import com.locapeer.nostr.NostrEvent
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.settings.AppPreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

const val ACTION_TRACK_ACCEPT = "com.locapeer.TRACK_ACCEPT"
const val ACTION_TRACK_DECLINE = "com.locapeer.TRACK_DECLINE"
const val EXTRA_SENDER_PUBKEY = "sender_pubkey"
const val EXTRA_SENDER_NAME = "sender_name"
const val EXTRA_SENDER_RELAY = "sender_relay"

@AndroidEntryPoint
class TrackRequestReceiver : BroadcastReceiver() {

    @Inject lateinit var peerDao: PeerDao
    @Inject lateinit var keyManager: KeyManager
    @Inject lateinit var crypto: CryptoUtils
    @Inject lateinit var relayClient: NostrRelayClient
    @Inject lateinit var prefs: AppPreferences

    private val json = Json { ignoreUnknownKeys = true }

    override fun onReceive(context: Context, intent: Intent) {
        val senderPubkey = intent.getStringExtra(EXTRA_SENDER_PUBKEY) ?: return
        val senderName = intent.getStringExtra(EXTRA_SENDER_NAME) ?: return
        val senderRelay = intent.getStringExtra(EXTRA_SENDER_RELAY) ?: return

        // Dismiss the notification
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(senderPubkey.hashCode() + 20000)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_TRACK_ACCEPT -> {
                        Log.d("TrackRequestReceiver", "Accepting track request from $senderName")
                        acceptTrackRequest(senderPubkey, senderName, senderRelay)
                        launch(Dispatchers.Main) {
                            Toast.makeText(context, "Accepted tracking request from $senderName", Toast.LENGTH_SHORT).show()
                        }
                    }
                    ACTION_TRACK_DECLINE -> {
                        Log.d("TrackRequestReceiver", "Declined track request from $senderName")
                        launch(Dispatchers.Main) {
                            Toast.makeText(context, "Declined tracking request from $senderName", Toast.LENGTH_SHORT).show()
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

    private suspend fun acceptTrackRequest(senderPubkey: String, senderName: String, senderRelay: String) {
        val existing = peerDao.getPeer(senderPubkey)
        val newRole = if (existing?.role == PeerEntity.ROLE_BROADCASTER || existing?.role == PeerEntity.ROLE_MUTUAL) {
            PeerEntity.ROLE_MUTUAL
        } else {
            PeerEntity.ROLE_SUBSCRIBER
        }

        val peer = PeerEntity(
            deviceId = senderPubkey,
            displayName = senderName,
            publicKeyHex = senderPubkey,
            relayUrl = senderRelay,
            role = newRole
        )
        peerDao.upsertPeer(peer)
        relayClient.connect(senderRelay)
        sendTrackAccept(senderPubkey, senderRelay)
        Log.i("TrackRequestReceiver", "Accepted track request from $senderName (Role: $newRole)")
    }

    private suspend fun sendTrackAccept(recipientPubkey: String, recipientRelay: String) {
        val (privHex, pubHex) = keyManager.ensureKeypair()
        val settings = prefs.settings.first()
        val myRelay = settings.customRelays.firstOrNull() ?: "wss://relay.daygle.net"
        val payload = TrackAcceptPayload(
            acceptorPublicKeyHex = pubHex,
            acceptorDisplayName = settings.displayName.ifBlank { "Someone" },
            acceptorDeviceId = pubHex,
            acceptorRelayUrl = myRelay
        )
        val encrypted = crypto.nip44Encrypt(
            crypto.hexToBytes(privHex),
            recipientPubkey,
            json.encodeToString(payload)
        )
        val event = NostrEvent.build(
            privKeyHex = privHex,
            pubKeyHex = pubHex,
            kind = NostrEventKind.TRACK_ACCEPT,
            content = encrypted,
            tags = listOf(listOf("p", recipientPubkey)),
            crypto = crypto
        )
        relayClient.publishEvent(event)
    }
}
