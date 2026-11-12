package com.locapeer.subscriber

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageCatchUpTest {
    private val now = 1_800_000_000L
    private val day = 24 * 3600L

    @Test fun `a device that never synced starts from now`() {
        assertEquals(now, messageCatchUpSince(0L, now))
    }

    @Test fun `catch-up starts shortly before the saved sync point`() {
        assertEquals(now - day - 300, messageCatchUpSince(now - day, now))
    }

    @Test fun `catch-up never reaches back more than thirty days`() {
        assertEquals(now - 30 * day, messageCatchUpSince(now - 90 * day, now))
    }

    @Test fun `a sync point ahead of the clock does not ask for the future`() {
        assertEquals(now, messageCatchUpSince(now + day, now))
    }
}
