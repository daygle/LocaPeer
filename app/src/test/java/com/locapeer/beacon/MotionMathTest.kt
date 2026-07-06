package com.locapeer.beacon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionMathTest {

    // --- classify ---

    @Test
    fun `classifies each speed band`() {
        assertEquals(MotionState.STATIONARY, MotionMath.classify(0f))
        assertEquals(MotionState.STATIONARY, MotionMath.classify(0.49f))
        assertEquals(MotionState.WALKING, MotionMath.classify(0.5f))
        assertEquals(MotionState.WALKING, MotionMath.classify(1.4f))
        assertEquals(MotionState.RUNNING, MotionMath.classify(2.2f))
        assertEquals(MotionState.RUNNING, MotionMath.classify(3.0f))
        assertEquals(MotionState.CYCLING, MotionMath.classify(3.6f))
        assertEquals(MotionState.CYCLING, MotionMath.classify(6.0f))
        assertEquals(MotionState.DRIVING, MotionMath.classify(8.0f))
        assertEquals(MotionState.DRIVING, MotionMath.classify(30f))
    }

    // --- derivedSpeedMps ---

    @Test
    fun `wifi drift while parked reads as zero speed`() {
        // 80m jump between two 50m-accuracy fixes 120s apart: inside uncertainty.
        assertEquals(0f, MotionMath.derivedSpeedMps(80f, 120f, 100f))
    }

    @Test
    fun `driving between moderate-accuracy fixes registers as fast motion`() {
        // 1.7km in 120s with 300m combined accuracy → well above walking speed.
        val speed = MotionMath.derivedSpeedMps(1700f, 120f, 300f)
        assertTrue(speed > MotionMath.RUNNING_MAX_MPS)
    }

    @Test
    fun `derived speed is underestimated, never negative`() {
        assertEquals(0f, MotionMath.derivedSpeedMps(100f, 60f, 500f))
        val speed = MotionMath.derivedSpeedMps(1000f, 100f, 400f)
        assertEquals(6f, speed, 0.001f)
        assertTrue(speed < 1000f / 100f)
    }

    @Test
    fun `capping the subtraction lets real motion register on coarse moving fixes`() {
        // 450m in 30s (15 m/s) between two 200m fixes. Uncapped, the 400m subtraction
        // drags it to walking speed and would demote a drive.
        assertTrue(MotionMath.derivedSpeedMps(450f, 30f, 400f) < MotionMath.WALKING_MAX_MPS)
        // Capped at 150m, the same fixes read as vehicle speed.
        val capped = MotionMath.derivedSpeedMps(450f, 30f, 400f, MotionMath.MOVING_ACCURACY_SUBTRACT_CAP_M)
        assertEquals(10f, capped, 0.001f)
        assertTrue(capped >= MotionMath.CYCLING_MAX_MPS)
    }

    @Test
    fun `cap never subtracts more than the real accuracy sum`() {
        // Accuracy (100m) below the cap: full subtraction still applies, so parked
        // drift within the uncertainty still reads as zero even with a cap in force.
        assertEquals(0f, MotionMath.derivedSpeedMps(80f, 120f, 100f, MotionMath.MOVING_ACCURACY_SUBTRACT_CAP_M))
    }

    // --- samplesRequiredToSwitch ---

    @Test
    fun `accelerating into a faster tier trips quickly`() {
        assertEquals(2, MotionMath.samplesRequiredToSwitch(MotionState.STATIONARY, MotionState.WALKING))
        assertEquals(2, MotionMath.samplesRequiredToSwitch(MotionState.WALKING, MotionState.DRIVING))
        assertEquals(2, MotionMath.samplesRequiredToSwitch(MotionState.CYCLING, MotionState.DRIVING))
        // An unknown starting point should also detect motion promptly.
        assertEquals(2, MotionMath.samplesRequiredToSwitch(MotionState.UNKNOWN, MotionState.DRIVING))
    }

    @Test
    fun `settling into stationary needs the most evidence`() {
        assertEquals(4, MotionMath.samplesRequiredToSwitch(MotionState.WALKING, MotionState.STATIONARY))
        assertEquals(4, MotionMath.samplesRequiredToSwitch(MotionState.DRIVING, MotionState.STATIONARY))
    }

    @Test
    fun `leaving vehicle or cycling speed is sticky through brief dips`() {
        // A train dwelling at a platform / poor-GPS patch must not demote a drive
        // to WALKING on a couple of slow samples.
        assertEquals(4, MotionMath.samplesRequiredToSwitch(MotionState.DRIVING, MotionState.WALKING))
        assertEquals(4, MotionMath.samplesRequiredToSwitch(MotionState.DRIVING, MotionState.CYCLING))
        assertEquals(4, MotionMath.samplesRequiredToSwitch(MotionState.CYCLING, MotionState.WALKING))
    }

    @Test
    fun `slow-downs below cycling speed are only mildly sticky`() {
        assertEquals(3, MotionMath.samplesRequiredToSwitch(MotionState.RUNNING, MotionState.WALKING))
    }

    // --- shouldExitStationary ---

    @Test
    fun `drift inside the uncertainty never exits stationary`() {
        // Parked with a 50m anchor; coarse 1km fix lands 900m away: not provable movement.
        assertFalse(MotionMath.shouldExitStationary(900f, 50f, 1000f))
        // Small wifi wobble around a tight anchor.
        assertFalse(MotionMath.shouldExitStationary(60f, 20f, 30f))
    }

    @Test
    fun `a suburb-scale drive exits stationary even on coarse cell fixes`() {
        // 3km from a 50m anchor seen by a 1km-accuracy cell fix.
        assertTrue(MotionMath.shouldExitStationary(3000f, 50f, 1000f))
        // GPS-grade anchor and fix: exits shortly after driving off.
        assertTrue(MotionMath.shouldExitStationary(200f, 15f, 15f))
    }

    @Test
    fun `exit threshold includes the buffer exactly`() {
        val anchorAcc = 20f
        val fixAcc = 30f
        val threshold = anchorAcc + fixAcc + MotionMath.STATIONARY_EXIT_BUFFER_M
        assertFalse(MotionMath.shouldExitStationary(threshold, anchorAcc, fixAcc))
        assertTrue(MotionMath.shouldExitStationary(threshold + 1f, anchorAcc, fixAcc))
    }

    // --- shouldTightenAnchor ---

    @Test
    fun `better fix confirming the same place tightens the anchor`() {
        // 30m-accuracy fix 100m from a 1km anchor: contained, tighten.
        assertTrue(MotionMath.shouldTightenAnchor(100f, 1000f, 30f))
    }

    @Test
    fun `anchor never tightens toward a fix outside its circle`() {
        // Better accuracy but the fix circle pokes outside the anchor circle:
        // could be genuine movement, must not chase it.
        assertFalse(MotionMath.shouldTightenAnchor(990f, 1000f, 30f))
        // Worse accuracy never tightens.
        assertFalse(MotionMath.shouldTightenAnchor(0f, 50f, 200f))
    }
}
