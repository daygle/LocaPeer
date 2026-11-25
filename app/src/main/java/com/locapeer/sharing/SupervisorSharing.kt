package com.locapeer.sharing

/**
 * True when [peerPubkey] is this device's active supervisor. While supervised, location must keep
 * reaching the supervisor: the per-contact pause and the per-contact schedule do not apply to them.
 *
 * The settings screens lock both controls for the supervisor, but that only stops new changes. A
 * pause or an empty schedule set before supervision began would otherwise keep every heartbeat
 * from the supervisor while supervision reports itself as active, so the sender checks this too.
 */
internal fun isActiveSupervisor(supervisionActive: Boolean, supervisorPubkey: String, peerPubkey: String): Boolean =
    supervisionActive && supervisorPubkey.isNotEmpty() && supervisorPubkey.equals(peerPubkey, ignoreCase = true)
