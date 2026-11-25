package com.locapeer.beacon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttemptCadenceTest {

    private val base = 120_000L

    @Test
    fun `first rejection does not slow the cadence`() {
        assertEquals(base, AttemptCadence.nextDelayMs(base, consecutiveRejections = 0))
        assertEquals(base, AttemptCadence.nextDelayMs(base, consecutiveRejections = 1))
    }

    @Test
    fun `a run of rejections backs off gradually`() {
        assertEquals(base * 5 / 4, AttemptCadence.nextDelayMs(base, consecutiveRejections = 2))
        assertEquals(base * 3 / 2, AttemptCadence.nextDelayMs(base, consecutiveRejections = 4))
    }

    @Test
    fun `backoff is capped so a run of rejections cannot stall collection`() {
        val capped = base * 2
        assertEquals(capped, AttemptCadence.nextDelayMs(base, consecutiveRejections = 8))
        assertEquals(capped, AttemptCadence.nextDelayMs(base, consecutiveRejections = 500))
    }

    @Test
    fun `delay never shrinks as rejections accumulate`() {
        var previous = 0L
        for (rejections in 0..12) {
            val delay = AttemptCadence.nextDelayMs(base, rejections)
            assertTrue("delay for $rejections rejections was $delay, below $previous", delay >= previous)
            previous = delay
        }
    }

    @Test
    fun `an accepted attempt is scheduled at the base interval again`() {
        assertEquals(base, AttemptCadence.nextDelayMs(base, consecutiveRejections = 0))
    }

    @Test
    fun `a zero base interval stays zero`() {
        assertEquals(0L, AttemptCadence.nextDelayMs(0L, consecutiveRejections = 4))
    }
}
