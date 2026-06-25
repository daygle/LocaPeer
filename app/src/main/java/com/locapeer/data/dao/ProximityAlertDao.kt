package com.locapeer.data.dao

import androidx.room.*
import com.locapeer.data.entity.ProximityAlertEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProximityAlertDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(alert: ProximityAlertEntity)

    @Delete
    suspend fun delete(alert: ProximityAlertEntity)

    @Query("SELECT * FROM proximity_alerts")
    fun getAll(): Flow<List<ProximityAlertEntity>>

    @Query("SELECT * FROM proximity_alerts WHERE peerDeviceId = :peerDeviceId LIMIT 1")
    suspend fun getForPeer(peerDeviceId: String): ProximityAlertEntity?

    @Query("UPDATE proximity_alerts SET active = :active WHERE peerDeviceId = :peerDeviceId")
    suspend fun setActive(peerDeviceId: String, active: Boolean)

    @Query("UPDATE proximity_alerts SET radiusMetres = :radiusMetres WHERE peerDeviceId = :peerDeviceId")
    suspend fun setRadius(peerDeviceId: String, radiusMetres: Int)
}
