package com.locapeer.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "heartbeats",
    // (deviceId, timestamp) serves the per-contact lookups (duplicate check, latest position,
    // day history) and plain deviceId filters alike; timestamp alone serves retention deletes.
    indices = [Index("deviceId", "timestamp"), Index("timestamp")]
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
    /** Bearing in degrees (0-360) from GPS. 0 when stationary or unavailable. */
    val bearing: Float = 0f,
    /** Altitude in metres from GPS. 0 when unavailable. */
    val altitude: Double = 0.0,
    /** Sender's heartbeat interval in seconds when this ping was sent. */
    val expectedIntervalSeconds: Long,
    /**
     * UTC epoch millis at which the provider captured the fix. [timestamp] is the moment
     * the attempt was ingested, which can differ by up to [fixAgeSeconds]. 0 on rows
     * written before v18, where the capture time was not recorded.
     */
    val capturedAt: Long = 0L,
    /**
     * The device's UTC offset in minutes at [capturedAt], so the stored UTC instant can be
     * rendered in the wall-clock time the wearer actually saw.
     */
    val capturedAtOffsetMinutes: Int = 0,
    /** Provider that produced the fix (e.g. "gps", "network", "fused"); empty if unknown. */
    val source: String = "",
    /** receivedAt - capturedAt in seconds. How old the fix already was when it arrived. */
    val fixAgeSeconds: Long = 0L,
    /**
     * [ReadingStatus.dbValue] assigned at ingest. Received peer heartbeats are always
     * accepted; only this device's own attempts can be rejected.
     */
    val status: String = ReadingStatus.ACCEPTED.dbValue,
    /** [RejectReason.code] when [status] is rejected, null otherwise. */
    val rejectReason: String? = null
) {
    /** True when this reading passed the sharing quality gate. */
    val isAccepted: Boolean get() = status == ReadingStatus.ACCEPTED.dbValue

    /** The parsed reject reason, or null for an accepted/legacy row. */
    val parsedRejectReason: RejectReason? get() = RejectReason.fromCode(rejectReason)
}
