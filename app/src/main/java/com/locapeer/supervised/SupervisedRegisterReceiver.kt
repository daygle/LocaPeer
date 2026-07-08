package com.locapeer.supervised

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.locapeer.R
import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.PeerSharingConfigDao
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

const val ACTION_SUPERVISED_REGISTER_ACCEPT = "com.locapeer.SUPERVISED_REGISTER_ACCEPT"
const val ACTION_SUPERVISED_REGISTER_DECLINE = "com.locapeer.SUPERVISED_REGISTER_DECLINE"
const val EXTRA_REQUESTER_PUBKEY = "requester_pubkey"
const val EXTRA_REQUESTER_NAME = "requester_name"
const val EXTRA_REQUESTER_RELAY = "requester_relay"

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SupervisedRegisterReceiverEntryPoint {
    fun sharingConfigDao(): PeerSharingConfigDao
    fun keyManager(): KeyManager
    fun relayClient(): NostrRelayClient
    fun crypto(): CryptoUtils
    fun prefs(): AppPreferences
}

class SupervisedRegisterReceiver : BroadcastReceiver() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun onReceive(context: Context, intent: Intent) {
        val requesterPubkey = intent.getStringExtra(EXTRA_REQUESTER_PUBKEY) ?: return
        val requesterName = intent.getStringExtra(EXTRA_REQUESTER_NAME) ?: return
        val requesterRelay = intent.getStringExtra(EXTRA_REQUESTER_RELAY) ?: ""

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
                        val cfg = ep.sharingConfigDao().getForPeer(requesterPubkey)
                        if (cfg != null) {
                            ep.sharingConfigDao().setIsMySupervised(requesterPubkey, true)
                        } else {
                            ep.sharingConfigDao().upsert(
                                com.locapeer.data.entity.PeerSharingConfig(
                                    peerDeviceId = requesterPubkey,
                                    isMySupervised = true
                                )
                            )
                        }
                        sendRegisterResponse(ep, requesterPubkey, requesterRelay, accepted = true)
                        launch(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.toast_now_supervising, requesterName), Toast.LENGTH_SHORT).show()
                        }
                    }
                    ACTION_SUPERVISED_REGISTER_DECLINE -> {
                        Log.d("SupervisedRegisterReceiver", "Declined supervised registration from $requesterName")
                        sendRegisterResponse(ep, requesterPubkey, requesterRelay, accepted = false)
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

    private suspend fun sendRegisterResponse(
        ep: SupervisedRegisterReceiverEntryPoint,
        requesterPubkey: String,
        requesterRelay: String,
        accepted: Boolean
    ) {
        try {
            val keyManager = ep.keyManager()
            val relayClient = ep.relayClient()
            val crypto = ep.crypto()
            val (privHex, pubHex) = keyManager.ensureKeypair()
            val payload = json.encodeToString(
                SupervisedRegisterResponsePayload(
                    devicePubkeyHex = requesterPubkey,
                    accepted = accepted
                )
            )
            val encrypted = crypto.nip44Encrypt(
                crypto.hexToBytes(privHex),
                requesterPubkey,
                payload
            )
            if (requesterRelay.isNotBlank()) relayClient.connect(requesterRelay)
            relayClient.publishEvent(
                NostrEvent.build(
                    privKeyHex = privHex,
                    pubKeyHex = pubHex,
                    kind = if (accepted) NostrEventKind.SUPERVISED_REGISTER_ACCEPT
                           else NostrEventKind.SUPERVISED_REGISTER_DECLINE,
                    content = encrypted,
                    tags = listOf(listOf("p", requesterPubkey)),
                    crypto = crypto
                )
            )
        } catch (e: Exception) {
            Log.w("SupervisedRegisterReceiver", "Failed to send supervised register response", e)
        }
    }
}
