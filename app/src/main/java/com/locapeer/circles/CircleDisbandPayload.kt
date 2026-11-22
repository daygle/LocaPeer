package com.locapeer.circles

import kotlinx.serialization.Serializable

/**
 * Payload of a [com.locapeer.nostr.NostrEventKind.CIRCLE_DISBAND] control message. A circle's
 * creator sends it (NIP-44 encrypted, p-tagged) to every member when they pick "Delete for
 * Everyone", so each member's device removes its copy of the circle and its thread. Only [gid] is
 * carried - the disbanding creator's identity is the signed event.pubkey, and recipients honour the
 * message only when that pubkey is the circle's recorded creator.
 */
@Serializable
data class CircleDisbandPayload(
    /** Stable circle id (UUID) the sender has disbanded. */
    val gid: String
)
