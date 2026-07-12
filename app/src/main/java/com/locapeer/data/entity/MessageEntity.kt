package com.locapeer.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Delivery states for outgoing messages. */
enum class DeliveryState { SENDING, SENT, DELIVERED, READ }

@Entity(
    tableName = "messages",
    indices = [Index("peerId"), Index("timestamp"), Index("groupId")]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    /**
     * Thread key. For a 1:1 conversation this is the other participant's deviceId (pubkey).
     * For a circle/group conversation it is the circle id, and [groupId] is set to the same
     * value to mark the row as a group message.
     */
    val peerId: String,
    val senderPublicKeyHex: String,
    val content: String,
    val timestamp: Long,
    val isMine: Boolean,
    val deliveryState: String = DeliveryState.SENT.name,
    val isRead: Boolean = false,
    val nostrEventId: String = "",
    /** True when stored during a messaging block - hidden from UI until unblocked. */
    val isBlocked: Boolean = false,
    /** Non-null circle id when this message belongs to a group conversation; null for 1:1. */
    val groupId: String? = null
)
