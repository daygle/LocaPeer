package com.locapeer.beacon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationFilterTest {

    // ~0.001° latitude ≈ 111m; keep test geometry near the equator so
    // longitude degrees convert the same way.
    private fun latOffsetForMetres(m: Double) = m / 111_000.0

    private fun ns(seconds: Long) = seconds * 1_000_000_000L

    private val baseLat = 0.0
    private val baseLng = 0.0

    private fun seeded(): LocationFilter = LocationFilter().apply {
        assertTrue(accept(baseLat, baseLng, 15f, ns(0)))
    }

    @Test
    fun `first fix is always accepted`() {
        assertTrue(LocationFilter().accept(-37.8, 144.9, 2000f, ns(5)))
    }

    @Test
    fun `normal walking-pace fixes flow through`() {
        val f = seeded()
        // ~40m in 30s at similar accuracy.
        assertTrue(f.accept(baseLat + latOffsetForMetres(40.0), baseLng, 20f, ns(30)))
        assertTrue(f.accept(baseLat + latOffsetForMetres(80.0), baseLng, 12f, ns(60)))
    }

    @Test
    fun `single coarse cell fix among good fixes is gated`() {
        val f = seeded()
        // 700m-accuracy cell fix landing 600m away: classic history outlier.
        assertFalse(f.accept(baseLat + latOffsetForMetres(600.0), baseLng, 700f, ns(30)))
        // The next good fix near the true position still flows.
        assertTrue(f.accept(baseLat + latOffsetForMetres(10.0), baseLng, 18f, ns(60)))
    }

    @Test
    fun `accuracy gate is relative so coarse-only coverage still updates`() {
        val f = LocationFilter()
        assertTrue(f.accept(baseLat, baseLng, 800f, ns(0)))
        // Everything is cell-grade here; 4x800m gate lets peers of the same
        // quality through.
        assertTrue(f.accept(baseLat + latOffsetForMetres(300.0), baseLng, 900f, ns(120)))
    }

    @Test
    fun `accurate fix after a coarse one always passes the gate`() {
        val f = LocationFilter()
        assertTrue(f.accept(baseLat, baseLng, 1500f, ns(0)))
        assertTrue(f.accept(baseLat + latOffsetForMetres(200.0), baseLng, 10f, ns(30)))
    }

    @Test
    fun `gps glitch jump is rejected`() {
        val f = seeded()
        // 5km away in 30s with tight claimed accuracy: >160 m/s implied.
        assertFalse(f.accept(baseLat + latOffsetForMetres(5000.0), baseLng, 20f, ns(30)))
        // Position recovers with the next honest fix.
        assertTrue(f.accept(baseLat + latOffsetForMetres(15.0), baseLng, 15f, ns(60)))
    }

    @Test
    fun `second fix agreeing with a jump confirms genuine relocation`() {
        val f = seeded()
        // 20km is implausible from the anchor even a minute later, so the
        // second fix can only pass by corroborating the first rejected one.
        val farLat = baseLat + latOffsetForMetres(20_000.0)
        assertFalse(f.accept(farLat, baseLng, 20f, ns(30)))
        // Next fix lands beside the rejected one: the device really is there.
        assertTrue(f.accept(farLat + latOffsetForMetres(30.0), baseLng, 20f, ns(60)))
    }

    @Test
    fun `two unrelated glitches do not confirm each other`() {
        val f = seeded()
        assertFalse(f.accept(baseLat + latOffsetForMetres(20_000.0), baseLng, 20f, ns(30)))
        // Second glitch in the opposite direction: mutually implausible too.
        assertFalse(f.accept(baseLat - latOffsetForMetres(20_000.0), baseLng, 20f, ns(60)))
    }

    @Test
    fun `stale anchor never censors reality forever`() {
        val f = seeded()
        // No fixes for over STALE_ACCEPT_MS (flight mode, tunnel, ...): the
        // next fix is taken at face value however far or coarse it is.
        val staleSec = LocationFilter.STALE_ACCEPT_MS / 1000 + 1
        assertTrue(f.accept(baseLat + latOffsetForMetres(500_000.0), baseLng, 2000f, ns(staleSec)))
    }

    @Test
    fun `high accuracy movement is not rejected at plausible speeds`() {
        val f = seeded()
        // Highway driving: ~1km in 30s ≈ 33 m/s.
        assertTrue(f.accept(baseLat + latOffsetForMetres(1000.0), baseLng, 10f, ns(30)))
    }

    @Test
    fun `accuracy uncertainty is subtracted before the speed check`() {
        val f = LocationFilter()
        assertTrue(f.accept(baseLat, baseLng, 90f, ns(0)))
        // 3.15km in 30s reads over the cap raw (105 m/s) but under it once the
        // fixes' combined 190m of accuracy slop is subtracted (~99 m/s).
        val distM = 3150.0
        assertTrue(distM / 30.0 > LocationFilter.MAX_PLAUSIBLE_SPEED_MPS)
        assertTrue((distM - 90.0 - 100.0) / 30.0 < LocationFilter.MAX_PLAUSIBLE_SPEED_MPS)
        assertTrue(f.accept(baseLat + latOffsetForMetres(distM), baseLng, 100f, ns(30)))
    }

    @Test
    fun `out of order fix is dropped`() {
        val f = seeded()
        assertTrue(f.accept(baseLat + latOffsetForMetres(10.0), baseLng, 15f, ns(30)))
        assertFalse(f.accept(baseLat + latOffsetForMetres(20.0), baseLng, 15f, ns(20)))
    }
}
