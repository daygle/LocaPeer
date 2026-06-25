package com.locapeer.beacon

import kotlinx.serialization.Serializable

@Serializable
data class PurgeRequestPayload(
    val deviceId: String,
    val deleteOlderThanMs: Long
)
