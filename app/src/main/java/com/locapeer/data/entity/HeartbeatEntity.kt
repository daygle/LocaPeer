package com.locapeer.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "heartbeats",
    indices = [Index("deviceId"), Index("timestamp")]
)
data class HeartbeatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: String,
    val displayName: String,
    val timestamp: Long,
    val lat: Double,
    val lng: Double,
    val accuracy: Float,
    val battery: Int,
    val motionState: String,
    val isSos: Boolean = false,
    val receivedAt: Long = System.currentTimeMillis(),
    /** Hex colour string the sender chose for their map pin. Empty = use auto colour. */
    val pinColor: String = "",
    /** Speed in m/s from GPS. 0 when stationary or unavailable. */
    val speed: Float = 0f,
    /** Bearing in degrees (0–360) from GPS. 0 when stationary or unavailable. */
    val bearing: Float = 0f,
    /** Sender's heartbeat interval in seconds when this ping was sent. */
    val expectedIntervalSeconds: Long
)
