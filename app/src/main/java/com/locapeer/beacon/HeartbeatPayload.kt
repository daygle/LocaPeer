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
    /** Bearing in degrees (0-360) from GPS. 0 when stationary or unavailable. */
    val bearing: Float = 0f,
    /** Altitude in metres from GPS. 0 when unavailable. */
    val altitude: Double = 0.0,
    /**
     * The sender's current heartbeat interval in seconds (SOS/battery/motion aware),
     * so receivers know exactly when the next beat is due instead of guessing from
     * their own settings.
     */
    val expectedIntervalSeconds: Long,
    /**
     * True for a live-only pulse: an extra update sent to a contact watching live, between
     * the sender's normal-cadence heartbeats. Receivers keep only the latest such point per
     * contact, so a watched stretch doesn't fill history with a point every few seconds.
     * Absent (false) from older senders and on every cadence heartbeat.
     */
    val live: Boolean = false,
    /**
     * Set when sending to this recipient stops on purpose before the next heartbeat is due
     * (outside the sender's schedule): the epoch ms it resumes, or
     * [com.locapeer.sharing.SharingSchedule.QUIET_INDEFINITELY]. 0 (and absent, from older
     * senders) means updates continue. Lets the recipient hold back a missed-update alert.
     */
    val quietUntilMs: Long = 0L
)
