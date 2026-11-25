package com.locapeer.data.dao

import androidx.room.*
import com.locapeer.data.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

data class UnreadCountRow(val peerId: String, val cnt: Int)

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity)

    @Update
    suspend fun update(message: MessageEntity)

    @Delete
    suspend fun delete(message: MessageEntity)

    /**
     * The newest [limit] visible messages of a thread (contact device id, or circle id for a
     * circle), oldest first, for the chat screens. The Base64 media column is returned as NULL:
     * this Flow re-runs on every messages-table write (each delivery tick and read receipt), and
     * reading up to ~200 KB of media per row each time made long media-heavy chats slow. Bubbles
     * load their own bytes through [getMediaBase64] when shown.
     */
    @Query(
        "SELECT id, peerId, senderPublicKeyHex, content, timestamp, isMine, deliveryState, isRead, " +
            "nostrEventId, nostrEventIdsByMember, isBlocked, groupId, contentType, NULL AS mediaBase64, " +
            "mediaDurationMs, mediaFilename, mediaMimeType, reaction, peerReaction, remoteMessageId " +
            "FROM (SELECT * FROM messages WHERE peerId = :peerId AND isBlocked = 0 " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit) " +
            "ORDER BY timestamp ASC, id ASC"
    )
    fun getRecentMessagesForPeer(peerId: String, limit: Int): Flow<List<MessageEntity>>

    /** A single message's Base64 media bytes (see [getRecentMessagesForPeer]). */
    @Query("SELECT mediaBase64 FROM messages WHERE id = :messageId")
    suspend fun getMediaBase64(messageId: String): String?

    /**
     * Latest visible message per 1:1 conversation, newest first. The "latest row" lookup runs
     * once per conversation (over the distinct thread keys), not once per message row: the
     * per-row form re-sorted each thread for every one of its messages, and this Flow re-runs on
     * every messages-table write (send, receive, delivery and read updates). Circle threads are
     * excluded; they have their own summary query.
     */
    @Query(
        "SELECT * FROM messages WHERE id IN (" +
            "SELECT (SELECT m2.id FROM messages m2 WHERE m2.peerId = p.peerId AND m2.isBlocked = 0 " +
            "ORDER BY m2.timestamp DESC, m2.id DESC LIMIT 1) " +
            "FROM (SELECT DISTINCT peerId FROM messages WHERE isBlocked = 0 AND groupId IS NULL) p" +
            ") ORDER BY timestamp DESC"
    )
    fun getConversationSummaries(): Flow<List<MessageEntity>>

    /** Unread per 1:1 conversation. Circle rows (which reuse peerId as the circle id) are counted
     *  by [getUnreadCountsPerGroup] instead; counting them here put circle unread on the Chats badge. */
    @Query("SELECT peerId, COUNT(*) as cnt FROM messages WHERE isRead = 0 AND isMine = 0 AND isBlocked = 0 AND groupId IS NULL GROUP BY peerId")
    fun getUnreadCountsPerPeer(): Flow<List<UnreadCountRow>>

    @Query("UPDATE messages SET isRead = 1 WHERE peerId = :peerId AND isMine = 0 AND isBlocked = 0")
    suspend fun markAllReadForPeer(peerId: String)

    /**
     * Flags the most recent received message from a peer as unread so the conversation surfaces an
     * unread badge again. Only the latest incoming message is touched (badge shows 1) rather than
     * the whole history, matching how other messengers "mark unread". Local-only, like the bulk
     * mark-read path - no read receipts are involved either way.
     */
    @Query(
        "UPDATE messages SET isRead = 0 WHERE id = (" +
            "SELECT id FROM messages WHERE peerId = :peerId AND isMine = 0 AND isBlocked = 0 " +
            "ORDER BY timestamp DESC, id DESC LIMIT 1)"
    )
    suspend fun markLatestUnreadForPeer(peerId: String)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteById(messageId: String)

    /** Set (or clear, with null) the local reaction on a single message. */
    @Query("UPDATE messages SET reaction = :reaction WHERE id = :messageId")
    suspend fun setReaction(messageId: String, reaction: String?)

    /** Set (or clear, with null) the reaction the other participant put on a message. */
    @Query("UPDATE messages SET peerReaction = :reaction WHERE id = :messageId")
    suspend fun setPeerReaction(messageId: String, reaction: String?)

    /**
     * The 1:1 message a contact's reaction targets: addressed by its Nostr event id, and only
     * within that contact's own thread (uses the nostrEventId index).
     */
    @Query(
        "SELECT * FROM messages WHERE nostrEventId = :targetId AND peerId = :peerId " +
            "AND groupId IS NULL LIMIT 1"
    )
    suspend fun findDirectReactionTarget(targetId: String, peerId: String): MessageEntity?

    /**
     * The circle message a member's reaction targets, addressed by the author's logical id -
     * either the row's own id (author's copy) or [MessageEntity.remoteMessageId] (everyone else's
     * copy) - and only within that circle (uses the groupId index). The receiver refuses a
     * logical id the circle already holds, so at most one row matches; the author's own row is
     * still preferred should older data hold a clash.
     */
    @Query(
        "SELECT * FROM messages WHERE groupId = :groupId " +
            "AND (id = :targetId OR remoteMessageId = :targetId) " +
            "ORDER BY (id = :targetId) DESC LIMIT 1"
    )
    suspend fun findCircleReactionTarget(targetId: String, groupId: String): MessageEntity?

    @Query("DELETE FROM messages WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM messages WHERE peerId = :peerId")
    suspend fun deleteAllForPeer(peerId: String)

    @Query("SELECT * FROM messages WHERE nostrEventId = :eventId LIMIT 1")
    suspend fun getByNostrEventId(eventId: String): MessageEntity?

    /** Primary-key existence check. Received rows use the Nostr event id as their [MessageEntity.id],
     *  so this is the indexed way to dedupe an incoming event (nostrEventId has no index). */
    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE id = :id)")
    suspend fun existsById(id: String): Boolean

    // --- Group / circle conversations. A group message stores the circle id in both peerId
    // (thread key, so getRecentMessagesForPeer works unchanged) and groupId (marker). ---

    /** Latest message per circle, newest first. Used to build the group conversation list. The
     *  lookup runs once per circle, not once per message row (see [getConversationSummaries]). */
    @Query(
        "SELECT * FROM messages WHERE id IN (" +
            "SELECT (SELECT m2.id FROM messages m2 WHERE m2.groupId = g.groupId " +
            "ORDER BY m2.timestamp DESC, m2.id DESC LIMIT 1) " +
            "FROM (SELECT DISTINCT groupId FROM messages WHERE groupId IS NOT NULL) g" +
            ") ORDER BY timestamp DESC"
    )
    fun getGroupConversationSummaries(): Flow<List<MessageEntity>>

    @Query("SELECT groupId AS peerId, COUNT(*) as cnt FROM messages WHERE groupId IS NOT NULL AND isRead = 0 AND isMine = 0 GROUP BY groupId")
    fun getUnreadCountsPerGroup(): Flow<List<UnreadCountRow>>

    @Query("UPDATE messages SET isRead = 1 WHERE groupId = :groupId AND isMine = 0")
    suspend fun markAllReadForGroup(groupId: String)

    @Query("DELETE FROM messages WHERE groupId = :groupId")
    suspend fun deleteAllForGroup(groupId: String)

    /** Messages the local user sent to a circle - used to fan out NIP-09 deletions when leaving
     *  a circle with the "delete my messages everywhere" option. */
    @Query("SELECT * FROM messages WHERE groupId = :groupId AND isMine = 1")
    suspend fun getMineForGroup(groupId: String): List<MessageEntity>

    /**
     * Relay acknowledgement (OK true): SENDING -> SENT only. Every relay that accepts the event
     * sends its own OK, often after the recipient's delivery ack or read receipt has already
     * landed, so an unconditional update would regress DELIVERED/READ back to SENT.
     */
    @Query(
        "UPDATE messages SET deliveryState = 'SENT' " +
            "WHERE nostrEventId = :nostrEventId AND nostrEventId != '' AND deliveryState = 'SENDING'"
    )
    suspend fun markSentByNostrEventId(nostrEventId: String)

    /**
     * Scoped variant used when a peer reports delivery/read state (DELIVERY_ACK / READ_RECEIPT).
     * Only messages we sent to that specific peer may be touched, so a contact cannot flip the
     * displayed delivery state of messages we sent to *other* contacts by replaying the public
     * event ids it observed on the relay.
     */
    @Query(
        "UPDATE messages SET deliveryState = 'DELIVERED' " +
            "WHERE nostrEventId = :nostrEventId AND nostrEventId != '' AND peerId = :peerId AND isMine = 1 " +
            "AND deliveryState != 'READ'"
    )
    suspend fun markDeliveredForPeer(nostrEventId: String, peerId: String)

    /** READ is terminal, so it may overwrite any earlier state. Scoped like [markDeliveredForPeer]. */
    @Query(
        "UPDATE messages SET deliveryState = 'READ' " +
            "WHERE nostrEventId = :nostrEventId AND nostrEventId != '' AND peerId = :peerId AND isMine = 1"
    )
    suspend fun markReadForPeer(nostrEventId: String, peerId: String)

    @Query("SELECT * FROM messages WHERE peerId = :peerId AND isMine = 0 AND isRead = 0 AND isBlocked = 0")
    suspend fun getUnreadFromPeer(peerId: String): List<MessageEntity>

    @Query("UPDATE messages SET isBlocked = 0 WHERE peerId = :peerId AND isBlocked = 1")
    suspend fun unblockMessagesFromPeer(peerId: String)

    @Query("DELETE FROM messages WHERE senderPublicKeyHex = :senderPubKeyHex AND timestamp < :before")
    suspend fun deleteOlderThanFromSender(senderPubKeyHex: String, before: Long)

    @Query("DELETE FROM messages WHERE senderPublicKeyHex = :senderPubKeyHex")
    suspend fun deleteAllFromSender(senderPubKeyHex: String)
}
