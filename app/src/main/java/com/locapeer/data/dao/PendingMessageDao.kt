package com.locapeer.data.dao

import androidx.room.*
import com.locapeer.data.entity.PendingMessageEntity

@Dao
interface PendingMessageDao {
    @Query("SELECT * FROM pending_messages ORDER BY id ASC")
    suspend fun getAll(): List<PendingMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(msg: PendingMessageEntity)

    @Delete
    suspend fun delete(msg: PendingMessageEntity)

    @Query("DELETE FROM pending_messages WHERE id = :id")
    suspend fun deleteById(id: Long)
}
