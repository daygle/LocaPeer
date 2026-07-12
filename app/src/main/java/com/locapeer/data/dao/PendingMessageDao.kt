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

    @Delete
    suspend fun delete(msg: PendingMessageEntity)
}
