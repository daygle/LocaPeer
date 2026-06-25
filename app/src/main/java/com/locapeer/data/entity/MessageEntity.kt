package com.locapeer.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Delivery states for outgoing messages. */
enum class DeliveryState { SENDING, SENT, DELIVERED, READ }

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    /** deviceId of the other participant */
    val peerId: String,
    val senderPublicKeyHex: String,
    val content: String,
    val timestamp: Long,
    val isMine: Boolean,
    val deliveryState: String = DeliveryState.SENT.name,
    val isRead: Boolean = false,
    val nostrEventId: String = ""
)
