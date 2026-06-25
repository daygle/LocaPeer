package com.locapeer.messaging

import kotlinx.serialization.Serializable

@Serializable
data class ReadReceiptPayload(val eventIds: List<String>)

@Serializable
data class DeliveryAckPayload(val eventId: String)
