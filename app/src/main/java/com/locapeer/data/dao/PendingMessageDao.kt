package com.locapeer.data.dao

import androidx.room.*
import com.locapeer.data.entity.PendingMessageEntity

@Dao
interface PendingMessageDao {

    @Query("SELECT * FROM pending_messages WHERE relayUrl = :relayUrl ORDER BY id ASC")
    suspend fun getForRelay(relayUrl: String): List<PendingMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(msg: PendingMessageEntity)

    @Query("DELETE FROM pending_messages WHERE relayUrl = :relayUrl")
    suspend fun deleteForRelay(relayUrl: String)

    @Delete
    suspend fun delete(msg: PendingMessageEntity)
}
