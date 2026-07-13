package com.locapeer.beacon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixSelectorTest {

    // ~0.001° latitude ≈ 111m; keep test geometry near the equator so
    // longitude degrees convert the same way.
    private fun latOffsetForMetres(m: Double) = m / 111_000.0

    private fun ns(seconds: Long) = seconds * 1_000_000_000L

    private val baseLat = 0.0
    private val baseLng = 0.0

    @Test
    fun `first fix is always selected`() {
        assertTrue(FixSelector().select(-37.8, 144.9, 30f, ns(1)))
    }

    @Test
    fun `sharper fix replaces the held one`() {
        val s = FixSelector()
        assertTrue(s.select(baseLat, baseLng, 30f, ns(0)))
        // A tighter fix at almost the same spot: unambiguously better to report.
        assertTrue(s.select(baseLat, baseLng, 8f, ns(15)))
    }

    @Test
    fun `equally accurate newer fix is taken`() {
        val s = FixSelector()
        assertTrue(s.select(baseLat, baseLng, 12f, ns(0)))
        assertTrue(s.select(baseLat, baseLng, 12f, ns(30)))
    }

    @Test
    fun `coarser fix is held off while the sharp fix stays current`() {
        val s = FixSelector()
        // Sharp 8m fix, then a 40m fix moments later a few metres away (still within
        // combined uncertainty): the stationary pin should stay on the sharp fix.
        assertTrue(s.select(baseLat, baseLng, 8f, ns(0)))
        assertFalse(s.select(baseLat + latOffsetForMetres(5.0), baseLng, 40f, ns(20)))
        // And a second coarse fix a little later is still rejected.
        assertFalse(s.select(baseLat + latOffsetForMetres(6.0), baseLng, 45f, ns(40)))
    }

    @Test
    fun `coarser fix wins once the device has moved past combined uncertainty`() {
        val s = FixSelector()
        assertTrue(s.select(baseLat, baseLng, 8f, ns(0)))
        // 200m away at 40m accuracy: displacement (200m) exceeds 8+40, so the device
        // genuinely moved and the fresh fix must win despite being coarser.
        assertTrue(s.select(baseLat + latOffsetForMetres(200.0), baseLng, 40f, ns(20)))
    }

    @Test
    fun `held fix is released once it is stale even without movement`() {
        val s = FixSelector()
        assertTrue(s.select(baseLat, baseLng, 8f, ns(0)))
        // Same spot but past MAX_HOLD_MS: a fresher (coarser) fix is taken as a backstop
        // so the reported position can't lag reality indefinitely.
        val pastHold = FixSelector.MAX_HOLD_MS / 1000 + 1
        assertTrue(s.select(baseLat, baseLng, 30f, ns(pastHold)))
    }

    @Test
    fun `stationary hold keeps the sharp fix past the age backstop`() {
        val s = FixSelector()
        assertTrue(s.select(baseLat, baseLng, 8f, ns(0)))
        // At the 5-min stationary poll cadence every fix is past MAX_HOLD_MS; with the
        // hold engaged a coarse network fix at the same place must NOT take the pin.
        assertFalse(s.select(baseLat + latOffsetForMetres(50.0), baseLng, 800f, ns(300), stationaryHold = true))
        // Nor the next one, arbitrarily far past the backstop.
        assertFalse(s.select(baseLat + latOffsetForMetres(80.0), baseLng, 700f, ns(1200), stationaryHold = true))
    }

    @Test
    fun `stationary hold still yields to provable movement`() {
        val s = FixSelector()
        assertTrue(s.select(baseLat, baseLng, 8f, ns(0)))
        // 1200m away at 800m accuracy: displacement exceeds 8+800, the device provably
        // moved, so the fresh fix wins even with the hold engaged.
        assertTrue(s.select(baseLat + latOffsetForMetres(1200.0), baseLng, 800f, ns(300), stationaryHold = true))
    }

    @Test
    fun `stationary hold still takes a sharper fix`() {
        val s = FixSelector()
        assertTrue(s.select(baseLat, baseLng, 30f, ns(0)))
        assertTrue(s.select(baseLat, baseLng, 8f, ns(300), stationaryHold = true))
    }

    @Test
    fun `age backstop still applies without stationary hold`() {
        val s = FixSelector()
        assertTrue(s.select(baseLat, baseLng, 8f, ns(0)))
        val pastHold = FixSelector.MAX_HOLD_MS / 1000 + 1
        assertTrue(s.select(baseLat, baseLng, 30f, ns(pastHold), stationaryHold = false))
    }

    @Test
    fun `reset makes the next fix take unconditionally`() {
        val s = FixSelector()
        assertTrue(s.select(baseLat, baseLng, 8f, ns(0)))
        s.reset()
        // Without reset this coarser, un-moved, fresh fix would be held off; after reset
        // there is no baseline, so it is taken.
        assertTrue(s.select(baseLat, baseLng, 50f, ns(10)))
    }
}
