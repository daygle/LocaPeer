package com.locapeer.subscriber

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.locapeer.MainActivity
import com.locapeer.R
import com.locapeer.beacon.HeartbeatPayload
import com.locapeer.beacon.PurgeRequestPayload
import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.HeartbeatDao
import com.locapeer.data.dao.MessageDao
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.entity.DeliveryState
import com.locapeer.data.entity.HeartbeatEntity
import com.locapeer.data.entity.MessageEntity
import com.locapeer.geofence.GeofenceEngine
import com.locapeer.messaging.DeliveryAckPayload
import com.locapeer.messaging.ReadReceiptPayload
import com.locapeer.proximity.ProximityEngine
import com.locapeer.peer.PeerManager
import com.locapeer.peer.PeerRemovedPayload
import com.locapeer.invite.ACTION_TRACK_ACCEPT
import com.locapeer.invite.ACTION_TRACK_DECLINE
import com.locapeer.invite.EXTRA_SENDER_NAME
import com.locapeer.invite.EXTRA_SENDER_PUBKEY
import com.locapeer.invite.EXTRA_SENDER_RELAY
import com.locapeer.invite.TrackRequestPayload
import com.locapeer.invite.TrackRequestReceiver
import com.locapeer.supervised.SupervisedModeManager
import com.locapeer.supervised.SupervisionApprovalManager
import com.locapeer.supervised.UnlockRequestPayload
import com.locapeer.supervised.UnlockResponsePayload
import com.locapeer.nostr.NostrEvent
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrFilter
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.settings.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "HeartbeatReceiver"
private const val CHANNEL_ID_ALERTS = "locapeer_alerts"
private const val CHANNEL_ID_MESSAGES = "locapeer_messages"
private const val SUB_ID = "lp-hb"

