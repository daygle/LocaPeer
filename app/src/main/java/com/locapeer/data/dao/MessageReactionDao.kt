package com.locapeer.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.locapeer.data.entity.MessageReactionEntity
import kotlinx.coroutines.flow.Flow

/** A circle reaction joined with the reactor's current display name for the bubble chip. */
data class MessageReactionWithName(
    val messageId: String,
    val reactorPubkey: String,
    val reactorName: String,
    val emoji: String
)

@Dao
interface MessageReactionDao {
    /** One emoji per member per message: a new choice replaces that member's previous one. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reaction: MessageReactionEntity)

    @Query("DELETE FROM message_reactions WHERE messageId = :messageId AND reactorPubkey = :reactorPubkey")
    suspend fun delete(messageId: String, reactorPubkey: String)

    /** Drop rows whose message is gone (message/thread deletion, retention purge). */
    @Query("DELETE FROM message_reactions WHERE messageId NOT IN (SELECT id FROM messages)")
    suspend fun deleteOrphans()

    /**
     * Every reaction on the messages of one thread (a circle id), newest name source included.
     * The join is what lets the bubble show "who reacted" without a second lookup per row.
     */
    @Query(
        "SELECT r.messageId AS messageId, r.reactorPubkey AS reactorPubkey, " +
            "COALESCE(p.displayName, '') AS reactorName, r.emoji AS emoji " +
            "FROM message_reactions r LEFT JOIN peers p ON p.deviceId = r.reactorPubkey " +
            "WHERE r.messageId IN (SELECT id FROM messages WHERE peerId = :threadId)"
    )
    fun observeForThread(threadId: String): Flow<List<MessageReactionWithName>>
}
