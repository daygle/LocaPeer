package com.locapeer.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val deviceId: String,
    val displayName: String,
    val publicKeyHex: String,
    val relayUrl: String,
    /**
     * Location role relative to this device.
     * NONE         = no location sharing (messaging-only contact).
     * RECEIVE      = we receive their location.
     * SEND         = we send our location to them.
     * SEND_RECEIVE = we exchange location with each other.
     */
    val locationRole: String,
    /** Whether this device wants to receive messages from this peer. */
    val messagingEnabled: Boolean = true,
    val isArchived: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val ROLE_NONE = "NONE"
        const val ROLE_RECEIVE = "RECEIVE"
        const val ROLE_SEND = "SEND"
        const val ROLE_SEND_RECEIVE = "SEND_RECEIVE"
    }
}
