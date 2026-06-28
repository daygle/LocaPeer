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
     * RECEIVE = we track them.
     * SEND = they track us.
     * SEND_RECEIVE = we track each other.
     */
    val role: String,
    val addedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val ROLE_RECEIVE = "RECEIVE"
        const val ROLE_SEND = "SEND"
        const val ROLE_SEND_RECEIVE = "SEND_RECEIVE"
    }
}
