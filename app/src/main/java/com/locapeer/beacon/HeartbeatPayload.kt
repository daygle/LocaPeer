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
    val isSos: Boolean = false,
    /** Days to retain this device's heartbeats on the receiver. 0 = no expiry (default). */
    val retentionDays: Int = 0,
    /** Hex colour string chosen by the sender for their map pin (e.g. "#1565C0"). Empty = auto. */
    val pinColor: String = ""
)
