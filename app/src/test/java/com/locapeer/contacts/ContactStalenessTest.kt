package com.locapeer.contacts

import com.locapeer.sharing.SharingSchedule
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactStalenessTest {

    private val now = 1_000_000_000L

    @Test
    fun `a contact with no position is distinct from one that stopped sharing`() {
        assertEquals(
            ContactUpdateState.NO_UPDATE_YET,
            ContactStaleness.evaluate(nowMs = now, lastUpdateMs = null, quietUntilMs = 0L)
        )
        assertEquals(
            ContactUpdateState.STOPPED_SHARING,
            ContactStaleness.evaluate(nowMs = now, lastUpdateMs = null, quietUntilMs = SharingSchedule.QUIET_INDEFINITELY)
        )
    }

    @Test
    fun `a paused contact keeps the pause label even with an existing position`() {
        assertEquals(
            ContactUpdateState.STOPPED_SHARING,
            ContactStaleness.evaluate(nowMs = now, lastUpdateMs = now - 60_000, quietUntilMs = now + 60_000)
        )
    }

    @Test
    fun `a position that is still arriving is reported with its age`() {
        assertEquals(
            ContactUpdateState.LAST_UPDATED,
            ContactStaleness.evaluate(nowMs = now, lastUpdateMs = now - 120_000, quietUntilMs = 0L)
        )
    }

    @Test
    fun `a planned gap that has ended no longer suppresses the update label`() {
        assertEquals(
            ContactUpdateState.LAST_UPDATED,
            ContactStaleness.evaluate(nowMs = now, lastUpdateMs = now - 120_000, quietUntilMs = now - 1)
        )
        // The gap time itself is the resume point, so it is no longer "stopped".
        assertEquals(
            ContactUpdateState.LAST_UPDATED,
            ContactStaleness.evaluate(nowMs = now, lastUpdateMs = now - 120_000, quietUntilMs = now)
        )
    }

    @Test
    fun `an indefinitely paused contact with no position still reports the pause`() {
        assertEquals(
            ContactUpdateState.STOPPED_SHARING,
            ContactStaleness.evaluate(
                nowMs = now,
                lastUpdateMs = now - 86_400_000,
                quietUntilMs = SharingSchedule.QUIET_INDEFINITELY
            )
        )
    }
}
