package com.locapeer.circles

import kotlinx.serialization.Serializable

/**
 * Payload of a [com.locapeer.nostr.NostrEventKind.CIRCLE_LEAVE] control message. A non-owner sends
 * it (NIP-44 encrypted, p-tagged) to a circle's owner when they leave, so the owner drops them from
 * the circle's membership; the reduced membership then propagates to the other members on the
 * owner's next circle message. Only [gid] is carried - the leaver's identity is the signed
 * event.pubkey, so it can't be spoofed.
 */
@Serializable
data class CircleLeavePayload(
    /** Stable circle id (UUID) the sender is leaving. */
    val gid: String
)
