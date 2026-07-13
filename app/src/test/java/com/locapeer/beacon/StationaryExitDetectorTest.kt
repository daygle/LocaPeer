package com.locapeer.beacon

import com.locapeer.beacon.StationaryExitDetector.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StationaryExitDetectorTest {

    // ~0.001° latitude ≈ 111m; keep test geometry near the equator so
    // longitude degrees convert the same way (mirrors FixSelectorTest).
    private fun latOffsetForMetres(m: Double) = m / 111_000.0

    private fun ns(seconds: Long) = seconds * 1_000_000_000L

    /** Anchored detector at the origin with 20m accuracy. With 30m-accuracy fixes the
     *  exit threshold is 20 + 30 + 150 = 200m (see MotionMath.shouldExitStationary). */
    private fun anchored() = StationaryExitDetector().apply { setAnchor(0.0, 0.0, 20f) }

    @Test
    fun `no anchor means no verdict`() {
        assertEquals(Verdict.NO_ANCHOR, StationaryExitDetector().evaluate(0.0, 0.0, 30f, ns(0)))
    }

    @Test
    fun `fix inside the exit threshold is held`() {
        val d = anchored()
        assertEquals(Verdict.HELD, d.evaluate(latOffsetForMetres(100.0), 0.0, 30f, ns(300)))
        assertTrue(d.hasAnchor)
        assertFalse(d.hasPendingCandidate)
    }

    @Test
    fun `strictly better fix at the same place tightens the anchor`() {
        val d = anchored()
        assertEquals(Verdict.TIGHTENED, d.evaluate(latOffsetForMetres(10.0), 0.0, 5f, ns(300)))
        // The tightened anchor (5m accuracy) shrinks the exit threshold to
        // 5 + 30 + 150 = 185m: a 190m fix now exits where it wouldn't have before.
        assertEquals(Verdict.CANDIDATE, d.evaluate(latOffsetForMetres(200.0), 0.0, 30f, ns(600)))
    }

    @Test
    fun `single far fix is only a candidate, a consistent second fix confirms the exit`() {
        val d = anchored()
        assertEquals(Verdict.CANDIDATE, d.evaluate(latOffsetForMetres(500.0), 0.0, 30f, ns(300)))
        assertTrue(d.hasPendingCandidate)
        // 15s later, 100m further along: implied speed from the candidate is
        // (100 - 30 - 30) / 15 ≈ 2.7 m/s - travel-consistent, so the exit is real.
        assertEquals(Verdict.EXIT, d.evaluate(latOffsetForMetres(600.0), 0.0, 30f, ns(315)))
        assertFalse(d.hasAnchor)
        assertFalse(d.hasPendingCandidate)
        // With the anchor cleared, later fixes carry no verdict.
        assertEquals(Verdict.NO_ANCHOR, d.evaluate(latOffsetForMetres(700.0), 0.0, 30f, ns(330)))
    }

    @Test
    fun `glitch is discarded when the next fix is back at the anchor`() {
        val d = anchored()
        assertEquals(Verdict.CANDIDATE, d.evaluate(latOffsetForMetres(5000.0), 0.0, 30f, ns(300)))
        // Next fix is home again: the far point was a glitch; still stationary.
        assertEquals(Verdict.HELD, d.evaluate(latOffsetForMetres(50.0), 0.0, 30f, ns(315)))
        assertFalse(d.hasPendingCandidate)
        // A later far fix starts corroboration from scratch rather than pairing
        // with the long-discarded glitch.
        assertEquals(Verdict.CANDIDATE, d.evaluate(latOffsetForMetres(500.0), 0.0, 30f, ns(330)))
    }

    @Test
    fun `travel-inconsistent far fixes do not corroborate each other`() {
        val d = anchored()
        // Two glitches in opposite directions, 15s apart: 10km implied travel is
        // far past plausible speed, so the second replaces the first as candidate.
        assertEquals(Verdict.CANDIDATE, d.evaluate(latOffsetForMetres(5000.0), 0.0, 30f, ns(300)))
        assertEquals(Verdict.CANDIDATE, d.evaluate(latOffsetForMetres(-5000.0), 0.0, 30f, ns(315)))
        assertTrue(d.hasPendingCandidate)
        // A fix consistent with the REPLACED candidate confirms against it.
        assertEquals(Verdict.EXIT, d.evaluate(latOffsetForMetres(-5050.0), 0.0, 30f, ns(330)))
    }

    @Test
    fun `tightening discards a pending candidate`() {
        val d = anchored()
        assertEquals(Verdict.CANDIDATE, d.evaluate(latOffsetForMetres(500.0), 0.0, 30f, ns(300)))
        // A sharp fix contained in the anchor circle: strong evidence the device
        // never left; the candidate was a glitch.
        assertEquals(Verdict.TIGHTENED, d.evaluate(0.0, 0.0, 5f, ns(315)))
        assertFalse(d.hasPendingCandidate)
        assertEquals(Verdict.CANDIDATE, d.evaluate(latOffsetForMetres(500.0), 0.0, 30f, ns(330)))
    }

    @Test
    fun `re-anchoring resets any pending candidate`() {
        val d = anchored()
        assertEquals(Verdict.CANDIDATE, d.evaluate(latOffsetForMetres(500.0), 0.0, 30f, ns(300)))
        d.setAnchor(0.0, 0.0, 20f)
        assertFalse(d.hasPendingCandidate)
        assertEquals(Verdict.CANDIDATE, d.evaluate(latOffsetForMetres(500.0), 0.0, 30f, ns(315)))
    }
}
