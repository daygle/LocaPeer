package com.locapeer.data.dao

import androidx.room.*
import com.locapeer.data.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity)

    @Update
    suspend fun update(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE peerId = :peerId ORDER BY timestamp ASC")
    fun getMessagesForPeer(peerId: String): Flow<List<MessageEntity>>

    @Query(
        "SELECT * FROM messages m1 WHERE timestamp = (" +
            "SELECT MAX(timestamp) FROM messages m2 WHERE m2.peerId = m1.peerId" +
            ") GROUP BY peerId ORDER BY timestamp DESC"
    )
    fun getConversationSummaries(): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM messages WHERE peerId = :peerId AND isRead = 0 AND isMine = 0")
    fun getUnreadCount(peerId: String): Flow<Int>

    @Query("UPDATE messages SET isRead = 1 WHERE peerId = :peerId AND isMine = 0")
    suspend fun markAllReadForPeer(peerId: String)

    @Query("DELETE FROM messages WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM messages WHERE peerId = :peerId")
    suspend fun deleteAllForPeer(peerId: String)

    @Query("SELECT * FROM messages WHERE nostrEventId = :eventId LIMIT 1")
    suspend fun getByNostrEventId(eventId: String): MessageEntity?

    @Query("UPDATE messages SET deliveryState = :state WHERE nostrEventId = :nostrEventId AND nostrEventId != ''")
    suspend fun updateDeliveryStateByNostrEventId(nostrEventId: String, state: String)

    @Query("SELECT * FROM messages WHERE peerId = :peerId AND isMine = 0 AND isRead = 0")
    suspend fun getUnreadFromPeer(peerId: String): List<MessageEntity>

    @Query("DELETE FROM messages WHERE senderPublicKeyHex = :senderPubKeyHex AND timestamp < :before")
    suspend fun deleteOlderThanFromSender(senderPubKeyHex: String, before: Long)
}
