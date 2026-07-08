package com.locapeer.beacon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MotionFusionTest {

    // --- fromActivityType ---

    @Test
    fun `maps each recognised activity to a motion state`() {
        assertEquals(MotionState.STATIONARY, MotionFusion.fromActivityType(MotionFusion.STILL))
        assertEquals(MotionState.WALKING, MotionFusion.fromActivityType(MotionFusion.WALKING))
        assertEquals(MotionState.WALKING, MotionFusion.fromActivityType(MotionFusion.ON_FOOT))
        assertEquals(MotionState.RUNNING, MotionFusion.fromActivityType(MotionFusion.RUNNING))
        assertEquals(MotionState.CYCLING, MotionFusion.fromActivityType(MotionFusion.ON_BICYCLE))
        assertEquals(MotionState.DRIVING, MotionFusion.fromActivityType(MotionFusion.IN_VEHICLE))
    }

    @Test
    fun `activities without a usable motion signal map to null`() {
        assertNull(MotionFusion.fromActivityType(MotionFusion.UNKNOWN))
        assertNull(MotionFusion.fromActivityType(MotionFusion.TILTING))
        assertNull(MotionFusion.fromActivityType(999))
    }

    // --- fuse: AR authoritative for the label ---

    @Test
    fun `AR on-foot reading overrides a GPS driving misclassification`() {
        // The "walk read as driving" bug: GPS scatter says DRIVING, AR says WALKING.
        assertEquals(MotionState.WALKING, MotionFusion.fuse(MotionState.DRIVING, MotionState.WALKING))
    }

    @Test
    fun `AR still overrides a stale GPS driving state`() {
        assertEquals(MotionState.STATIONARY, MotionFusion.fuse(MotionState.DRIVING, MotionState.STATIONARY))
    }

    @Test
    fun `AR reading wins whenever present`() {
        assertEquals(MotionState.DRIVING, MotionFusion.fuse(MotionState.WALKING, MotionState.DRIVING))
        assertEquals(MotionState.CYCLING, MotionFusion.fuse(MotionState.STATIONARY, MotionState.CYCLING))
    }

    // --- fuse: GPS fallback when AR absent ---

    @Test
    fun `falls back to GPS state when AR has no reading`() {
        assertEquals(MotionState.WALKING, MotionFusion.fuse(MotionState.WALKING, null))
        assertEquals(MotionState.DRIVING, MotionFusion.fuse(MotionState.DRIVING, null))
        assertEquals(MotionState.STATIONARY, MotionFusion.fuse(MotionState.STATIONARY, null))
    }

    @Test
    fun `transitional or cold-start UNKNOWN resolves to STATIONARY without AR`() {
        // The "movement type shows Unknown" bug: no AR yet, GPS state still UNKNOWN.
        assertEquals(MotionState.STATIONARY, MotionFusion.fuse(MotionState.UNKNOWN, null))
    }

    @Test
    fun `AR resolves an UNKNOWN GPS state when present`() {
        assertEquals(MotionState.WALKING, MotionFusion.fuse(MotionState.UNKNOWN, MotionState.WALKING))
    }

    // --- fuseForInterval: cadence/power state ---

    @Test
    fun `on-foot AR caps a stale GPS driving cadence`() {
        // Shop-visit battery case: GPS stuck DRIVING, AR says the user is walking.
        assertEquals(MotionState.WALKING, MotionFusion.fuseForInterval(MotionState.DRIVING, MotionState.WALKING))
        assertEquals(MotionState.CYCLING, MotionFusion.fuseForInterval(MotionState.DRIVING, MotionState.CYCLING))
    }

    @Test
    fun `AR still never slows a freshly detected GPS drive`() {
        // Drive-start safety: GPS already DRIVING, AR still lagging on STILL — keep DRIVING.
        assertEquals(MotionState.DRIVING, MotionFusion.fuseForInterval(MotionState.DRIVING, MotionState.STATIONARY))
    }

    @Test
    fun `cadence takes the faster of the two signals`() {
        // AR detects a vehicle before GPS: poll at the faster cadence.
        assertEquals(MotionState.DRIVING, MotionFusion.fuseForInterval(MotionState.STATIONARY, MotionState.DRIVING))
        // Both agree stationary.
        assertEquals(MotionState.STATIONARY, MotionFusion.fuseForInterval(MotionState.STATIONARY, MotionState.STATIONARY))
    }

    @Test
    fun `cadence falls back to GPS when AR absent`() {
        assertEquals(MotionState.DRIVING, MotionFusion.fuseForInterval(MotionState.DRIVING, null))
        assertEquals(MotionState.STATIONARY, MotionFusion.fuseForInterval(MotionState.STATIONARY, null))
    }
}
