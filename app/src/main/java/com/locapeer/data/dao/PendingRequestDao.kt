package com.locapeer.data.dao

import androidx.room.*
import com.locapeer.data.entity.PendingRequestEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingRequestDao {
    @Query("SELECT * FROM pending_requests ORDER BY receivedAt DESC")
    fun observeAll(): Flow<List<PendingRequestEntity>>

    @Query("SELECT * FROM pending_requests WHERE senderPubkey = :senderPubkey")
    suspend fun getByPubkey(senderPubkey: String): PendingRequestEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(request: PendingRequestEntity)

    @Query("DELETE FROM pending_requests WHERE senderPubkey = :senderPubkey")
    suspend fun deleteByPubkey(senderPubkey: String)

    @Query("SELECT COUNT(*) FROM pending_requests")
    fun observeCount(): Flow<Int>
}
