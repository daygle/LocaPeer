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
     * Role of the peer relative to this device.
     * BROADCASTER = we track them.
     * SUBSCRIBER = they track us.
     * MUTUAL = we track each other.
     */
    val role: String,
    val addedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val ROLE_BROADCASTER = "BROADCASTER"
        const val ROLE_SUBSCRIBER = "SUBSCRIBER"
        const val ROLE_MUTUAL = "MUTUAL"
    }
}
