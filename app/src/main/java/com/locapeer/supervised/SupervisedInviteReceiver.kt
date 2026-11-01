package com.locapeer.supervised

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.locapeer.R
import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.entity.PeerEntity
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

const val ACTION_SUPERVISED_INVITE_ACCEPT = "com.locapeer.SUPERVISED_INVITE_ACCEPT"
const val ACTION_SUPERVISED_INVITE_DECLINE = "com.locapeer.SUPERVISED_INVITE_DECLINE"
const val SUPERVISED_INVITE_SUPPRESS = "com.locapeer.SUPERVISED_INVITE_CANCELLED"

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SupervisedInviteReceiverEntryPoint {
    fun prefs(): AppPreferences
    fun supervisedModeManager(): SupervisedModeManager
    fun peerDao(): PeerDao
    fun keyManager(): KeyManager
    fun relayClient(): NostrRelayClient
    fun crypto(): CryptoUtils
}

/**
 * Notification actions for SUPERVISED_INVITE (kind 1061): a supervisor has asked to
 * supervise this device. Acceptance mirrors enableSupervisedMode (pending state +
 * role promotion + register request, which the supervisor auto-answers because it
 * already flagged the device); declining tells the supervisor in-band (kind 1062)
 * so its "Supervise" switch doesn't sit in bought state forever.
 */
class SupervisedInviteReceiver : BroadcastReceiver() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun onReceive(context: Context, intent: Intent) {
        val supervisorPubkey = intent.getStringExtra(EXTRA_REQUESTER_PUBKEY) ?: return
        val supervisorName = intent.getStringExtra(EXTRA_REQUESTER_NAME) ?: "Supervisor"

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.cancel(
            supervisorPubkey,
            com.locapeer.subscriber.NOTIF_ID_SUPERVISED_INVITE
        )

        val ep = EntryPointAccessors
            .fromApplication(context.applicationContext, SupervisedInviteReceiverEntryPoint::class.java)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_SUPERVISED_INVITE_ACCEPT -> {
                        Log.d("SupervisedInviteReceiver", "Accepted supervision invite from $supervisorName")
                        // Refuse if this device is already supervised or has a pending request:
                        // supervision is one-to-one, and accepting a second supervisor would
                        // silently orphan the first relationship.
                        val settings = ep.prefs().settings.first()
                        if (settings.supervisedModeEnabled) {
                            launch(Dispatchers.Main) {
                                Toast.makeText(context, context.getString(R.string.toast_already_supervised), Toast.LENGTH_SHORT).show()
                            }
                            sendInviteResponse(ep, supervisorPubkey, accepted = false)
                            return@launch
                        }
                        ep.prefs().setSupervisedMode(enabled = true, supervisorPubkey = supervisorPubkey)
                        // Supervision only works if the supervisor receives this device's
                        // location: promote the SEND role on their contact row (mirrors
                        // SettingsViewModel.enableSupervisedMode).
                        val supervisor = ep.peerDao().getPeer(supervisorPubkey)
                        if (supervisor != null) {
                            val promotedRole = PeerEntity.roleWithSend(supervisor.locationRole)
                            if (promotedRole != supervisor.locationRole) {
                                ep.peerDao().upsertPeer(supervisor.copy(locationRole = promotedRole))
                            }
                        }
                        // The supervisor has already flagged this device, so its register
                        // handler auto-answers with an accept - no extra notification there.
                        ep.supervisedModeManager().sendRegisterRequest(supervisorPubkey)
                        launch(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.toast_now_supervised, supervisorName), Toast.LENGTH_SHORT).show()
                        }
                    }
                    ACTION_SUPERVISED_INVITE_DECLINE -> {
                        Log.d("SupervisedInviteReceiver", "Declined supervision invite from $supervisorName")
                        sendInviteResponse(ep, supervisorPubkey, accepted = false)
                        launch(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.toast_declined_supervision_invite, supervisorName), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("SupervisedInviteReceiver", "Error handling supervision invite action", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun sendInviteResponse(
        ep: SupervisedInviteReceiverEntryPoint,
        supervisorPubkey: String,
        accepted: Boolean
    ) {
        try {
            val (privHex, pubHex) = ep.keyManager().ensureKeypair()
            val payload = json.encodeToString(
                SupervisedInviteResponsePayload(
                    devicePubkeyHex = pubHex,
                    accepted = accepted
                )
            )
            val encrypted = ep.crypto().nip44Encrypt(ep.crypto().hexToBytes(privHex), supervisorPubkey, payload)
            ep.relayClient().publishEvent(
                NostrEvent.build(
                    privKeyHex = privHex,
                    pubKeyHex = pubHex,
                    kind = NostrEventKind.SUPERVISED_INVITE_RESPONSE,
                    content = encrypted,
                    tags = listOf(listOf("p", supervisorPubkey)),
                    crypto = ep.crypto()
                )
            )
        } catch (e: Exception) {
            Log.w("SupervisedInviteReceiver", "Failed to send supervision invite response", e)
        }
    }
}
