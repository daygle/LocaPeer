package com.locapeer.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_requests")
data class PendingRequestEntity(
    @PrimaryKey val senderPubkey: String,
    val senderName: String,
    val senderRelayUrl: String,
    val isRoleChange: Boolean = false,
    val requestedRole: String? = null,
    val receivedAt: Long = System.currentTimeMillis()
)
