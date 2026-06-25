package com.locapeer.data.dao

import androidx.room.*
import com.locapeer.data.entity.GeofenceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GeofenceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(geofence: GeofenceEntity)

    @Delete
    suspend fun delete(geofence: GeofenceEntity)

    @Query("SELECT * FROM geofences ORDER BY name ASC")
    fun getAllGeofences(): Flow<List<GeofenceEntity>>

    @Query("SELECT * FROM geofences WHERE trackedDeviceId = :deviceId AND active = 1")
    suspend fun getActiveGeofencesForDevice(deviceId: String): List<GeofenceEntity>

    @Query("UPDATE geofences SET active = :active WHERE id = :id")
    suspend fun setActive(id: String, active: Boolean)

    @Query("SELECT * FROM geofences WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): GeofenceEntity?
}
