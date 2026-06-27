package com.locapeer.data.dao

import androidx.room.*
import com.locapeer.data.entity.PeerSharingConfig
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerSharingConfigDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: PeerSharingConfig)

    @Query("SELECT * FROM peer_sharing_config WHERE peerDeviceId = :peerDeviceId LIMIT 1")
    suspend fun getForPeer(peerDeviceId: String): PeerSharingConfig?

    @Query("SELECT * FROM peer_sharing_config WHERE peerDeviceId = :peerDeviceId LIMIT 1")
    fun observeForPeer(peerDeviceId: String): Flow<PeerSharingConfig?>

    @Query("UPDATE peer_sharing_config SET sharingEnabled = :enabled WHERE peerDeviceId = :peerDeviceId")
    suspend fun setSharingEnabled(peerDeviceId: String, enabled: Boolean)

    @Query("UPDATE peer_sharing_config SET messagingEnabled = :enabled WHERE peerDeviceId = :peerDeviceId")
    suspend fun setMessagingEnabled(peerDeviceId: String, enabled: Boolean)

    @Query("UPDATE peer_sharing_config SET precisionMode = :mode WHERE peerDeviceId = :peerDeviceId")
    suspend fun setPrecisionMode(peerDeviceId: String, mode: String)

    @Query("UPDATE peer_sharing_config SET scheduleRulesJson = :rulesJson WHERE peerDeviceId = :peerDeviceId")
    suspend fun setScheduleRules(peerDeviceId: String, rulesJson: String)

    @Query("SELECT * FROM peer_sharing_config")
    suspend fun getAll(): List<PeerSharingConfig>

    @Query("SELECT * FROM peer_sharing_config")
    fun observeAll(): Flow<List<PeerSharingConfig>>

    @Query("DELETE FROM peer_sharing_config WHERE peerDeviceId = :peerDeviceId")
    suspend fun deleteForPeer(peerDeviceId: String)
}
