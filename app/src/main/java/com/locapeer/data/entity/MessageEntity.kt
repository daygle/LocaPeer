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
    val groupId: String? = null,
    /** TEXT (default), IMAGE, or AUDIO. Drives how the chat bubble renders the row. */
    val contentType: String = MessageType.TEXT,
    /**
     * For IMAGE/AUDIO rows: Base64 (NO_WRAP) of the compressed, size-capped media bytes (JPEG for
     * images, AAC/m4a for audio). Kept inline in the row so message retention, remote purge and
     * backup all continue to work unchanged. Null for text rows.
     */
    val mediaBase64: String? = null,
    /** For AUDIO rows: recording length in milliseconds, shown on the playback bubble. */
    val mediaDurationMs: Long? = null
)

/** Values for [MessageEntity.contentType]. Plain strings (not an enum) to keep the Room column simple. */
object MessageType {
    const val TEXT = "TEXT"
    const val IMAGE = "IMAGE"
    const val AUDIO = "AUDIO"
}
