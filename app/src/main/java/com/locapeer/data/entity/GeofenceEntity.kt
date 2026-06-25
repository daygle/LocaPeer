package com.locapeer.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "geofences")
data class GeofenceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radiusMetres: Int,
    val trackedDeviceId: String,
    /** ENTER, EXIT, or BOTH */
    val triggerOn: String,
    val active: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
