package com.locapeer.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val deviceId: String,
    val displayName: String,
    val publicKeyHex: String,
    val relayUrl: String,
    /**
     * Location role relative to this device.
     * NONE         = no location sharing (messaging-only contact).
     * RECEIVE      = we receive their location.
     * SEND         = we send our location to them.
     * SEND_RECEIVE = we exchange location with each other.
     */
    val locationRole: String,
    /** Whether this device wants to receive messages from this peer. */
    val messagingEnabled: Boolean = true,
    val isArchived: Boolean = false,
    /** Wall-clock time of the most recent explicit archive action, used to decide whether a
     *  late-arriving message (by its own signed timestamp) predates the archive and should
     *  not silently re-surface the conversation. */
    val archivedAt: Long = 0,
    val addedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val ROLE_NONE = "NONE"
        const val ROLE_RECEIVE = "RECEIVE"
        const val ROLE_SEND = "SEND"
        const val ROLE_SEND_RECEIVE = "SEND_RECEIVE"

        /**
         * The location role after granting reception of this peer's location, leaving any
         * existing SEND capability intact. Used when a supervised device is accepted: being
         * their supervisor implies we should receive their location, so a messaging-only
         * (NONE) or send-only (SEND) relationship is promoted to also RECEIVE. Roles that
         * already receive (RECEIVE / SEND_RECEIVE) are returned unchanged.
         */
        fun roleWithReceive(current: String): String = when (current) {
            ROLE_NONE -> ROLE_RECEIVE
            ROLE_SEND -> ROLE_SEND_RECEIVE
            else -> current
        }
    }
}
