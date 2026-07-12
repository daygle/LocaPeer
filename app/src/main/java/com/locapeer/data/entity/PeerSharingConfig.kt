package com.locapeer.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.locapeer.sharing.ScheduleRule
import com.locapeer.sharing.toScheduleRules

enum class PrecisionMode { EXACT, SUBURB }

@Entity(tableName = "peer_sharing_config")
data class PeerSharingConfig(
    @PrimaryKey val peerDeviceId: String,
    val sharingEnabled: Boolean = true,
    val precisionMode: String = PrecisionMode.EXACT.name,
    /** JSON-encoded List<ScheduleRule>. Empty array = always share. */
    val scheduleRulesJson: String = "[]",
    val isSosContact: Boolean = false,
    /** True when this device is one that the local user supervises (supervisor side of supervised mode). */
    val isMySupervised: Boolean = false,
    /** Notify the local user when this peer stops reporting their location. Off by default. */
    val notifyOnMissedHeartbeat: Boolean = false,
    /** How long this contact keeps my location data on their device. 0 = forever. */
    val retentionDaysLocation: Int = 30,
    /** How long this contact keeps my messages on their device. 0 = forever. */
    val retentionDaysMessages: Int = 0,
    /**
     * One-off temporary share expiry, expressed as Unix epoch SECONDS. While the current
     * wall-clock time is below this value, sharing is allowed for this peer regardless of
     * the recurring [scheduleRules] gate. null = no active temporary share.
     *
     * Epoch seconds (not millis) keep the value readable in `adb shell sqlite3`, so a
     * support engineer can `select temporary_share_ends_at_epoch_seconds from
     * peer_sharing_config` and immediately see a wall-clock time. The HeartbeatService
     * and worker convert via `System.currentTimeMillis() / 1000`.
     */
    val temporaryShareEndsAtEpochSeconds: Long? = null
)

fun PeerSharingConfig.scheduleRules(): List<ScheduleRule> = scheduleRulesJson.toScheduleRules()
