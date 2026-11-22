package com.locapeer.subscriber

import com.locapeer.settings.LeftCircle
import org.junit.Assert.assertEquals
import org.junit.Test

class PendingCircleDisbandTest {
    private val gid = "123e4567-e89b-12d3-a456-426614174000"
    private val creator = "a".repeat(64)
    private val member = "b".repeat(64)
    private val pending = LeftCircle(gid, creator, leftAt = 1_000)

    @Test fun `the creator's own earlier message is dropped`() {
        assertEquals(
            PendingDisbandOutcome.APPLY,
            pendingDisbandOutcome(pending, creator, creator, eventCreatedAt = 900, signedVersion = 0)
        )
    }

    @Test fun `a member's copy with a valid creator signature is dropped`() {
        // A member's clock may run ahead of the creator's, so their copy's time doesn't matter.
        assertEquals(
            PendingDisbandOutcome.APPLY,
            pendingDisbandOutcome(pending, creator, member, eventCreatedAt = 5_000, signedVersion = 7)
        )
    }

    @Test fun `a newer message from the creator supersedes the notice`() {
        assertEquals(
            PendingDisbandOutcome.SUPERSEDED,
            pendingDisbandOutcome(pending, creator, creator, eventCreatedAt = 1_001, signedVersion = 0)
        )
    }

    @Test fun `an unsigned member copy cannot prove who created the circle`() {
        assertEquals(
            PendingDisbandOutcome.NOT_APPLICABLE,
            pendingDisbandOutcome(pending, creator, member, eventCreatedAt = 900, signedVersion = 0)
        )
    }

    @Test fun `a notice from someone other than the creator never applies`() {
        // A member sent the notice, but the circle belongs to someone else.
        val fromMember = LeftCircle(gid, member, leftAt = 1_000)
        assertEquals(
            PendingDisbandOutcome.NOT_APPLICABLE,
            pendingDisbandOutcome(fromMember, creator, creator, eventCreatedAt = 900, signedVersion = 3)
        )
    }
}
