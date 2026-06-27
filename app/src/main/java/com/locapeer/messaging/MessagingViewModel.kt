package com.locapeer.messaging

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale
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

    private val json = Json { ignoreUnknownKeys = true }

    val relayStatus = relayClient.relayStatus

    val conversations: StateFlow<List<ConversationSummary>?> =
        messageDao.getConversationSummaries()
            .combine(peerDao.getAllPeers()) { msgs, peers ->
                val peerMap = peers.associateBy { it.deviceId }
                msgs.mapNotNull { msg ->
                    val peer = peerMap[msg.peerId] ?: return@mapNotNull null
                    ConversationSummary(peer = peer, lastMessage = msg)
                }
            }
            .stateIn(viewModelScope, SharingStarted.Lazily, null)

    private val _typingPeers = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** Maps peerDeviceId (= pubkey) to the millisecond timestamp of the last typing event. */
    val typingPeers: StateFlow<Map<String, Long>> = _typingPeers

    private val typingClearJobs = mutableMapOf<String, Job>()
    private var outgoingTypingJob: Job? = null
    private var myListeningPubkey: String? = null

    @SuppressLint("MissingPermission")
    fun sendLocation(peerId: String) {
        LocationServices.getFusedLocationProviderClient(context)
            .lastLocation
            .addOnSuccessListener { loc ->
                loc?.let {
                    val lat = String.format(Locale.US, "%.5f", it.latitude)
                    val lng = String.format(Locale.US, "%.5f", it.longitude)
                    sendMessage(peerId, "My current location: https://www.openstreetmap.org/?mlat=$lat&mlon=$lng#map=16/$lat/$lng")
                }
            }
    }

    fun getUnreadCount(peerId: String) = messageDao.getUnreadCount(peerId)
    fun getMessages(peerId: String) = messageDao.getMessagesForPeer(peerId)

    fun markRead(peerId: String) {
        viewModelScope.launch {
            val unread = messageDao.getUnreadFromPeer(peerId)
            messageDao.markAllReadForPeer(peerId)
            if (unread.isNotEmpty()) sendReadReceipt(peerId, unread.map { it.nostrEventId }.filter { it.isNotEmpty() })
        }
    }

    /** Call on every keystroke in the chat input; debounces and sends one typing event per 3 s. */
    fun onTyping(peerId: String) {
        outgoingTypingJob?.cancel()
        outgoingTypingJob = viewModelScope.launch {
            delay(500)
            sendTypingEvent(peerId)
        }
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
                deliveryState = DeliveryState.SENDING.name,
                nostrEventId = event.id
            )
            messageDao.insert(msg)
        }
    }

    fun startListening(myPubHex: String) {
        myListeningPubkey = myPubHex
        createMessageChannel()
        viewModelScope.launch {
            relayClient.subscribe(
                "locapeer-dm-$myPubHex",
                NostrFilter(
                    kinds = listOf(
                        NostrEventKind.ENCRYPTED_DM,
                        NostrEventKind.READ_RECEIPT,
                        NostrEventKind.TYPING,
                        NostrEventKind.DELIVERY_ACK
                    ),
                    pTags = listOf(myPubHex)
                )
            )
        }

        relayClient.events
            .onEach { event ->
                when (event.kind) {
                    NostrEventKind.ENCRYPTED_DM -> processIncomingDm(event)
                    NostrEventKind.READ_RECEIPT -> processReadReceipt(event)
                    NostrEventKind.TYPING -> processTypingEvent(event)
                    NostrEventKind.DELIVERY_ACK -> processDeliveryAck(event)
                }
            }
            .launchIn(viewModelScope)

        // Relay OK → SENDING → SENT (relay confirmed it received the event)
        relayClient.okEvents
            .onEach { confirmedEventId ->
                messageDao.updateDeliveryStateByNostrEventId(
                    confirmedEventId,
                    DeliveryState.SENT.name
                )
            }
            .launchIn(viewModelScope)
    }

    private suspend fun processIncomingDm(event: NostrEvent) {
        if (messageDao.getByNostrEventId(event.id) != null) return
        val sender = peerDao.getPeer(event.pubkey) ?: return
        if (!NostrEvent.verify(event, crypto)) return

        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip04Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }

        val msg = MessageEntity(
            id = event.id,
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

    private suspend fun processReadReceipt(event: NostrEvent) {
        peerDao.getPeer(event.pubkey) ?: return  // only handle receipts from known peers
        if (!NostrEvent.verify(event, crypto)) return
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip04Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }

        val receipt = try { json.decodeFromString<ReadReceiptPayload>(plaintext) } catch (e: Exception) { return }
        receipt.eventIds.forEach { eventId ->
            messageDao.updateDeliveryStateByNostrEventId(eventId, DeliveryState.READ.name)
        }
    }

    private fun processTypingEvent(event: NostrEvent) {
        val fromPubkey = event.pubkey
        viewModelScope.launch {
            peerDao.getPeer(fromPubkey) ?: return@launch  // only known peers
            _typingPeers.update { it + (fromPubkey to System.currentTimeMillis()) }
            typingClearJobs[fromPubkey]?.cancel()
            typingClearJobs[fromPubkey] = viewModelScope.launch {
                delay(5_000)
                _typingPeers.update { it - fromPubkey }
            }
        }
    }

    private suspend fun processDeliveryAck(event: NostrEvent) {
        peerDao.getPeer(event.pubkey) ?: return
        if (!NostrEvent.verify(event, crypto)) return
        val privHex = keyManager.getPrivateKeyHex() ?: return
        val plaintext = try {
            crypto.nip04Decrypt(crypto.hexToBytes(privHex), event.pubkey, event.content)
        } catch (e: Exception) { return }
        val payload = try { json.decodeFromString<DeliveryAckPayload>(plaintext) } catch (e: Exception) { return }
        messageDao.updateDeliveryStateByNostrEventId(payload.eventId, DeliveryState.DELIVERED.name)
    }

    private suspend fun sendReadReceipt(peerPubkey: String, eventIds: List<String>) {
        if (eventIds.isEmpty()) return
        val peer = peerDao.getPeer(peerPubkey) ?: return
        val (privHex, pubHex) = keyManager.ensureKeypair()
        val privBytes = crypto.hexToBytes(privHex)
        val payload = json.encodeToString(ReadReceiptPayload(eventIds))
        val encrypted = crypto.nip04Encrypt(privBytes, peer.publicKeyHex, payload)
        val event = NostrEvent.build(
            privKeyHex = privHex,
            pubKeyHex = pubHex,
            kind = NostrEventKind.READ_RECEIPT,
            content = encrypted,
            tags = listOf(listOf("p", peer.publicKeyHex)),
            crypto = crypto
        )
        relayClient.publishEvent(event)
    }

    private suspend fun sendTypingEvent(peerPubkey: String) {
        val peer = peerDao.getPeer(peerPubkey) ?: return
        val (privHex, pubHex) = keyManager.ensureKeypair()
        val privBytes = crypto.hexToBytes(privHex)
        val encrypted = crypto.nip04Encrypt(privBytes, peer.publicKeyHex, "{\"typing\":true}")
        val event = NostrEvent.build(
            privKeyHex = privHex,
            pubKeyHex = pubHex,
            kind = NostrEventKind.TYPING,
            content = encrypted,
            tags = listOf(listOf("p", peer.publicKeyHex)),
            crypto = crypto
        )
        relayClient.publishEvent(event)
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

    override fun onCleared() {
        super.onCleared()
        myListeningPubkey?.let { pubkey ->
            relayClient.unsubscribe("locapeer-dm-$pubkey")
        }
    }
}

data class ConversationSummary(
    val peer: PeerEntity,
    val lastMessage: MessageEntity
)
