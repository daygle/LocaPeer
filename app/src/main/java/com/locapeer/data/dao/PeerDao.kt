package com.locapeer.data.dao

import androidx.room.*
import com.locapeer.data.entity.PeerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers ORDER BY displayName ASC")
    fun getAllPeers(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE role = 'BROADCASTER' ORDER BY displayName ASC")
    fun getBroadcasters(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE role = 'SUBSCRIBER' ORDER BY displayName ASC")
    fun getSubscribers(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE deviceId = :deviceId LIMIT 1")
    suspend fun getPeer(deviceId: String): PeerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPeer(peer: PeerEntity)

    @Delete
    suspend fun deletePeer(peer: PeerEntity)

    @Query("DELETE FROM peers WHERE deviceId = :deviceId")
    suspend fun deletePeerById(deviceId: String)
}
