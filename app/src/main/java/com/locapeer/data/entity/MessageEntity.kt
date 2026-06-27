package com.locapeer.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Delivery states for outgoing messages. */
enum class DeliveryState { SENDING, SENT, DELIVERED, READ }

@Entity(
    tableName = "messages",
    indices = [Index("peerId"), Index("timestamp")]
)
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
    val nostrEventId: String = "",
    /** True when stored during a messaging block - hidden from UI until unblocked. */
    val isBlocked: Boolean = false
)
