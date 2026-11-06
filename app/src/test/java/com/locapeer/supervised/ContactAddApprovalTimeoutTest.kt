package com.locapeer.supervised

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactAddApprovalTimeoutTest {
    @Test fun `restored approval keeps remaining time from its original request time`() {
        assertEquals(60_000L, contactAddTimeoutDelayMs(100_000L, 100_000L))
        assertEquals(35_000L, contactAddTimeoutDelayMs(100_000L, 125_000L))
        assertEquals(0L, contactAddTimeoutDelayMs(100_000L, 160_000L))
    }
}
