package com.locapeer.messaging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingFreshnessTest {
    private val now = 1_800_000_000L

    @Test fun `a typing event from moments ago shows the indicator`() {
        assertTrue(isFreshTypingEvent(now - 5, now))
        assertTrue(isFreshTypingEvent(now + 5, now))
    }

    @Test fun `a replayed old typing event is ignored`() {
        assertFalse(isFreshTypingEvent(now - 31, now))
        assertFalse(isFreshTypingEvent(now - 24 * 3600, now))
    }
}
