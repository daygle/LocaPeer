package com.locapeer.data.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Membership join row: one per (circle, contact) pair. [memberPubkey] is a peer's deviceId
 * (its 64-char Nostr public key), the same value used as [PeerEntity.deviceId]. A contact can
 * belong to several circles, so the primary key is the pair.
 */
@Entity(
    tableName = "circle_members",
    primaryKeys = ["circleId", "memberPubkey"],
    indices = [Index("circleId"), Index("memberPubkey")]
)
data class CircleMemberEntity(
    val circleId: String,
    val memberPubkey: String
)
