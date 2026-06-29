package com.locapeer.data.dao

import androidx.room.*
import com.locapeer.data.entity.PeerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers ORDER BY displayName ASC")
    fun getAllPeers(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE locationRole = 'RECEIVE' OR locationRole = 'SEND_RECEIVE' ORDER BY displayName ASC")
    fun getReceiveContacts(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE locationRole = 'SEND' OR locationRole = 'SEND_RECEIVE' ORDER BY displayName ASC")
    fun getPeersReceivingMyLocation(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE deviceId = :deviceId LIMIT 1")
    suspend fun getPeer(deviceId: String): PeerEntity?

    @Query("SELECT * FROM peers WHERE deviceId = :deviceId LIMIT 1")
    fun observePeer(deviceId: String): Flow<PeerEntity?>

    @Query("SELECT * FROM peers WHERE publicKeyHex = :publicKeyHex LIMIT 1")
    suspend fun getPeerByPublicKey(publicKeyHex: String): PeerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPeer(peer: PeerEntity)

    @Query("DELETE FROM peers WHERE deviceId = :deviceId")
    suspend fun deletePeerById(deviceId: String)
}
