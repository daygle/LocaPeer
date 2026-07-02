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
    val pinColor: String = "",
    /** Speed in m/s from GPS. 0 when stationary or unavailable. */
    val speed: Float = 0f,
    /** Bearing in degrees (0–360) from GPS. 0 when stationary or unavailable. */
    val bearing: Float = 0f,
    /**
     * The sender's current heartbeat interval in seconds (SOS/battery/motion aware),
     * so receivers know exactly when the next beat is due instead of guessing from
     * their own settings. Null from older app versions.
     */
    val expectedIntervalSeconds: Long? = null
)
