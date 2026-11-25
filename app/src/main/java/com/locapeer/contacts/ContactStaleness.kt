package com.locapeer.contacts

import com.locapeer.sharing.SharingSchedule

/**
 * What a contact row should say about the position we hold for them.
 *
 * The three states are deliberately distinct: a contact who has never shared a position is
 * not the same as one who asked us to stop receiving updates (a kind-1072 pause notice, or
 * a planned quiet window advertised in their last heartbeat). Without that distinction a
 * paused contact's last pin simply stops moving, which looks identical to a broken app.
 */
enum class ContactUpdateState {
    /** No position has ever been received from this contact. */
    NO_UPDATE_YET,

    /** The contact announced that updates stopped on purpose. */
    STOPPED_SHARING,

    /** A position has been received; its age should be shown. */
    LAST_UPDATED,
}

/** Pure classifier so the label choice is unit-testable without Compose or a database. */
object ContactStaleness {

    /**
     * [lastUpdateMs] is the receiver's own arrival time for the newest stored position, not
     * the sender's clock, so the age stays honest across inter-device clock skew.
     * [quietUntilMs] is the contact's announced planned gap (0 = none).
     */
    fun evaluate(nowMs: Long, lastUpdateMs: Long?, quietUntilMs: Long): ContactUpdateState = when {
        isStoppedSharing(nowMs, quietUntilMs) -> ContactUpdateState.STOPPED_SHARING
        lastUpdateMs == null -> ContactUpdateState.NO_UPDATE_YET
        else -> ContactUpdateState.LAST_UPDATED
    }

    /**
     * True while the contact's announced gap is current. [SharingSchedule.QUIET_INDEFINITELY]
     * means they stopped with no planned resume. The comparison is arranged so a peer-supplied
     * Long.MAX_VALUE cannot overflow.
     */
    private fun isStoppedSharing(nowMs: Long, quietUntilMs: Long): Boolean =
        quietUntilMs != 0L &&
            (quietUntilMs == SharingSchedule.QUIET_INDEFINITELY || nowMs < quietUntilMs)
}
