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

    @Query("SELECT * FROM geofences WHERE id = :id")
    suspend fun getById(id: String): GeofenceEntity?
}
