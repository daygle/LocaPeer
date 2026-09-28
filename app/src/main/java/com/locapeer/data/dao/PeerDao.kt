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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPeer(peer: PeerEntity)

    /** Targeted rename, so a UI snapshot of the row can't overwrite fields (role, relay,
     *  archive state) that changed in the background since the screen loaded it. */
    @Query("UPDATE peers SET displayName = :displayName WHERE deviceId = :deviceId")
    suspend fun rename(deviceId: String, displayName: String)

    @Query("DELETE FROM peers WHERE deviceId = :deviceId")
    suspend fun deletePeerById(deviceId: String)

    @Query(
        "UPDATE peers SET isArchived = :archived, " +
            "archivedAt = CASE WHEN :archived = 1 THEN :now ELSE archivedAt END " +
            "WHERE deviceId = :peerId OR publicKeyHex = :peerId"
    )
    suspend fun setArchived(peerId: String, archived: Boolean, now: Long)

    suspend fun unarchive(peerId: String) = setArchived(peerId, false, 0)
}
