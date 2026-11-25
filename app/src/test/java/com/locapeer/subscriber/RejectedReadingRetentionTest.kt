package com.locapeer.subscriber

import org.junit.Assert.assertEquals
import org.junit.Test

class RejectedReadingRetentionTest {

    private val day = 86_400_000L

    @Test
    fun `rejected readings are kept for seven days`() {
        val now = 1_000L * day + 37_000L
        assertEquals(now - 7 * day, RejectedReadingRetention.cutoff(now))
    }
}
