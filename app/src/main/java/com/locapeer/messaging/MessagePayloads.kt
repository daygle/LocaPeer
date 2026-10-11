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