@Singleton
class HeartbeatReceiver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val relayClient: NostrRelayClient,
    private val keyManager: KeyManager,
    private val crypto: CryptoUtils,
    private val heartbeatDao: HeartbeatDao,
    private val messageDao: MessageDao,
    private val peerDao: PeerDao,
    private val prefs: AppPreferences,
    private val geofenceEngine: GeofenceEngine,
    private val proximityEngine: ProximityEngine,
    private val notificationManager: NotificationManager,
    private val supervisedModeManager: SupervisedModeManager,
    private val supervisionApprovalManager: SupervisionApprovalManager,
    private val sharingConfigDao: com.locapeer.data.dao.PeerSharingConfigDao,
    private val peerManager: PeerManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    fun stop() {
        scope.cancel()
    }

    fun start() {
        createAlertChannel()
        createMessageChannel()
        scope.launch {
            val (_, pubHex) = keyManager.ensureKeypair()
            relayClient.connect()
            relayClient.subscribe(
                SUB_ID,
                NostrFilter(
                    kinds = listOf(
                        NostrEventKind.HEARTBEAT,
                        NostrEventKind.SOS_ALERT,
                        NostrEventKind.PURGE_REQUEST,
                        NostrEventKind.MESSAGE_PURGE_REQUEST,
                        NostrEventKind.ENCRYPTED_DM,
                        NostrEventKind.READ_RECEIPT,
                        NostrEventKind.DELIVERY_ACK,
                        NostrEventKind.SUPERVISED_UNLOCK_REQUEST,
                        NostrEventKind.SUPERVISED_UNLOCK_RESPONSE,
                        NostrEventKind.TRACK_REQUEST,
                        NostrEventKind.TRACK_ACCEPT,
                        NostrEventKind.PEER_REMOVED,
                        NostrEventKind.DELETE_MY_MESSAGES,
                        NostrEventKind.DELETE_MY_LOCATION
                    ),
                    pTags = listOf(pubHex)
                )
            )
        }

        relayClient.events
            .onEach { event ->
                when (event.kind) {
                    NostrEventKind.HEARTBEAT, NostrEventKind.SOS_ALERT -> processEvent(event)
                    NostrEventKind.PURGE_REQUEST -> processPurgeRequest(event)
                    NostrEventKind.MESSAGE_PURGE_REQUEST -> processMsgPurgeRequest(event)
                    NostrEventKind.ENCRYPTED_DM -> processDmInBackground(event)
                    NostrEventKind.READ_RECEIPT -> processReadReceiptInBackground(event)
                    NostrEventKind.DELIVERY_ACK -> processDeliveryAckInBackground(event)
                    NostrEventKind.SUPERVISED_UNLOCK_REQUEST -> processUnlockRequest(event)
                    NostrEventKind.SUPERVISED_UNLOCK_RESPONSE -> processUnlockResponse(event)
                    NostrEventKind.TRACK_REQUEST -> scope.launch { processTrackRequest(event) }
                    NostrEventKind.TRACK_ACCEPT -> scope.launch { processTrackAccept(event) }
                    NostrEventKind.PEER_REMOVED -> scope.launch { processPeerRemoved(event) }
                    NostrEventKind.DELETE_MY_MESSAGES -> scope.launch { processDeleteMyMessages(event) }
                    NostrEventKind.DELETE_MY_LOCATION -> scope.launch { processDeleteMyLocation(event) }
                }
            }
            .launchIn(scope)
    }

    private suspend fun processMsgPurgeRequest(event: NostrEvent) {
        peerDao.getPeer(event.pubkey) ?: return
        if (!NostrEvent.verify(event, crypto)) return
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
        val payload = try { json.decodeFromString<PurgeRequestPayload>(plaintext) } catch (e: Exception) { return }
        if (payload.deviceId != event.pubkey) return
        messageDao.deleteOlderThanFromSender(payload.deviceId, payload.deleteOlderThanMs)
        Log.d(TAG, "Purged messages from ${payload.deviceId} before ${payload.deleteOlderThanMs}ms")
    }

    private suspend fun processPurgeRequest(event: NostrEvent) {
        peerDao.getPeer(event.pubkey) ?: return
        if (!NostrEvent.verify(event, crypto)) return
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
        val payload = try { json.decodeFromString<PurgeRequestPayload>(plaintext) } catch (e: Exception) { return }
        if (payload.deviceId != event.pubkey) return
        heartbeatDao.deleteOlderThanForDevice(payload.deviceId, payload.deleteOlderThanMs)
        Log.d(TAG, "Purged ${payload.deviceId} heartbeats before ${payload.deleteOlderThanMs}ms")
    }

    private suspend fun processEvent(event: NostrEvent) {
        if (event.kind != NostrEventKind.HEARTBEAT && event.kind != NostrEventKind.SOS_ALERT) return
        // Look up by deviceId first; fall back to publicKeyHex for peers stored before the
        // key-length fix (old peers had 128-char deviceId but correct 64-char publicKeyHex).
        val broadcaster = peerDao.getPeer(event.pubkey)
            ?: peerDao.getPeerByPublicKey(event.pubkey)
                ?.also { peer ->
                    // Migrate: re-save the peer with the canonical 64-char deviceId
                    peerDao.upsertPeer(peer.copy(deviceId = event.pubkey))
                    peerDao.deletePeerById(peer.deviceId)
                }
            ?: return
        if (!NostrEvent.verify(event, crypto)) {
            Log.w(TAG, "Signature verification failed for event ${event.id}")
            return
        }
        try {
            val privHex = keyManager.getPrivateKeyHex() ?: return
            val privBytes = crypto.hexToBytes(privHex)
            val plaintext = crypto.nip44Decrypt(privBytes, event.pubkey, event.content)
            val payload = json.decodeFromString<HeartbeatPayload>(plaintext)
            // Use event.pubkey (canonical 64-char Nostr pubkey) so the heartbeat always
            // matches the peer row regardless of what the payload.deviceId contains.
            val canonicalDeviceId = event.pubkey
            val prevHeartbeat = heartbeatDao.getLatestHeartbeat(canonicalDeviceId)
            val entity = HeartbeatEntity(
                deviceId = canonicalDeviceId,
                displayName = payload.displayName,
                timestamp = Instant.parse(payload.timestamp).toEpochMilli(),
                lat = payload.lat,
                lng = payload.lng,
                accuracy = payload.accuracy,
                battery = payload.battery,
                motionState = payload.motionState,
                isSos = payload.isSos
            )
            heartbeatDao.insert(entity)
            if (payload.retentionDays > 0) {
                val cutoff = System.currentTimeMillis() - payload.retentionDays * 24 * 3600 * 1000L
                heartbeatDao.deleteOlderThanForDevice(canonicalDeviceId, cutoff)
            }
            if (payload.isSos) sendSosNotification(broadcaster.displayName, payload)
            geofenceEngine.evaluate(entity, prevHeartbeat)
            proximityEngine.evaluate(entity)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process heartbeat", e)
        }
    }

    private fun sendSosNotification(name: String, payload: HeartbeatPayload) {
        val intent = Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setSmallIcon(R.drawable.ic_notif_alert)
            .setContentTitle("SOS from $name!")
            .setContentText("${name} has activated SOS at (${payload.lat}, ${payload.lng})")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(payload.deviceId.hashCode(), notification)
    }

    private suspend fun processDmInBackground(event: NostrEvent) {
        if (messageDao.getByNostrEventId(event.id) != null) return
        val sender = peerDao.getPeer(event.pubkey) ?: return
        val cfg = sharingConfigDao.getForPeer(event.pubkey)
        val isBlocked = cfg?.messagingEnabled == false
        if (!NostrEvent.verify(event, crypto)) return
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
        val msg = MessageEntity(
            id = event.id,
            peerId = event.pubkey,
            senderPublicKeyHex = event.pubkey,
            content = plaintext,
            timestamp = event.createdAt * 1000L,
            isMine = false,
            deliveryState = if (isBlocked) DeliveryState.SENT.name else DeliveryState.DELIVERED.name,
            nostrEventId = event.id,
            isBlocked = isBlocked
        )
        messageDao.insert(msg)
        if (!isBlocked) {
            sendBackgroundMessageNotification(sender.displayName, plaintext, event.pubkey)
            sendDeliveryAck(event.pubkey, event.id)
        }
    }

    private suspend fun processReadReceiptInBackground(event: NostrEvent) {
        peerDao.getPeer(event.pubkey) ?: return
        if (!NostrEvent.verify(event, crypto)) return
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
        val receipt = try { json.decodeFromString<ReadReceiptPayload>(plaintext) } catch (e: Exception) { return }
        receipt.eventIds.forEach { eventId ->
            messageDao.updateDeliveryStateByNostrEventId(eventId, DeliveryState.READ.name)
        }
    }

    private suspend fun processDeliveryAckInBackground(event: NostrEvent) {
        peerDao.getPeer(event.pubkey) ?: return
        if (!NostrEvent.verify(event, crypto)) return
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
        val payload = try { json.decodeFromString<DeliveryAckPayload>(plaintext) } catch (e: Exception) { return }
        messageDao.updateDeliveryStateByNostrEventId(payload.eventId, DeliveryState.DELIVERED.name)
    }

    private suspend fun processUnlockRequest(event: NostrEvent) {
        peerDao.getPeer(event.pubkey) ?: return
        if (!NostrEvent.verify(event, crypto)) return
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
        val payload = try { json.decodeFromString<UnlockRequestPayload>(plaintext) } catch (e: Exception) { return }
        supervisionApprovalManager.setPending(
            SupervisionApprovalManager.PendingRequest(
                fromPubkey = event.pubkey,
                deviceName = payload.deviceName,
                requestId = payload.requestId
            )
        )
    }

    private suspend fun processUnlockResponse(event: NostrEvent) {
        peerDao.getPeer(event.pubkey) ?: return
        if (!NostrEvent.verify(event, crypto)) return
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
        val payload = try { json.decodeFromString<UnlockResponsePayload>(plaintext) } catch (e: Exception) { return }
        supervisedModeManager.handleResponse(payload.requestId, payload.approved)
    }

    private suspend fun sendDeliveryAck(toPubkey: String, deliveredEventId: String) {
        val (privHex, pubHex) = keyManager.ensureKeypair()
        val privBytes = crypto.hexToBytes(privHex)
        val payload = json.encodeToString(DeliveryAckPayload(deliveredEventId))
        val encrypted = crypto.nip44Encrypt(privBytes, toPubkey, payload)
        val event = NostrEvent.build(
            privKeyHex = privHex,
            pubKeyHex = pubHex,
            kind = NostrEventKind.DELIVERY_ACK,
            content = encrypted,
            tags = listOf(listOf("p", toPubkey)),
            crypto = crypto
        )
        relayClient.publishEvent(event)
    }

    private fun sendBackgroundMessageNotification(senderName: String, preview: String, peerId: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("openChat", peerId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(context, peerId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_MESSAGES)
            .setSmallIcon(R.drawable.ic_notif_message)
            .setContentTitle(senderName)
            .setContentText(preview.take(80))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(peerId.hashCode() + 10000, notification)
    }

    private suspend fun processPeerRemoved(event: NostrEvent) {
        // Only act if we know this peer — ignore unknown senders
        peerDao.getPeer(event.pubkey) ?: return
        if (!NostrEvent.verify(event, crypto)) return
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
        try {
            json.decodeFromString<PeerRemovedPayload>(plaintext)
        } catch (e: Exception) { return }
        peerManager.handleRemovalByPeer(event.pubkey)
    }

    private suspend fun processDeleteMyMessages(event: NostrEvent) {
        peerDao.getPeer(event.pubkey) ?: return
        if (!NostrEvent.verify(event, crypto)) return
        val privHex = keyManager.getPrivateKeyHex() ?: return
        try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
        peerManager.handleDeleteMyMessages(event.pubkey)
    }

    private suspend fun processDeleteMyLocation(event: NostrEvent) {
        peerDao.getPeer(event.pubkey) ?: return
        if (!NostrEvent.verify(event, crypto)) return
        val privHex = keyManager.getPrivateKeyHex() ?: return
        try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
        peerManager.handleDeleteMyLocation(event.pubkey)
    }

    private suspend fun processTrackRequest(event: NostrEvent) {
        if (!NostrEvent.verify(event, crypto)) return
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
        val payload = try { json.decodeFromString<TrackRequestPayload>(plaintext) } catch (e: Exception) { return }

        // Don't prompt if we're already tracking this person
        if (peerDao.getPeer(event.pubkey) != null) return

        val notifId = event.pubkey.hashCode() + 20000
        val acceptIntent = Intent(context, TrackRequestReceiver::class.java).apply {
            action = ACTION_TRACK_ACCEPT
            putExtra(EXTRA_SENDER_PUBKEY, payload.senderPublicKeyHex)
            putExtra(EXTRA_SENDER_NAME, payload.senderDisplayName)
            putExtra(EXTRA_SENDER_RELAY, payload.senderRelayUrl)
        }
        val declineIntent = Intent(context, TrackRequestReceiver::class.java).apply {
            action = ACTION_TRACK_DECLINE
            putExtra(EXTRA_SENDER_PUBKEY, payload.senderPublicKeyHex)
            putExtra(EXTRA_SENDER_NAME, payload.senderDisplayName)
            putExtra(EXTRA_SENDER_RELAY, payload.senderRelayUrl)
        }
        val acceptPi = PendingIntent.getBroadcast(context, notifId, acceptIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val declinePi = PendingIntent.getBroadcast(context, notifId + 1, declineIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setSmallIcon(R.drawable.ic_notif_message)
            .setContentTitle("Track request from ${payload.senderDisplayName}")
            .setContentText("${payload.senderDisplayName} wants to share their location with you.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(0, "Accept", acceptPi)
            .addAction(0, "Decline", declinePi)
            .build()
        notificationManager.notify(notifId, notification)
    }

    private suspend fun processTrackAccept(event: NostrEvent) {
        if (!NostrEvent.verify(event, crypto)) return
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip44Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
        val payload = try { json.decodeFromString<com.locapeer.invite.TrackAcceptPayload>(plaintext) } catch (e: Exception) { return }

        // Add the acceptor as a BROADCASTER so we can see their location
        val peer = com.locapeer.data.entity.PeerEntity(
            deviceId = payload.acceptorPublicKeyHex,
            displayName = payload.acceptorDisplayName,
            publicKeyHex = payload.acceptorPublicKeyHex,
            relayUrl = payload.acceptorRelayUrl,
            role = "BROADCASTER"
        )
        peerDao.upsertPeer(peer)
        relayClient.connect(payload.acceptorRelayUrl)
        Log.i(TAG, "${payload.acceptorDisplayName} accepted your track request")
    }

    private fun createAlertChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID_ALERTS,
            context.getString(R.string.channel_name_alerts),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_desc_alerts)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun createMessageChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID_MESSAGES,
            context.getString(R.string.channel_name_messages),
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = context.getString(R.string.channel_desc_messages) }
        notificationManager.createNotificationChannel(channel)
    }
}
