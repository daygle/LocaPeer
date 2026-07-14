package com.locapeer.messaging

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.util.Log
import com.locapeer.data.entity.MessageType
import java.io.File
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
import androidx.core.content.FileProvider
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
    private val peerManager: com.locapeer.peer.PeerManager,
    private val prefs: com.locapeer.settings.AppPreferences
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }

    val peers: StateFlow<List<PeerEntity>> =
        peerDao.getAllPeers()
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /**
     * This device's own pubkey. Used by the circle screens to tell whether the local user owns a
     * circle (`circle.creatorPubkey == myPubkeyHex`); only the owner may rename it or change its
     * membership. Empty until the keypair loads.
     */
    val myPubkeyHex: StateFlow<String> =
        flow { emit(keyManager.ensureKeypair().second) }
            .stateIn(viewModelScope, SharingStarted.Lazily, "")

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
        combine(
            messageDao.getConversationSummaries(),
            peerDao.getAllPeers(),
            _searchQuery,
            _sortOrder,
            messageDao.getUnreadCountsPerPeer().map { rows -> rows.associate { it.peerId to it.cnt } }
        ) { msgs, peers, query, sort, unreadCounts ->
            // Mirrors the [conversations] search/sort pipeline exactly, but keeps only archived
            // peers, so the Archived tab offers the same search-and-sort affordances as Chats.
            val peerMap = peers.associateBy { it.deviceId }
            val base = msgs.mapNotNull { msg ->
                val peer = peerMap[msg.peerId] ?: return@mapNotNull null
                if (!peer.isArchived) return@mapNotNull null
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
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun observePeer(peerId: String): Flow<PeerEntity?> = peerDao.observePeer(peerId)

    val unreadCounts: StateFlow<Map<String, Int>> =
        messageDao.getUnreadCountsPerPeer()
            .map { rows -> rows.associate { it.peerId to it.cnt } }
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

    /**
     * Total unread across all non-archived 1:1 chats, driving the badge on the Chats sub-tab so
     * unread on a tab the user isn't currently viewing is still visible. Computed from the raw DAO
     * flows (not the search-filtered [conversations]) so the badge reflects the true tab state
     * regardless of any active search.
     */
    val chatsUnreadTotal: StateFlow<Int> =
        combine(peerDao.getAllPeers(), messageDao.getUnreadCountsPerPeer()) { peers, rows ->
            val archived = peers.filter { it.isArchived }.map { it.deviceId }.toSet()
            rows.filterNot { it.peerId in archived }.sumOf { it.cnt }
        }.stateIn(viewModelScope, SharingStarted.Lazily, 0)

    /** Total unread across all non-archived circles, driving the badge on the Circles sub-tab. */
    val circlesUnreadTotal: StateFlow<Int> =
        combine(circleDao.observeCircles(), messageDao.getUnreadCountsPerGroup()) { circles, rows ->
            val active = circles.filterNot { it.isArchived }.map { it.id }.toSet()
            rows.filter { it.peerId in active }.sumOf { it.cnt }
        }.stateIn(viewModelScope, SharingStarted.Lazily, 0)

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

    /**
     * Group conversation rows, one per non-archived circle, filtered by [_searchQuery]
     * and ordered by [_sortOrder]. Mirrors exactly how [conversations] threads query
     * and sort for 1:1 chats, so the Circles / Archived tabs can offer the same
     * search-and-sort affordances as the Chats tab.
     *
     * Nullable so callers can distinguish LOADING (Room has not emitted yet) from
     * EMPTY (no circles exist yet). See [com.locapeer.messaging.ConversationListScreen].
     */
    val groupConversations: StateFlow<List<GroupConversationSummary>?> =
        combine(
            // Stage 1: build the unfiltered, unsorted per-circle summary rows from the
            // underlying DAO flows. The standard typed `combine` overload tops out at 5
            // arguments and adding `_searchQuery` + `_sortOrder` here would exceed that,
            // so the search/sort stage is chained as a second combine below instead.
            combine(
                circleDao.observeCircles(),
                messageDao.getGroupConversationSummaries(),
                circleDao.observeMemberCounts(),
                messageDao.getUnreadCountsPerGroup().map { rows -> rows.associate { it.peerId to it.cnt } }
            ) { circles, lastMsgs, counts, unread ->
                val lastByGid = lastMsgs.associateBy { it.groupId }
                val countByGid = counts.associate { it.circleId to it.cnt }
                circles.filterNot { it.isArchived }.map { c ->
                    GroupConversationSummary(
                        circle = c,
                        lastMessage = lastByGid[c.id],
                        memberCount = countByGid[c.id] ?: 0,
                        unread = unread[c.id] ?: 0
                    )
                }
            },
            _searchQuery,
            _sortOrder,
        ) { list, query, sort ->
            // Search matches the circle name OR the last-message preview (mirrors how
            // 1:1 chat search matches `displayName` + `lastMessage.content`). `lastMessage`
            // is nullable, hence the explicit null-safe chain.
            val filtered = if (query.isBlank()) list
            else list.filter {
                it.circle.name.contains(query, ignoreCase = true) ||
                    (it.lastMessage?.content?.contains(query, ignoreCase = true) ?: false)
            }
            when (sort) {
                SortOrder.DATE -> filtered.sortedByDescending {
                    it.lastMessage?.timestamp ?: it.circle.createdAt
                }
                SortOrder.NAME -> filtered.sortedBy { it.circle.name.lowercase(Locale.ROOT) }
                SortOrder.UNREAD -> filtered.sortedWith(
                    compareByDescending<GroupConversationSummary> { it.unread > 0 }
                        .thenByDescending { it.lastMessage?.timestamp ?: it.circle.createdAt }
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    /** Archived circles, shown on the Archived tab alongside archived 1:1 conversations. Applies
     *  the same search/sort pipeline as [groupConversations] so the Archived tab's search-and-sort
     *  covers circles too. Two-stage combine because the base combine already uses 4 flows and
     *  adding query + sort would exceed the typed `combine` overload's 5-argument limit. */
    val archivedGroupConversations: StateFlow<List<GroupConversationSummary>> =
        combine(
            combine(
                circleDao.observeCircles(),
                messageDao.getGroupConversationSummaries(),
                circleDao.observeMemberCounts(),
                messageDao.getUnreadCountsPerGroup().map { rows -> rows.associate { it.peerId to it.cnt } }
            ) { circles, lastMsgs, counts, unread ->
                val lastByGid = lastMsgs.associateBy { it.groupId }
                val countByGid = counts.associate { it.circleId to it.cnt }
                circles.filter { it.isArchived }.map { c ->
                    GroupConversationSummary(
                        circle = c,
                        lastMessage = lastByGid[c.id],
                        memberCount = countByGid[c.id] ?: 0,
                        unread = unread[c.id] ?: 0
                    )
                }
            },
            _searchQuery,
            _sortOrder,
        ) { list, query, sort ->
            val filtered = if (query.isBlank()) list
            else list.filter {
                it.circle.name.contains(query, ignoreCase = true) ||
                    (it.lastMessage?.content?.contains(query, ignoreCase = true) ?: false)
            }
            when (sort) {
                SortOrder.DATE -> filtered.sortedByDescending { it.lastMessage?.timestamp ?: it.circle.createdAt }
                SortOrder.NAME -> filtered.sortedBy { it.circle.name.lowercase(Locale.ROOT) }
                SortOrder.UNREAD -> filtered.sortedWith(
                    compareByDescending<GroupConversationSummary> { it.unread > 0 }
                        .thenByDescending { it.lastMessage?.timestamp ?: it.circle.createdAt }
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Archive/unarchive a circle - the group counterpart of [archiveConversation]. */
    fun archiveCircle(circleId: String, archived: Boolean) {
        viewModelScope.launch { circleDao.setArchived(circleId, archived, System.currentTimeMillis()) }
    }

    /** Deletes a circle, its membership and its whole message thread (local only - a circle is a
     *  client-side grouping, so there is no remote circle object to delete). */
    fun deleteCircleAndConversation(circleId: String) {
        viewModelScope.launch {
            messageDao.deleteAllForGroup(circleId)
            circleDao.clearMembers(circleId)
            circleDao.deleteCircle(circleId)
            MediaCache.clearDecryptedMedia(context)
        }
    }

    /**
     * Leave a circle the local user does not own. Notifies the owner (who drops us from the circle's
     * membership so we stop receiving it), optionally deletes the messages we sent to the circle from
     * every member's device (NIP-09, sender-only), records that we left so a straggler message can't
     * silently re-create the circle, then removes our local copy.
     *
     * If we own the circle or it has no recorded owner there is nobody authoritative to notify, so
     * this falls back to a plain local delete (owners disband via [deleteCircleAndConversation]).
     */
    fun leaveCircle(circleId: String, alsoDeleteRemote: Boolean) {
        viewModelScope.launch {
            try {
                val circle = circleDao.getCircle(circleId) ?: return@launch
                val (privHex, pubHex) = keyManager.ensureKeypair()
                val owner = circle.creatorPubkey
                if (owner.isBlank() || owner == pubHex) {
                    messageDao.deleteAllForGroup(circleId)
                    circleDao.clearMembers(circleId)
                    circleDao.deleteCircle(circleId)
                    return@launch
                }
                // Tell the owner we left so they remove us from the circle's membership.
                try {
                    val privBytes = crypto.hexToBytes(privHex)
                    val payload = json.encodeToString(com.locapeer.circles.CircleLeavePayload(circleId))
                    val encrypted = crypto.nip44Encrypt(privBytes, owner, payload)
                    val event = NostrEvent.build(
                        privKeyHex = privHex,
                        pubKeyHex = pubHex,
                        kind = NostrEventKind.CIRCLE_LEAVE,
                        content = encrypted,
                        tags = listOf(listOf("p", owner)),
                        crypto = crypto
                    )
                    relayClient.publishEvent(event)
                } catch (e: Exception) {
                    Log.e("MessagingViewModel", "circle leave notify failed", e)
                }
                // Optionally remove the messages we sent from the other members' devices too.
                if (alsoDeleteRemote) {
                    messageDao.getMineForGroup(circleId).forEach { deleteMessageFromRemote(it) }
                }
                // Remember we left so an incoming straggler can't re-materialise the circle.
                prefs.addLeftCircleId(circleId)
                // Drop our local copy of the circle, its membership and its messages.
                messageDao.deleteAllForGroup(circleId)
                circleDao.clearMembers(circleId)
                circleDao.deleteCircle(circleId)
            } catch (e: Exception) {
                Log.e("MessagingViewModel", "leaveCircle failed", e)
            }
        }
    }

    /** Group messages share the 1:1 thread query because peerId holds the circle id. */
    fun getGroupMessages(circleId: String) = messageDao.getMessagesForPeer(circleId)

    fun observeCircle(circleId: String): Flow<CircleEntity?> = circleDao.observeCircle(circleId)

    fun markReadGroup(circleId: String) {
        viewModelScope.launch { messageDao.markAllReadForGroup(circleId) }
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
                // Capture each successfully built event id keyed by recipient pubkey while the
                // fanout runs. Captured BEFORE publish so a partial network failure doesn't
                // strand an unsent event id in the map - we only record what NostrEvent.build
                // actually produced. Consumed later by [deleteMessageFromRemote] to fan out N
                // separate NIP-09 kind-5 events. CSV format because both halves are strict
                // 64-char lowercase hex so `:`/`,` can never collide with payload bytes.
                val idsByMember = mutableListOf<String>()
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
                            idsByMember.add("$memberPub:${event.id}")
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
                        groupId = circleId,
                        nostrEventIdsByMember = idsByMember.joinToString(","),
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

    /**
     * Compresses a picked image down to the [MediaUtils.MAX_IMAGE_BYTES] cap and fans it out to
     * every non-self circle member as a media-in-group NIP-44 DM. The image is encrypted into
     * EACH recipient's individually-addressed copy on the relay (no shared group key); the
     * recipient side probes [MediaWire] inside [GroupWire.text] and reverses the nesting.
     */
    fun sendGroupImage(circleId: String, uri: Uri) {
        viewModelScope.launch {
            val base64 = withContext(Dispatchers.Default) { MediaUtils.compressImageToBase64(context, uri) }
            if (base64 == null) {
                Log.w("MessagingViewModel", "sendGroupImage: could not read/compress image")
                return@launch
            }
            sendGroupMedia(
                circleId,
                MediaMessage(kind = MediaKind.IMAGE, data = base64),
                MessageType.IMAGE,
                context.getString(com.locapeer.R.string.chat_preview_photo),
            )
        }
    }

    /**
     * Fan out a media envelope (image or voice) to every non-self circle member as a nested
     * group + media NIP-44 DM:
     *
     *   outer plaintext = GroupWire.encode(GroupMessage(text = MediaWire.encode(media)))
     *
     * Recipients decode the outer wrapper first, then probe the inner [MediaWire] envelope so
     * the DB row is stored with contentType=IMAGE/AUDIO and a localised preview ("📷 Photo" /
     * "🎤 Voice message") - never the raw Base64 / magic-prefixed text. See
     * [HeartbeatReceiver.processDmInBackground] for the matching receive path.
     */
    private fun sendGroupMedia(
        circleId: String,
        media: MediaMessage,
        contentType: String,
        previewText: String,
    ) {
        viewModelScope.launch {
            try {
                val circle = circleDao.getCircle(circleId) ?: return@launch
                val members = circleDao.getMemberPubkeys(circleId)
                val (privHex, pubHex) = keyManager.ensureKeypair()
                val privBytes = crypto.hexToBytes(privHex)
                // Per-member list captured at send time so recipients can repair their local
                // circle after membership churn.
                val fullMembers = (members + pubHex).distinct()
                val innerMediaEnvelope = MediaWire.encode(media)
                val outerGroupEnvelope = GroupWire.encode(
                    GroupMessage(
                        gid = circleId,
                        gname = circle.name,
                        members = fullMembers,
                        text = innerMediaEnvelope,
                        creator = circle.creatorPubkey,
                    )
                )
                // Same per-member event-id capture as [sendGroupMessage] - the map is what
                // [deleteMessageFromRemote] reads to fan out NIP-09 deletions for media rows.
                val idsByMember = mutableListOf<String>()
                withContext(Dispatchers.Default) {
                    members.filter { it != pubHex }.forEach { memberPub ->
                        try {
                            val encrypted = crypto.nip44Encrypt(privBytes, memberPub, outerGroupEnvelope)
                            val event = NostrEvent.build(
                                privKeyHex = privHex,
                                pubKeyHex = pubHex,
                                kind = NostrEventKind.ENCRYPTED_DM,
                                content = encrypted,
                                tags = listOf(listOf("p", memberPub)),
                                crypto = crypto,
                            )
                            idsByMember.add("$memberPub:${event.id}")
                            relayClient.publishEvent(event)
                        } catch (e: Exception) {
                            Log.e("MessagingViewModel", "group media send to $memberPub failed", e)
                        }
                    }
                }
                messageDao.insert(
                    MessageEntity(
                        id = UUID.randomUUID().toString(),
                        peerId = circleId,
                        senderPublicKeyHex = pubHex,
                        content = previewText,
                        timestamp = System.currentTimeMillis(),
                        isMine = true,
                        deliveryState = DeliveryState.SENT.name,
                        nostrEventId = "",
                        groupId = circleId,
                        contentType = contentType,
                        mediaBase64 = media.data,
                        mediaDurationMs = media.durationMs,
                        mediaFilename = media.filename,
                        mediaMimeType = media.mimeType,
                        nostrEventIdsByMember = idsByMember.joinToString(","),
                    )
                )
            } catch (e: Exception) {
                Log.e("MessagingViewModel", "sendGroupMedia failed", e)
            }
        }
    }

    fun getMessages(peerId: String) = messageDao.getMessagesForPeer(peerId)

    fun deleteMessage(msg: MessageEntity) {
        viewModelScope.launch {
            messageDao.delete(msg)
            // Drop any decrypted copy of this message's media staged in the cache.
            MediaCache.clearDecryptedMedia(context)
        }
    }

    fun deleteMessageFromRemote(msg: MessageEntity) {
        viewModelScope.launch {
            try {
                val (privHex, pubHex) = keyManager.ensureKeypair()
                // Collect every relay event id we need to invalidate - either the single 1:1
                // event id or all per-recipient fan-out ids from a circle message. Each id
                // becomes its own NIP-09 kind-5 event so every contact gets exactly the kind-5
                // carrying the SAME event id they observed on the relay - their
                // processDeletionEvent match (`msg.senderPublicKeyHex == event.pubkey` + tag
                // `e` matches the message's own nostrEventId) then succeeds. NIP-09 events
                // without a matching recipient side are a harmless no-op.
                val targets = mutableListOf<String>()
                if (msg.nostrEventId.isNotEmpty()) targets.add(msg.nostrEventId)
                if (msg.nostrEventIdsByMember.isNotEmpty()) {
                    // Format: `memberPubHex:eventIdHex` pairs separated by ','. Bounded split
                    // because every entry is strict lowercase hex so substringAfter never finds
                    // a stray `:` inside the value halves.
                    msg.nostrEventIdsByMember.split(",").forEach { pair ->
                        val eventId = pair.substringAfter(":", "")
                        if (eventId.isNotEmpty()) targets.add(eventId)
                    }
                }
                if (targets.isEmpty()) {
                    // Defensive no-op reached from the long-press delete menu for rows that never
                    // recorded a relay event id: pre-v9 circle messages, a self-only circle whose
                    // fanout had zero non-self targets, or a 1:1 row whose nostrEventId never got
                    // stamped. There is nothing on the relay to invalidate for these. Log at DEBUG
                    // level so it never clutters logcat but is available during investigation.
                    Log.d(
                        "MessagingViewModel",
                        "deleteMessageFromRemote: no relay event ids recorded for msg ${msg.id}"
                    )
                    return@launch
                }
                targets.forEach { targetEventId ->
                    val event = NostrEvent.build(
                        privKeyHex = privHex,
                        pubKeyHex = pubHex,
                        kind = NostrEventKind.EVENT_DELETION,
                        content = "Deleting message",
                        tags = listOf(listOf("e", targetEventId)),
                        crypto = crypto
                    )
                    relayClient.publishEvent(event)
                }
            } catch (e: Exception) {
                android.util.Log.e("MessagingViewModel", "deleteMessageFromRemote failed", e)
            }
        }
    }

    fun deleteConversation(peerId: String) {
        viewModelScope.launch {
            messageDao.deleteAllForPeer(peerId)
            // Purge any decrypted attachments/voice notes staged in the cache.
            MediaCache.clearDecryptedMedia(context)
        }
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

    /**
     * Bulk "mark read" from the conversation list clears the unread badge locally only and does
     * NOT emit read receipts. Selecting conversations from the list is a housekeeping gesture, not
     * an acknowledgement that the messages were actually opened, so it must not reveal to senders
     * that their messages were read - only opening the chat ([markRead]) sends receipts.
     */
    fun markReadMultiple(peerIds: List<String>) {
        viewModelScope.launch {
            peerIds.forEach { messageDao.markAllReadForPeer(it) }
        }
    }

    /**
     * Re-flags the given conversations as unread from the conversation list. Like [markReadMultiple]
     * this is a local-only housekeeping gesture: it flips only the latest received message back to
     * unread and emits nothing to the sender.
     */
    fun markUnreadMultiple(peerIds: List<String>) {
        viewModelScope.launch {
            peerIds.forEach { messageDao.markLatestUnreadForPeer(it) }
        }
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

    // ----- Inline media messages (image / voice) -----

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val _playingMessageId = MutableStateFlow<String?>(null)
    /** Id of the audio message currently playing, or null. Drives the play/pause bubble state. */
    val playingMessageId: StateFlow<String?> = _playingMessageId

    /**
     * One-shot user-facing errors from the file-attachment path, carried as an already-resolved
     * localized string so the screen can Toast it directly. Resolved here off the application
     * [context] rather than via the composable's LocalContext, which lint flags for resource reads.
     * A [SharedFlow] (not StateFlow) so the same error fires every time - re-picking an oversize file
     * must re-toast, not be swallowed as "no change".
     */
    private val _mediaError = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val mediaError: SharedFlow<String> = _mediaError

    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var recordStartMs: Long = 0L
    /**
     * Where an in-progress recording is destined to be sent. Sealed class so [stopRecordingAndSend]
     * dispatches cleanly between 1:1 and circle routing without flag-style booleans. Also lets a
     * future listener ('whoever just started recording') be added without touching call sites.
     */
    private var recordTarget: RecordTarget? = null
    private var recordTimeoutJob: Job? = null
    /**
     * Active [MediaPlayer] instances keyed by voice-note message id. Per-id temp files plus
     * per-id players mean a second message can be tapped mid-playback and replaces only the
     * previous one cleanly (no shared `play.m4a` race). Cleared in [stopAudio] / [onCleared].
     */
    private val players: MutableMap<String, MediaPlayer> = mutableMapOf()

    /** Compresses and sends an image picked from the gallery as an inline IMAGE message. */
    fun sendImage(peerId: String, uri: Uri) {
        viewModelScope.launch {
            val base64 = withContext(Dispatchers.Default) { MediaUtils.compressImageToBase64(context, uri) }
            if (base64 == null) {
                Log.w("MessagingViewModel", "sendImage: could not read/compress image")
                return@launch
            }
            sendMediaMessage(
                peerId,
                MediaMessage(kind = MediaKind.IMAGE, data = base64),
                MessageType.IMAGE,
                context.getString(com.locapeer.R.string.chat_preview_photo)
            )
        }
    }

    /**
     * Reads a picked file, enforces the [MediaUtils.MAX_FILE_BYTES] cap, and sends it inline as a
     * FILE message to a 1:1 peer. Oversize/unreadable files emit a [mediaError] instead of sending -
     * files can't be transcoded down like images, so there is no fallback but to reject them.
     */
    fun sendFile(peerId: String, uri: Uri) {
        viewModelScope.launch {
            val media = readFileOrEmitError(uri) ?: return@launch
            sendMediaMessage(peerId, media, MessageType.FILE, filePreview(media.filename))
        }
    }

    /** Circle counterpart of [sendFile]: fans the capped file out to every non-self member. */
    fun sendGroupFile(circleId: String, uri: Uri) {
        viewModelScope.launch {
            val media = readFileOrEmitError(uri) ?: return@launch
            sendGroupMedia(circleId, media, MessageType.FILE, filePreview(media.filename))
        }
    }

    /**
     * Reads [uri] off the main thread and builds a FILE [MediaMessage], or emits the matching
     * [mediaError] string and returns null when the file is too large or can't be read.
     */
    private suspend fun readFileOrEmitError(uri: Uri): MediaMessage? {
        return when (val result = withContext(Dispatchers.IO) { MediaUtils.readFileCapped(context, uri) }) {
            is MediaUtils.FileReadResult.Ok -> MediaMessage(
                kind = MediaKind.FILE,
                data = MediaUtils.encodeBase64(result.bytes),
                filename = result.name,
                mimeType = result.mimeType,
            )
            MediaUtils.FileReadResult.TooLarge -> {
                _mediaError.tryEmit(context.getString(com.locapeer.R.string.chat_file_too_large))
                null
            }
            MediaUtils.FileReadResult.Error -> {
                _mediaError.tryEmit(context.getString(com.locapeer.R.string.chat_file_unreadable))
                null
            }
        }
    }

    /** Conversation-list / bubble preview for a file row: "📎 filename" (or a generic label). */
    private fun filePreview(filename: String?): String =
        if (filename.isNullOrBlank()) context.getString(com.locapeer.R.string.chat_preview_file)
        else context.getString(com.locapeer.R.string.chat_preview_file_named, filename)

    /** Starts a voice-note recording for the given destination (1:1 or circle). The
     *  destination is stamped once at start so [stopRecordingAndSend] can route correctly
     *  to the right send pipeline without holding the call site's UI state for that. */
    fun startRecording(target: RecordTarget) {
        if (_isRecording.value) return
        try {
            val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
                else @Suppress("DEPRECATION") MediaRecorder()
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(24000)
            rec.setAudioSamplingRate(22050)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            recordFile = file
            recordStartMs = System.currentTimeMillis()
            recordTarget = target
            _isRecording.value = true
            // Auto-stop-and-send at the cap so a forgotten recording can't produce a giant payload.
            recordTimeoutJob = viewModelScope.launch {
                delay(MediaUtils.MAX_AUDIO_MS)
                if (_isRecording.value) stopRecordingAndSend()
            }
        } catch (e: Exception) {
            Log.e("MessagingViewModel", "startRecording failed", e)
            releaseRecorder()
            _isRecording.value = false
        }
    }

    /** Stops recording and routes the encoded voice note to wherever [startRecording] said
     *  it was going - either the 1:1 peer pipeline or the circle fan-out pipeline. */
    fun stopRecordingAndSend() {
        if (!_isRecording.value) return
        val file = recordFile
        val target = recordTarget
        // Clear synchronously, before the send coroutine below runs: if the user starts a new
        // recording immediately, the async clear that used to live at the end of that coroutine
        // could null out the NEW recording's freshly-stamped target and silently drop its send.
        recordTarget = null
        val durationMs = System.currentTimeMillis() - recordStartMs
        recordTimeoutJob?.cancel()
        recordTimeoutJob = null
        var ok = true
        try { recorder?.stop() } catch (e: Exception) { ok = false }
        releaseRecorder()
        _isRecording.value = false
        if (!ok || file == null || target == null || !file.exists() || durationMs < 500) {
            file?.delete()
            return
        }
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { file.readBytes() }.getOrNull() }
            file.delete()
            if (bytes == null) return@launch
            // Build the envelope once, dispatch by destination. The target snapshot prevents any
            // weirdness if stopRecordingAndSend is intercepted by a future cancel-and-restart flow.
            val media = MediaMessage(
                kind = MediaKind.AUDIO,
                data = MediaUtils.encodeBase64(bytes),
                durationMs = durationMs,
            )
            val preview = context.getString(com.locapeer.R.string.chat_preview_voice)
            when (target) {
                is RecordTarget.Peer -> sendMediaMessage(target.id, media, MessageType.AUDIO, preview)
                is RecordTarget.Circle -> sendGroupMedia(target.id, media, MessageType.AUDIO, preview)
            }
        }
    }

    fun cancelRecording() {
        if (!_isRecording.value) return
        recordTimeoutJob?.cancel()
        try { recorder?.stop() } catch (_: Exception) {}
        releaseRecorder()
        _isRecording.value = false
        recordFile?.delete()
        recordFile = null
        recordTarget = null
    }

    private fun releaseRecorder() {
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
    }

    /** Decodes an inline voice note to a per-message temp file and plays it. Tapping a different
     *  voice note while one is playing releases-and-stops the old player cleanly (keyed
     *  [players] map + keyed cache file - no shared `play.m4a` race). */
    fun toggleAudioPlayback(messageId: String, base64: String) {
        if (_playingMessageId.value == messageId) {
            stopAudio()
            return
        }
        stopAudio()
        viewModelScope.launch {
            try {
                val bytes = MediaUtils.decodeBase64(base64) ?: return@launch
                val file = withContext(Dispatchers.IO) {
                    // Per-message cache file: a simultaneous play of a different voice note
                    // never overwrites the active file mid-stream.
                    File(context.cacheDir, "play_${messageId}.m4a").apply { writeBytes(bytes) }
                }
                val mp = MediaPlayer()
                mp.setDataSource(file.absolutePath)
                mp.setOnCompletionListener { stopAudio() }
                mp.prepare()
                mp.start()
                players[messageId] = mp
                _playingMessageId.value = messageId
            } catch (e: Exception) {
                Log.e("MessagingViewModel", "audio playback failed", e)
                stopAudio()
            }
        }
    }

    fun stopAudio() {
        // Release every active player regardless of who started it - a single audio session in
        // a decentralised messaging app might still happen to overlap briefly across screens if
        // the user keeps tapping, so the safe default is "always tear them all down".
        players.values.forEach { mp ->
            try { mp.stop() } catch (_: Exception) {}
            try { mp.release() } catch (_: Exception) {}
        }
        players.clear()
        _playingMessageId.value = null
    }

    /**
     * Decodes a received FILE row to a private cache file and hands it to the system viewer via a
     * FileProvider content Uri + ACTION_VIEW. The stored [MessageEntity.mediaFilename] comes from a
     * remote sender, so it is sanitized to a bare, safe basename before it touches the filesystem -
     * a crafted "../" name must never escape the attachments cache dir. Emits [mediaError] when the
     * bytes are missing/corrupt or no installed app can open the type.
     */
    fun openFile(msg: MessageEntity) {
        val base64 = msg.mediaBase64
        if (base64.isNullOrBlank()) {
            _mediaError.tryEmit(context.getString(com.locapeer.R.string.chat_file_unreadable))
            return
        }
        viewModelScope.launch {
            try {
                val bytes = MediaUtils.decodeBase64(base64)
                if (bytes == null) {
                    _mediaError.tryEmit(context.getString(com.locapeer.R.string.chat_file_unreadable))
                    return@launch
                }
                val safeName = sanitizeFilename(msg.mediaFilename)
                val file = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "attachments").apply { mkdirs() }
                    File(dir, safeName).apply { writeBytes(bytes) }
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val mime = msg.mediaMimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    _mediaError.tryEmit(context.getString(com.locapeer.R.string.chat_file_no_app))
                }
            } catch (e: Exception) {
                Log.e("MessagingViewModel", "openFile failed", e)
                _mediaError.tryEmit(context.getString(com.locapeer.R.string.chat_file_unreadable))
            }
        }
    }

    /** Reduces a possibly-hostile remote filename to a safe basename for the attachments cache. */
    private fun sanitizeFilename(name: String?): String {
        val base = name?.substringAfterLast('/')?.substringAfterLast('\\')?.trim().orEmpty()
        // Keep only characters that are safe in a filename; collapse everything else to '_'.
        val cleaned = base.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_')
        return cleaned.ifBlank { "attachment" }.take(120)
    }

    private fun sendMediaMessage(peerId: String, media: MediaMessage, contentType: String, previewText: String) {
        viewModelScope.launch {
            try {
                val peer = peerDao.getPeer(peerId) ?: return@launch
                val (privHex, pubHex) = keyManager.ensureKeypair()
                val privBytes = crypto.hexToBytes(privHex)
                val plaintext = MediaWire.encode(media)
                val event = withContext(Dispatchers.Default) {
                    val encrypted = crypto.nip44Encrypt(privBytes, peer.publicKeyHex, plaintext)
                    NostrEvent.build(
                        privKeyHex = privHex,
                        pubKeyHex = pubHex,
                        kind = NostrEventKind.ENCRYPTED_DM,
                        content = encrypted,
                        tags = listOf(listOf("p", peer.publicKeyHex)),
                        crypto = crypto
                    )
                }
                relayClient.publishEvent(event)
                peerDao.unarchive(peerId)
                messageDao.insert(
                    MessageEntity(
                        id = event.id,
                        peerId = peerId,
                        senderPublicKeyHex = pubHex,
                        content = previewText,
                        timestamp = System.currentTimeMillis(),
                        isMine = true,
                        deliveryState = DeliveryState.SENDING.name,
                        nostrEventId = event.id,
                        contentType = contentType,
                        mediaBase64 = media.data,
                        mediaDurationMs = media.durationMs,
                        mediaFilename = media.filename,
                        mediaMimeType = media.mimeType
                    )
                )
            } catch (e: Exception) {
                Log.e("MessagingViewModel", "sendMediaMessage failed", e)
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
        recordTimeoutJob?.cancel()
        releaseRecorder()
        stopAudio()
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

/**
 * Destination of an in-progress voice recording. Lets [MessagingViewModel.startRecording] take a
 * single argument and keep the existing [MessagingViewModel.stopRecordingAndSend] signature
 * (which has no destination parameter), while still routing to either the 1:1 peer send path
 * ([MessagingViewModel.sendMediaMessage]) or the circle fan-out path
 * ([MessagingViewModel.sendGroupMedia]).
 */
sealed class RecordTarget {
    data class Peer(val id: String) : RecordTarget()
    data class Circle(val id: String) : RecordTarget()
}
