package com.locapeer.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val deviceId: String,
    val displayName: String,
    val publicKeyHex: String,
    val relayUrl: String,
    /** BROADCASTER = this device tracks them; SUBSCRIBER = they track us */
    val role: String,
    val addedAt: Long = System.currentTimeMillis()
)
