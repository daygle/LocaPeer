package com.locapeer.supervised

import kotlinx.serialization.Serializable

@Serializable
data class UnlockRequestPayload(val requestId: String, val deviceName: String)

@Serializable
data class UnlockResponsePayload(val requestId: String, val approved: Boolean)

@Serializable
data class SupervisedRegisterPayload(
    val devicePubkeyHex: String,
    val deviceName: String,
    val deviceRelayUrl: String
)

@Serializable
data class SupervisedRegisterResponsePayload(
    val devicePubkeyHex: String,
    val accepted: Boolean
)
