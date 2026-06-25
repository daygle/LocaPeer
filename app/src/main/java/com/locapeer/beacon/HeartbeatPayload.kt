package com.locapeer.beacon

import kotlinx.serialization.Serializable

@Serializable
data class HeartbeatPayload(
    val deviceId: String,
    val displayName: String,
    val timestamp: String,
    val lat: Double,
    val lng: Double,
    val accuracy: Float,
    val battery: Int,
    val motionState: String,
    val isSos: Boolean = false
)
