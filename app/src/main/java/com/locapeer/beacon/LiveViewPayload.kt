package com.locapeer.beacon

import kotlinx.serialization.Serializable

/**
 * Plaintext of a `LIVE_VIEW_REQUEST` (kind 1060), NIP-44 encrypted per recipient exactly
 * like a heartbeat. Carries no location - it only tells the recipient "I'm looking at your
 * map, please broadcast live for a moment."
 *
 * [deviceId] is the sender's own pubkey and is checked against the signed event's pubkey on
 * receipt (see the request handler): a valid signature already proves authorship, and this
 * cross-check keeps the semantics identical to the app's other control payloads.
 * [sentAtMs] is the sender's wall clock at send time, used only as a freshness guard so a
 * replayed old request can't silently re-trigger live mode.
 */
@Serializable
data class LiveViewPayload(
    val deviceId: String,
    val sentAtMs: Long
)
