package com.locapeer.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class PrecisionMode { EXACT, SUBURB }

@Entity(tableName = "peer_sharing_config")
data class PeerSharingConfig(
    @PrimaryKey val peerDeviceId: String,
    val sharingEnabled: Boolean = true,
    val precisionMode: String = PrecisionMode.EXACT.name,
    val scheduleEnabled: Boolean = false,
    /** Bitmask: bit 0 = Monday … bit 6 = Sunday. Default = all days (127). */
    val scheduleDays: Int = 0b1111111,
    /** Minutes from midnight for the start of the sharing window. */
    val scheduleStartMinute: Int = 0,
    /** Minutes from midnight for the end of the sharing window. */
    val scheduleEndMinute: Int = 1439,
    val isSosContact: Boolean = true,
    /** When false, incoming messages from this peer are silently dropped. */
    val messagingEnabled: Boolean = true
)
