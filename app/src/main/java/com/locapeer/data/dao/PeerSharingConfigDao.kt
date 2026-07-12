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

    @Query("UPDATE peer_sharing_config SET precisionMode = :mode WHERE peerDeviceId = :peerDeviceId")
    suspend fun setPrecisionMode(peerDeviceId: String, mode: String)

    @Query("UPDATE peer_sharing_config SET scheduleRulesJson = :rulesJson WHERE peerDeviceId = :peerDeviceId")
    suspend fun setScheduleRules(peerDeviceId: String, rulesJson: String)

    @Query("UPDATE peer_sharing_config SET retentionDaysLocation = :days WHERE peerDeviceId = :peerDeviceId")
    suspend fun setRetentionDaysLocation(peerDeviceId: String, days: Int)

    @Query("UPDATE peer_sharing_config SET retentionDaysMessages = :days WHERE peerDeviceId = :peerDeviceId")
    suspend fun setRetentionDaysMessages(peerDeviceId: String, days: Int)

    @Query("UPDATE peer_sharing_config SET isMySupervised = :supervised WHERE peerDeviceId = :peerDeviceId")
    suspend fun setIsMySupervised(peerDeviceId: String, supervised: Boolean)

    @Query("UPDATE peer_sharing_config SET notifyOnMissedHeartbeat = :enabled WHERE peerDeviceId = :peerDeviceId")
    suspend fun setNotifyOnMissedHeartbeat(peerDeviceId: String, enabled: Boolean)

    /**
     * Set or clear the per-peer one-off temporary share expiry. Pass null to clear. The
     * caller is responsible for also scheduling/cancelling [com.locapeer.sharing.TempShareExpiryWorker]
     * - the DAO only stores the value.
     */
    @Query("UPDATE peer_sharing_config SET temporaryShareEndsAtEpochSeconds = :endsAtEpochSeconds WHERE peerDeviceId = :peerDeviceId")
    suspend fun setTemporaryShareEndsAt(peerDeviceId: String, endsAtEpochSeconds: Long?)

    @Query("SELECT * FROM peer_sharing_config")
    suspend fun getAll(): List<PeerSharingConfig>

    @Query("SELECT * FROM peer_sharing_config")
    fun observeAll(): Flow<List<PeerSharingConfig>>

    @Query("DELETE FROM peer_sharing_config WHERE peerDeviceId = :peerDeviceId")
    suspend fun deleteForPeer(peerDeviceId: String)
}
