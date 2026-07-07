package com.locapeer.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "proximity_alerts")
data class ProximityAlertEntity(
    @PrimaryKey val peerDeviceId: String,
    val radiusMetres: Int = 500,
    val active: Boolean = true,
    val scheduleRules: String = "[]"
)
