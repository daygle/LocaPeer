package com.locapeer.messaging

import kotlinx.serialization.Serializable

@Serializable
data class ReadReceiptPayload(val eventIds: List<String>) {
    companion object {
        /** Ids per receipt this app sends; larger backlogs go out as several receipts. */
        const val SEND_CHUNK = 200
        /** Ids a received receipt may carry. Above [SEND_CHUNK] so receipts from older app
         *  versions (which sent a whole backlog in one) still apply, but bounded so one receipt
         *  can't force an unbounded number of database updates. */
        const val MAX_IDS = 1000
    }
}

@Serializable
data class DeliveryAckPayload(val eventId: String)

/**
 * Emoji reaction to an existing message, delivered as an encrypted [NostrEventKind.MESSAGE_REACTION]
 * event.
 *
 * [targetId] identifies the target on the recipient's side: for a 1:1 message it is the target's
 * Nostr event id, and for a circle message it is the author's logical message id (carried as
 * [com.locapeer.messaging.GroupMessage.mid]). An empty [emoji] clears the reaction.
 */
@Serializable
data class ReactionPayload(
    val targetId: String,
    val emoji: String,
    /** Circle id when the target belongs to a circle thread; null for a 1:1 message. */
    val groupId: String? = null
) {
    companion object {
        /** A reaction is one emoji (plus optional modifiers); cap it so a hostile payload can't
         *  bloat a row or the bubble layout. */
        const val MAX_EMOJI_CHARS = 16
    }
}
