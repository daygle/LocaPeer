package com.locapeer.data.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * One emoji reaction from one participant on one circle message.
 *
 * Circle chats keep a row per (message, reactor) so several members can react independently and
 * every bubble can show who reacted. 1:1 chats keep using the single `reaction` / `peerReaction`
 * columns on [MessageEntity], where there is only ever one person on each side.
 */
@Entity(
    tableName = "message_reactions",
    primaryKeys = ["messageId", "reactorPubkey"],
    indices = [Index("messageId")]
)
data class MessageReactionEntity(
    val messageId: String,
    val reactorPubkey: String,
    val emoji: String,
    val updatedAt: Long
)
