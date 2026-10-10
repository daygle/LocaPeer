package com.locapeer.subscriber

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartbeatFlushRetryTest {
    @Test fun `failed flushes back off exponentially`() {
        assertEquals(listOf(500L, 1000L, 2000L, 4000L), (1..4).map { hbFlushRetryDelayMs(it) })
    }

    @Test fun `a flush that keeps failing gives up instead of retrying forever`() {
        assertNull(hbFlushRetryDelayMs(5))
        assertNull(hbFlushRetryDelayMs(100))
        assertNull(hbFlushRetryDelayMs(0))
    }
}
