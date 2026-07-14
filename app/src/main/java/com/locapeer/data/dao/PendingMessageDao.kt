package com.locapeer.data.dao

import androidx.room.*
import com.locapeer.data.entity.PendingMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingMessageDao {

    @Query("SELECT * FROM pending_messages WHERE relayUrl = :relayUrl ORDER BY id ASC")
    suspend fun getForRelay(relayUrl: String): List<PendingMessageEntity>

    /** Observable total of queued messages across all relays, surfaced in the relay status
     *  chip / About diagnostics so users see "N messages stuck" beyond the connected/disconnected dot. */
    @Query("SELECT COUNT(*) FROM pending_messages")
    fun countAll(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(msg: PendingMessageEntity)

    @Query("DELETE FROM pending_messages WHERE relayUrl = :relayUrl")
    suspend fun deleteForRelay(relayUrl: String)

    /**
     * Drop queued heartbeat events older than [cutoff] (by enqueue time) for a relay.
     * [heartbeatPattern] matches only HEARTBEAT rows by their serialized `"kind":1040`
     * marker, so queued DMs and SOS alerts are never touched - a stale location is
     * worthless once superseded, but a message must still be delivered. Matched by
     * content substring because pending rows store the raw event JSON (no typed kind
     * column), and the serializer emits compact, stable JSON.
     */
    @Query("DELETE FROM pending_messages WHERE relayUrl = :relayUrl AND content LIKE :heartbeatPattern AND createdAt < :cutoff")
    suspend fun deleteStaleHeartbeats(relayUrl: String, heartbeatPattern: String, cutoff: Long)

    /** Cap queued heartbeat events per relay to the newest [keep], so a long offline
     *  stretch can't grow the table without bound. Only heartbeat rows are pruned. */
    @Query(
        "DELETE FROM pending_messages WHERE relayUrl = :relayUrl AND content LIKE :heartbeatPattern " +
            "AND id NOT IN (SELECT id FROM pending_messages WHERE relayUrl = :relayUrl " +
            "AND content LIKE :heartbeatPattern ORDER BY id DESC LIMIT :keep)"
    )
    suspend fun capHeartbeats(relayUrl: String, heartbeatPattern: String, keep: Int)

    @Delete
    suspend fun delete(msg: PendingMessageEntity)
}
