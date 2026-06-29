package com.locapeer.invite

import kotlinx.serialization.Serializable

@Serializable
data class InviteData(
    val publicKeyHex: String,
    val displayName: String,
    val relayUrl: String,
    val deviceId: String
)

/** Sent by the scanner to the scannee asking to track them back. */
@Serializable
data class TrackRequestPayload(
    val senderPublicKeyHex: String,
    val senderDisplayName: String,
    val senderDeviceId: String,
    val senderRelayUrl: String,
    val isRoleChange: Boolean = false,
    val requestedRole: String? = null
)

/** Sent back when the recipient accepts a track request. */
@Serializable
data class TrackAcceptPayload(
    val acceptorPublicKeyHex: String,
    val acceptorDisplayName: String,
    val acceptorDeviceId: String,
    val acceptorRelayUrl: String,
    val acceptedRole: String? = null
)

/** Sent back when the recipient declines a track request. */
@Serializable
data class TrackDeclinePayload(
    val declinerPublicKeyHex: String,
    val declinerDeviceId: String
)
