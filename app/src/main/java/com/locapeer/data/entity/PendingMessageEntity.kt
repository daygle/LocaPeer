package com.locapeer.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_messages")
data class PendingMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val relayUrl: String,
    val content: String, // The full Nostr JSON message
    val createdAt: Long = System.currentTimeMillis()
)
