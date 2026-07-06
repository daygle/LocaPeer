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

    @Query("SELECT * FROM messages WHERE peerId = :peerId AND isBlocked = 0 ORDER BY timestamp ASC")
    fun getMessagesForPeer(peerId: String): Flow<List<MessageEntity>>

    @Query(
        "SELECT * FROM messages m1 WHERE isBlocked = 0 AND id = (" +
            "SELECT m2.id FROM messages m2 WHERE m2.peerId = m1.peerId AND m2.isBlocked = 0 " +
            "ORDER BY m2.timestamp DESC, m2.id DESC LIMIT 1" +
            ") ORDER BY timestamp DESC"
    )
    fun getConversationSummaries(): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM messages WHERE peerId = :peerId AND isRead = 0 AND isMine = 0 AND isBlocked = 0")
    fun getUnreadCount(peerId: String): Flow<Int>

    @Query("SELECT peerId, COUNT(*) as cnt FROM messages WHERE isRead = 0 AND isMine = 0 AND isBlocked = 0 GROUP BY peerId")
    fun getUnreadCountsPerPeer(): Flow<List<UnreadCountRow>>

    @Query("UPDATE messages SET isRead = 1 WHERE peerId = :peerId AND isMine = 0 AND isBlocked = 0")
    suspend fun markAllReadForPeer(peerId: String)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteById(messageId: String)

    @Query("DELETE FROM messages WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM messages WHERE peerId = :peerId")
    suspend fun deleteAllForPeer(peerId: String)

    @Query("SELECT * FROM messages WHERE nostrEventId = :eventId LIMIT 1")
    suspend fun getByNostrEventId(eventId: String): MessageEntity?

    @Query("UPDATE messages SET deliveryState = :state WHERE nostrEventId = :nostrEventId AND nostrEventId != ''")
    suspend fun updateDeliveryStateByNostrEventId(nostrEventId: String, state: String)

    /**
     * Scoped variant used when a peer reports delivery/read state (DELIVERY_ACK / READ_RECEIPT).
     * Only messages we sent to that specific peer may be touched, so a contact cannot flip the
     * displayed delivery state of messages we sent to *other* contacts by replaying the public
     * event ids it observed on the relay.
     */
    @Query(
        "UPDATE messages SET deliveryState = :state " +
            "WHERE nostrEventId = :nostrEventId AND nostrEventId != '' AND peerId = :peerId AND isMine = 1"
    )
    suspend fun updateDeliveryStateByNostrEventIdForPeer(nostrEventId: String, peerId: String, state: String)

    @Query("SELECT * FROM messages WHERE peerId = :peerId AND isMine = 0 AND isRead = 0 AND isBlocked = 0")
    suspend fun getUnreadFromPeer(peerId: String): List<MessageEntity>

    @Query("UPDATE messages SET isBlocked = 0 WHERE peerId = :peerId AND isBlocked = 1")
    suspend fun unblockMessagesFromPeer(peerId: String)

    @Query("DELETE FROM messages WHERE senderPublicKeyHex = :senderPubKeyHex AND timestamp < :before")
    suspend fun deleteOlderThanFromSender(senderPubKeyHex: String, before: Long)

    @Query("DELETE FROM messages WHERE senderPublicKeyHex = :senderPubKeyHex")
    suspend fun deleteAllFromSender(senderPubKeyHex: String)
}
