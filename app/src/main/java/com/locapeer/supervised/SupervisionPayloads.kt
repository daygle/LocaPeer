package com.locapeer.supervised

import kotlinx.serialization.Serializable

@Serializable
data class UnlockRequestPayload(val requestId: String, val deviceName: String)

@Serializable
data class UnlockResponsePayload(val requestId: String, val approved: Boolean)
