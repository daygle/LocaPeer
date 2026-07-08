package com.locapeer.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A geofence *area*: a named circular region on the map. Areas are global and can be
 * shared across contacts - which contact a geofence watches, and whether it fires on
 * entry/exit, lives in [GeofenceAssignmentEntity].
 */
@Entity(tableName = "geofences")
data class GeofenceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radiusMetres: Int,
    val createdAt: Long = System.currentTimeMillis()
)
