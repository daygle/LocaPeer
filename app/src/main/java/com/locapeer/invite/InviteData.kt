package com.locapeer.invite

import kotlinx.serialization.Serializable

@Serializable
data class InviteData(
    val publicKeyHex: String,
    val displayName: String,
    val relayUrl: String,
    val deviceId: String
)

@Serializable
data class InviteResponse(
    val subscriberPublicKeyHex: String,
    val subscriberDisplayName: String,
    val subscriberDeviceId: String
)
