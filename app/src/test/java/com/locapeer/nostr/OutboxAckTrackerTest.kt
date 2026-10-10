package com.locapeer.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxAckTrackerTest {
    @Test fun `an OK resolves exactly the event it names`() {
        val tracker = OutboxAckTracker(ackTimeoutMs = 30_000)
        tracker.sent("e1", "msg1", pendingRowId = 7, nowMs = 0)
        tracker.sent("e2", "msg2", pendingRowId = null, nowMs = 0)
        assertEquals(7L, tracker.acknowledged("e1")?.pendingRowId)
        assertNull("already resolved", tracker.acknowledged("e1"))
        assertEquals(listOf("e2"), tracker.drainOnClose().map { it.eventId })
    }

    @Test fun `a close hands back every unanswered event once`() {
        val tracker = OutboxAckTracker(ackTimeoutMs = 30_000)
        tracker.sent("live", "m", pendingRowId = null, nowMs = 0)
        tracker.sent("row", "m", pendingRowId = 3, nowMs = 0)
        assertTrue(tracker.isRowInFlight(3))
        assertEquals(setOf("live", "row"), tracker.drainOnClose().map { it.eventId }.toSet())
        assertTrue(tracker.drainOnClose().isEmpty())
        assertFalse(tracker.isRowInFlight(3))
    }

    @Test fun `events a relay never answers expire instead of piling up`() {
        val tracker = OutboxAckTracker(ackTimeoutMs = 1_000)
        tracker.sent("old", "m", pendingRowId = 1, nowMs = 0)
        tracker.sent("new", "m", pendingRowId = 2, nowMs = 900)
        assertEquals(listOf("old"), tracker.expire(nowMs = 1_000).map { it.eventId })
        assertEquals(listOf("new"), tracker.drainOnClose().map { it.eventId })
    }

    @Test fun `only final NIP-01 rejections stop retries`() {
        for (m in listOf("duplicate: have it", "invalid: bad sig", "blocked: no", "pow: 20 bits", "restricted: auth")) {
            assertTrue(m, OutboxAckTracker.isFinalRejection(m))
        }
        for (m in listOf("rate-limited: slow down", "error: db down", "", "no prefix here")) {
            assertFalse(m, OutboxAckTracker.isFinalRejection(m))
        }
    }
}
