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
    val retentionDaysMessages: Int = 0
)

fun PeerSharingConfig.scheduleRules(): List<ScheduleRule> = scheduleRulesJson.toScheduleRules()
