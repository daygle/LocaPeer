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
    /**
     * Per-recipient event ids for circle (group) messages. Stored as a CSV string of
     * `memberPubHex:eventIdHex` pairs separated by commas, e.g.
     *   "abcdef0123...:deadbeef...,deadbeef...:cafebabe..."
     * Both halves are exactly 64-char lowercase hex so the `:` and `,` delimiters cannot
     * collide with payload contents. Empty string when the row is a 1:1 message or the
     * fan-out recorded nothing (e.g. only-self circle). Populated at send time by
     * [com.locapeer.messaging.MessagingViewModel.sendGroupMessage] /
     * [com.locapeer.messaging.MessagingViewModel.sendGroupMedia], then read at delete
     * time by [com.locapeer.messaging.MessagingViewModel.deleteMessageFromRemote] to
     * publish N separate NIP-09 kind-5 events (one per recipient).
     *
     * Note: the tracking key is just `memberPub` rather than the `(circleId, memberPub)`
     * composite one might expect, because each circle-message row in `messages` already
     * belongs to exactly one circle via this row's [groupId] column. There is no scenario
     * where the same `memberPub:eventId` pair lives under two different circles, so the
     * outer circle identity is implicit in the parent row and we don't need to repeat it.
     */
    val nostrEventIdsByMember: String = "",
    /** True when stored during a messaging block - hidden from UI until unblocked. */
    val isBlocked: Boolean = false,
    /** Non-null circle id when this message belongs to a group conversation; null for 1:1. */
    val groupId: String? = null,
    /** TEXT (default), IMAGE, AUDIO, or FILE. Drives how the chat bubble renders the row. */
    val contentType: String = MessageType.TEXT,
    /**
     * For IMAGE/AUDIO/FILE rows: Base64 (NO_WRAP) of the size-capped media bytes (JPEG for images,
     * AAC/m4a for audio, the file's raw bytes for FILE). Kept inline in the row so message
     * retention, remote purge and backup all continue to work unchanged. Null for text rows.
     */
    val mediaBase64: String? = null,
    /** For AUDIO rows: recording length in milliseconds, shown on the playback bubble. */
    val mediaDurationMs: Long? = null,
    /** For FILE rows: the original display name (e.g. "notes.pdf"), shown on the bubble and used on open. */
    val mediaFilename: String? = null,
    /** For FILE rows: the MIME type (e.g. "application/pdf"), used to launch the right viewer on open. */
    val mediaMimeType: String? = null
)

/** Values for [MessageEntity.contentType]. Plain strings (not an enum) to keep the Room column simple. */
object MessageType {
    const val TEXT = "TEXT"
    const val IMAGE = "IMAGE"
    const val AUDIO = "AUDIO"
    const val FILE = "FILE"
}
