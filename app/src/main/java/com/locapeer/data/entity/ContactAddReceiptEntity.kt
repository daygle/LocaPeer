package com.locapeer.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Durable evidence about one step of a supervised contact add, keyed by the queued intent so
 * a resumed add can tell what already happened instead of repeating an irreversible effect.
 *
 *  - Inbound accept: [appliedAt] is written in the same transaction as the accept, so a lost
 *    TRACK_ACCEPT reply can be resent without re-applying - and without guessing from the
 *    peer row (a pre-existing peer can match the role after a *declined* request).
 *  - Outbound scan: [requestSentAt] is written as soon as the TRACK_REQUEST is published, so
 *    a resume after the add completed does not send it to the contact a second time.
 */
@Entity(tableName = "contact_add_receipts")
data class ContactAddReceiptEntity(
    @PrimaryKey val receiptId: String,
    /** Inbound: the accept transaction committed at this time. */
    val appliedAt: Long? = null,
    /** Outbound: the TRACK_REQUEST was published at this time. */
    val requestSentAt: Long? = null
)
