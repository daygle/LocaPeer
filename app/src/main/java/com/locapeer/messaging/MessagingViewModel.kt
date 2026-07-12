package com.locapeer.messaging

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.CircleDao
import com.locapeer.data.dao.MessageDao
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.dao.PendingMessageDao
import com.locapeer.data.entity.CircleEntity
import com.locapeer.data.entity.DeliveryState
import com.locapeer.data.entity.MessageEntity
import com.locapeer.data.entity.PeerEntity
import java.util.UUID
import com.locapeer.nostr.NostrEvent
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrFilter
import com.locapeer.nostr.NostrRelayClient
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale
import javax.inject.Inject

enum class SortOrder { DATE, NAME, UNREAD }

@HiltViewModel
class MessagingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageDao: MessageDao,
    private val peerDao: PeerDao,
    private val circleDao: CircleDao,
    private val pendingMessageDao: PendingMessageDao,
    private val keyManager: KeyManager,
    private val crypto: CryptoUtils,
    private val relayClient: NostrRelayClient,
    private val peerManager: com.locapeer.peer.PeerManager
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }

    val peers: StateFlow<List<PeerEntity>> =
        peerDao.getAllPeers()
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _sortOrder = MutableStateFlow(SortOrder.DATE)
    val sortOrder: StateFlow<SortOrder> = _sortOrder

    val conversations: StateFlow<List<ConversationSummary>?> =
        combine(
            messageDao.getConversationSummaries(),
            peerDao.getAllPeers(),
            _searchQuery,
            _sortOrder,
            messageDao.getUnreadCountsPerPeer().map { rows -> rows.associate { it.peerId to it.cnt } }
        ) { msgs, peers, query, sort, unreadCounts ->
            val peerMap = peers.associateBy { it.deviceId }
            val base = msgs.mapNotNull { msg ->
                val peer = peerMap[msg.peerId] ?: return@mapNotNull null
                if (peer.isArchived) return@mapNotNull null
                ConversationSummary(peer = peer, lastMessage = msg)
            }
            
            val filtered = if (query.isBlank()) base
            else base.filter {
                it.peer.displayName.contains(query, ignoreCase = true) ||
                it.lastMessage.content.contains(query, ignoreCase = true)
            }
            
            when (sort) {
                SortOrder.DATE -> filtered.sortedByDescending { it.lastMessage.timestamp }
                SortOrder.NAME -> filtered.sortedBy { it.peer.displayName.lowercase(Locale.ROOT) }
                SortOrder.UNREAD -> filtered.sortedWith(
                    compareByDescending<ConversationSummary> { (unreadCounts[it.peer.deviceId] ?: 0) > 0 }
                        .thenByDescending { it.lastMessage.timestamp }
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val archivedConversations: StateFlow<List<ConversationSummary>> =
        messageDao.getConversationSummaries()
            .combine(peerDao.getAllPeers()) { msgs, peers ->
                val peerMap = peers.associateBy { it.deviceId }
                msgs.mapNotNull { msg ->
                    val peer = peerMap[msg.peerId] ?: return@mapNotNull null
                    if (!peer.isArchived) return@mapNotNull null
                    ConversationSummary(peer = peer, lastMessage = msg)
                }
            }
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun observePeer(peerId: String): Flow<PeerEntity?> = peerDao.observePeer(peerId)

    val unreadCounts: StateFlow<Map<String, Int>> =
        messageDao.getUnreadCountsPerPeer()
            .map { rows -> rows.associate { it.peerId to it.cnt } }
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

    /** Count of messages queued in the relay outbox (sent but not yet acknowledged by any
     *  relay). Surfaced on the chat list and AboutScreen so outbox backups are visible
     *  beyond the simple connected/disconnected dot. */
    val pendingMessageCount: StateFlow<Int> =
        pendingMessageDao.countAll()
            .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    private val _typingPeers = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** Maps peerDeviceId (= pubkey) to the millisecond timestamp of the last typing event. */
    val typingPeers: StateFlow<Map<String, Long>> = _typingPeers

    private val typingClearJobs = mutableMapOf<String, Job>()
    private val lastTypingSentAt = mutableMapOf<String, Long>()
    private var myListeningPubkey: String? = null
    private var eventsJob: Job? = null
    private var okEventsJob: Job? = null

    @SuppressLint("MissingPermission")
    fun sendLocation(peerId: String) {
        LocationServices.getFusedLocationProviderClient(context)
            .lastLocation
            .addOnSuccessListener { loc ->
                loc?.let {
                    val lat = String.format(Locale.US, "%.5f", it.latitude)
                    val lng = String.format(Locale.US, "%.5f", it.longitude)
                    val url = "https://www.openstreetmap.org/?mlat=$lat&mlon=$lng#map=16/$lat/$lng"
                    sendMessage(peerId, context.getString(com.locapeer.R.string.chat_my_location_message, url))
                }
            }
    }

    // ----- Circles (client-side groups) -----

    /** Group conversation rows, one per circle, newest activity first. */
    val groupConversations: StateFlow<List<GroupConversationSummary>> =
        combine(
            circleDao.observeCircles(),
            messageDao.getGroupConversationSummaries(),
            circleDao.observeMemberCounts(),
            messageDao.getUnreadCountsPerGroup().map { rows -> rows.associate { it.peerId to it.cnt } }
        ) { circles, lastMsgs, counts, unread ->
            val lastByGid = lastMsgs.associateBy { it.groupId }
            val countByGid = counts.associate { it.circleId to it.cnt }
            circles.map { c ->
                GroupConversationSummary(
                    circle = c,
                    lastMessage = lastByGid[c.id],
                    memberCount = countByGid[c.id] ?: 0,
                    unread = unread[c.id] ?: 0
                )
            }.sortedByDescending { it.lastMessage?.timestamp ?: it.circle.createdAt }
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Group messages share the 1:1 thread query because peerId holds the circle id. */
    fun getGroupMessages(circleId: String) = messageDao.getMessagesForPeer(circleId)

    fun observeCircle(circleId: String): Flow<CircleEntity?> = circleDao.observeCircle(circleId)

    fun markReadGroup(circleId: String) {
        viewModelScope.launch { messageDao.markAllReadForGroup(circleId) }
    }

    fun deleteGroupConversation(circleId: String) {
        viewModelScope.launch { messageDao.deleteAllForGroup(circleId) }
    }

    /**
     * Fan a message out to every circle member as an individually NIP-44-encrypted DM, then store
     * one local row in the circle thread. There is no shared group key: each member gets their own
     * ciphertext, and the envelope carries the member list so a recipient can materialise the circle.
     */
    fun sendGroupMessage(circleId: String, content: String) {
        viewModelScope.launch {
            try {
                val circle = circleDao.getCircle(circleId) ?: return@launch
                val members = circleDao.getMemberPubkeys(circleId)
                val (privHex, pubHex) = keyManager.ensureKeypair()
                val privBytes = crypto.hexToBytes(privHex)
                val fullMembers = (members + pubHex).distinct()
                val envelope = GroupWire.encode(
                    GroupMessage(
                        gid = circleId,
                        gname = circle.name,
                        members = fullMembers,
                        text = content,
                        creator = circle.creatorPubkey
                    )
                )
                withContext(Dispatchers.Default) {
                    members.filter { it != pubHex }.forEach { memberPub ->
                        try {
                            val encrypted = crypto.nip44Encrypt(privBytes, memberPub, envelope)
                            val event = NostrEvent.build(
                                privKeyHex = privHex,
                                pubKeyHex = pubHex,
                                kind = NostrEventKind.ENCRYPTED_DM,
                                content = encrypted,
                                tags = listOf(listOf("p", memberPub)),
                                crypto = crypto
                            )
                            relayClient.publishEvent(event)
                        } catch (e: Exception) {
                            Log.e("MessagingViewModel", "group send to $memberPub failed", e)
                        }
                    }
                }
                messageDao.insert(
                    MessageEntity(
                        id = UUID.randomUUID().toString(),
                        peerId = circleId,
                        senderPublicKeyHex = pubHex,
                        content = content,
                        timestamp = System.currentTimeMillis(),
                        isMine = true,
                        deliveryState = DeliveryState.SENT.name,
                        nostrEventId = "",
                        groupId = circleId
                    )
                )
            } catch (e: Exception) {
                Log.e("MessagingViewModel", "sendGroupMessage failed", e)
            }
        }
    }

    /** Share the current location with a whole circle: a group message whose body is an OSM pin URL. */
    @SuppressLint("MissingPermission")
    fun sendGroupLocation(circleId: String) {
        LocationServices.getFusedLocationProviderClient(context)
            .lastLocation
            .addOnSuccessListener { loc ->
                loc?.let {
                    val lat = String.format(Locale.US, "%.5f", it.latitude)
                    val lng = String.format(Locale.US, "%.5f", it.longitude)
                    val url = "https://www.openstreetmap.org/?mlat=$lat&mlon=$lng#map=16/$lat/$lng"
                    sendGroupMessage(circleId, context.getString(com.locapeer.R.string.chat_my_location_message, url))
                }
            }
    }

    fun getMessages(peerId: String) = messageDao.getMessagesForPeer(peerId)

    fun deleteMessage(msg: MessageEntity) {
        viewModelScope.launch { messageDao.delete(msg) }
    }

    fun deleteMessageFromRemote(msg: MessageEntity) {
        viewModelScope.launch {
            try {
                if (msg.nostrEventId.isEmpty()) return@launch
                val (privHex, pubHex) = keyManager.ensureKeypair()
                
                // NIP-09 Event Deletion
                val event = NostrEvent.build(
                    privKeyHex = privHex,
                    pubKeyHex = pubHex,
                    kind = NostrEventKind.EVENT_DELETION,
                    content = "Deleting message",
                    tags = listOf(listOf("e", msg.nostrEventId)),
                    crypto = crypto
                )
                relayClient.publishEvent(event)
            } catch (e: Exception) {
                android.util.Log.e("MessagingViewModel", "deleteMessageFromRemote failed", e)
            }
        }
    }

    fun deleteConversation(peerId: String) {
        viewModelScope.launch { messageDao.deleteAllForPeer(peerId) }
    }

    fun deleteConversationFromRemote(peerId: String) {
        // PeerManager owns the DELETE_MY_MESSAGES event (typed payload, error handling);
        // this previously duplicated it with a hand-rolled map payload.
        viewModelScope.launch { peerManager.sendDeleteMyMessages(peerId) }
    }

    fun archiveConversation(peerId: String, archived: Boolean) {
        viewModelScope.launch { peerDao.setArchived(peerId, archived, System.currentTimeMillis()) }
    }

    fun markRead(peerId: String) {
        viewModelScope.launch {
            val unread = messageDao.getUnreadFromPeer(peerId)
            messageDao.markAllReadForPeer(peerId)
            if (unread.isNotEmpty()) sendReadReceipt(peerId, unread.map { it.nostrEventId }.filter { it.isNotEmpty() })
        }
    }

    fun markReadMultiple(peerIds: List<String>) {
        peerIds.forEach { markRead(it) }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
    }

    /** Call on every keystroke in the chat input; throttles and sends one typing event per 3 s per peer. */
    fun onTyping(peerId: String) {
        val now = System.currentTimeMillis()
        val last = lastTypingSentAt[peerId] ?: 0L
        if (now - last > 3000L) {
            lastTypingSentAt[peerId] = now
            viewModelScope.launch { sendTypingEvent(peerId) }
        }
    }

    fun sendMessage(peerId: String, content: String) {
        viewModelScope.launch {
            try {
                val peer = peerDao.getPeer(peerId) ?: return@launch
                val (privHex, pubHex) = keyManager.ensureKeypair()
                val privBytes = crypto.hexToBytes(privHex)

                val event = withContext(Dispatchers.Default) {
                    val encrypted = crypto.nip44Encrypt(privBytes, peer.publicKeyHex, content)
                    val tags = listOf(listOf("p", peer.publicKeyHex))
                    NostrEvent.build(
                        privKeyHex = privHex,
                        pubKeyHex = pubHex,
                        kind = NostrEventKind.ENCRYPTED_DM,
                        content = encrypted,
                        tags = tags,
                        crypto = crypto
                    )
                }
                relayClient.publishEvent(event)
                
                // Auto-unarchive peer on send
                peerDao.unarchive(peerId)

                val msg = MessageEntity(
                    id = event.id,
                    peerId = peerId,
                    senderPublicKeyHex = pubHex,
                    content = content,
                    timestamp = System.currentTimeMillis(),
                    isMine = true,
                    deliveryState = DeliveryState.SENDING.name,
                    nostrEventId = event.id
                )
                messageDao.insert(msg)
            } catch (e: Exception) {
                android.util.Log.e("MessagingViewModel", "sendMessage failed", e)
            }
        }
    }

    fun startListening(myPubHex: String) {
        if (myListeningPubkey == myPubHex && eventsJob?.isActive == true) return
        myListeningPubkey = myPubHex
        viewModelScope.launch {
            // Only the two kinds the always-on HeartbeatReceiver does NOT handle are
            // subscribed here: TYPING (ephemeral, needs live UI handling) and EVENT_DELETION
            // (NIP-09). Incoming DMs, read receipts and delivery acks are stored/notified by
            // HeartbeatReceiver, and this screen reflects them through Room flows - handling
            // them here too was pure duplication (double acks, redundant work).
            val since = (System.currentTimeMillis() / 1000L) - (24 * 60 * 60)
            relayClient.subscribe(
                "lp-dm-${myPubHex.take(16)}",
                NostrFilter(
                    kinds = listOf(
                        NostrEventKind.TYPING,
                        NostrEventKind.EVENT_DELETION
                    ),
                    pTags = listOf(myPubHex),
                    since = since
                )
            )
        }

        eventsJob?.cancel()
        eventsJob = relayClient.events
            .onEach { event ->
              // Isolate each dispatch so a single malformed/hostile event can't cancel the
              // collector and stop all future message processing.
              try {
                when (event.kind) {
                    NostrEventKind.TYPING -> processTypingEvent(event)
                    NostrEventKind.EVENT_DELETION -> processDeletionEvent(event)
                }
              } catch (e: Exception) {
                Log.w("MessagingViewModel", "Failed to handle event ${event.id} (kind ${event.kind})", e)
              }
            }
            .launchIn(viewModelScope)

        // Relay OK → SENDING → SENT (relay confirmed it received the event)
        okEventsJob?.cancel()
        okEventsJob = relayClient.okEvents
            .onEach { confirmedEventId ->
                messageDao.updateDeliveryStateByNostrEventId(
                    confirmedEventId,
                    DeliveryState.SENT.name
                )
            }
            .launchIn(viewModelScope)
    }

    private fun processTypingEvent(event: NostrEvent) {
        val fromPubkey = event.pubkey
        viewModelScope.launch {
            val sender = peerDao.getPeer(fromPubkey) ?: return@launch  // only known peers
            if (!sender.messagingEnabled) return@launch  // suppress typing from disabled contacts
            // Verify the signature so an unrelated party can't forge a "contact is typing"
            // indicator by publishing an unsigned event carrying the contact's pubkey.
            // Schnorr verification is CPU-heavy, so keep it off the Main dispatcher.
            if (!withContext(Dispatchers.Default) { NostrEvent.verify(event, crypto) }) return@launch
            _typingPeers.update { it + (fromPubkey to System.currentTimeMillis()) }
            typingClearJobs[fromPubkey]?.cancel()
            typingClearJobs[fromPubkey] = viewModelScope.launch {
                delay(5_000)
                _typingPeers.update { it - fromPubkey }
            }
        }
    }

    private fun processDeletionEvent(event: NostrEvent) {
        viewModelScope.launch {
            if (!NostrEvent.verify(event, crypto)) return@launch
            val targetEventIds = event.tags.filter { it.firstOrNull() == "e" }.mapNotNull { it.getOrNull(1) }
            targetEventIds.forEach { eventId ->
                val msg = messageDao.getByNostrEventId(eventId)
                if (msg != null && msg.senderPublicKeyHex == event.pubkey) {
                    messageDao.delete(msg)
                }
            }
        }
    }

    private suspend fun sendReadReceipt(peerPubkey: String, eventIds: List<String>) {
        if (eventIds.isEmpty()) return
        val peer = peerDao.getPeer(peerPubkey) ?: return
        val (privHex, pubHex) = keyManager.ensureKeypair()
        val privBytes = crypto.hexToBytes(privHex)
        val payload = json.encodeToString(ReadReceiptPayload(eventIds))

        val event = withContext(Dispatchers.Default) {
            val encrypted = crypto.nip44Encrypt(privBytes, peer.publicKeyHex, payload)
            NostrEvent.build(
                privKeyHex = privHex,
                pubKeyHex = pubHex,
                kind = NostrEventKind.READ_RECEIPT,
                content = encrypted,
                tags = listOf(listOf("p", peer.publicKeyHex)),
                crypto = crypto
            )
        }
        relayClient.publishEvent(event)
    }

    private suspend fun sendTypingEvent(peerPubkey: String) {
        val peer = peerDao.getPeer(peerPubkey) ?: return
        val (privHex, pubHex) = keyManager.ensureKeypair()
        val privBytes = crypto.hexToBytes(privHex)

        val event = withContext(Dispatchers.Default) {
            val encrypted = crypto.nip44Encrypt(privBytes, peer.publicKeyHex, "{\"typing\":true}")
            NostrEvent.build(
                privKeyHex = privHex,
                pubKeyHex = pubHex,
                kind = NostrEventKind.TYPING,
                content = encrypted,
                tags = listOf(listOf("p", peer.publicKeyHex)),
                crypto = crypto
            )
        }
        relayClient.publishEvent(event)
    }

    override fun onCleared() {
        super.onCleared()
        myListeningPubkey?.let { pubkey ->
            relayClient.unsubscribe("lp-dm-${pubkey.take(16)}")
        }
    }
}

data class ConversationSummary(
    val peer: PeerEntity,
    val lastMessage: MessageEntity
)

data class GroupConversationSummary(
    val circle: CircleEntity,
    /** Null until the circle has at least one message. */
    val lastMessage: MessageEntity?,
    val memberCount: Int,
    val unread: Int
)
