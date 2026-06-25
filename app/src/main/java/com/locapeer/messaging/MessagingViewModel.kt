package com.locapeer.messaging

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locapeer.MainActivity
import com.locapeer.R
import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.MessageDao
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.entity.DeliveryState
import com.locapeer.data.entity.MessageEntity
import com.locapeer.data.entity.PeerEntity
import com.locapeer.nostr.NostrEvent
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrFilter
import com.locapeer.nostr.NostrRelayClient
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class MessagingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageDao: MessageDao,
    private val peerDao: PeerDao,
    private val keyManager: KeyManager,
    private val crypto: CryptoUtils,
    private val relayClient: NostrRelayClient,
    private val notificationManager: NotificationManager
) : ViewModel() {

    val conversations: StateFlow<List<ConversationSummary>> =
        messageDao.getConversationSummaries()
            .combine(peerDao.getAllPeers()) { msgs, peers ->
                val peerMap = peers.associateBy { it.deviceId }
                msgs.mapNotNull { msg ->
                    val peer = peerMap[msg.peerId] ?: return@mapNotNull null
                    ConversationSummary(peer = peer, lastMessage = msg)
                }
            }
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun getUnreadCount(peerId: String) = messageDao.getUnreadCount(peerId)
    fun getMessages(peerId: String) = messageDao.getMessagesForPeer(peerId)

    fun markRead(peerId: String) {
        viewModelScope.launch { messageDao.markAllReadForPeer(peerId) }
    }

    fun sendMessage(peerId: String, content: String) {
        viewModelScope.launch {
            val peer = peerDao.getPeer(peerId) ?: return@launch
            val (privHex, pubHex) = keyManager.ensureKeypair()
            val privBytes = crypto.hexToBytes(privHex)

            val encrypted = crypto.nip04Encrypt(privBytes, peer.publicKeyHex, content)
            val tags = listOf(listOf("p", peer.publicKeyHex))
            val event = NostrEvent.build(
                privKeyHex = privHex,
                pubKeyHex = pubHex,
                kind = NostrEventKind.ENCRYPTED_DM,
                content = encrypted,
                tags = tags,
                crypto = crypto
            )
            relayClient.publishEvent(event)

            val msg = MessageEntity(
                id = UUID.randomUUID().toString(),
                peerId = peerId,
                senderPublicKeyHex = pubHex,
                content = content,
                timestamp = System.currentTimeMillis(),
                isMine = true,
                deliveryState = DeliveryState.SENT.name,
                nostrEventId = event.id
            )
            messageDao.insert(msg)
        }
    }

    fun startListening(myPubHex: String) {
        createMessageChannel()
        viewModelScope.launch {
            relayClient.subscribe(
                "locapeer-dm-$myPubHex",
                NostrFilter(
                    kinds = listOf(NostrEventKind.ENCRYPTED_DM),
                    pTags = listOf(myPubHex)
                )
            )
        }
        relayClient.events
            .filter { it.kind == NostrEventKind.ENCRYPTED_DM }
            .onEach { processIncomingDm(it) }
            .launchIn(viewModelScope)
    }

    private suspend fun processIncomingDm(event: NostrEvent) {
        if (messageDao.getByNostrEventId(event.id) != null) return
        val sender = peerDao.getPeer(event.pubkey) ?: return
        if (!NostrEvent.verify(event, crypto)) return

        val privHex = keyManager.getPrivateKeyHexBlocking() ?: return
        val plaintext = try {
            crypto.nip04Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }

        val msg = MessageEntity(
            id = UUID.randomUUID().toString(),
            peerId = event.pubkey,
            senderPublicKeyHex = event.pubkey,
            content = plaintext,
            timestamp = event.createdAt * 1000L,
            isMine = false,
            deliveryState = DeliveryState.DELIVERED.name,
            nostrEventId = event.id
        )
        messageDao.insert(msg)
        sendMessageNotification(sender, plaintext)
    }

    private fun sendMessageNotification(sender: PeerEntity, preview: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("openChat", sender.deviceId)
        }
        val pi = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, "locapeer_messages")
            .setSmallIcon(R.drawable.ic_notif_message)
            .setContentTitle(sender.displayName)
            .setContentText(preview.take(80))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(sender.deviceId.hashCode() + 10000, notification)
    }

    private fun createMessageChannel() {
        val channel = NotificationChannel(
            "locapeer_messages",
            context.getString(R.string.channel_name_messages),
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = context.getString(R.string.channel_desc_messages) }
        notificationManager.createNotificationChannel(channel)
    }
}

data class ConversationSummary(
    val peer: PeerEntity,
    val lastMessage: MessageEntity
)
