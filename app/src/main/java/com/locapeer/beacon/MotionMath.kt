package com.locapeer.beacon

/**
 * Pure motion-classification math, kept free of Android types so it can be
 * unit-tested on the JVM. HeartbeatService owns the state; this owns the rules.
 */
object MotionMath {
    const val STATIONARY_MAX_MPS = 0.5f
    const val WALKING_MAX_MPS = 2.2f
    const val RUNNING_MAX_MPS = 3.6f
    const val CYCLING_MAX_MPS = 8.0f

    /** Extra displacement beyond the fixes' combined accuracy before a stationary exit trips. */
    const val STATIONARY_EXIT_BUFFER_M = 150f

    fun classify(speedMps: Float): MotionState = when {
        speedMps < STATIONARY_MAX_MPS -> MotionState.STATIONARY
        speedMps < WALKING_MAX_MPS -> MotionState.WALKING
        speedMps < RUNNING_MAX_MPS -> MotionState.RUNNING
        speedMps < CYCLING_MAX_MPS -> MotionState.CYCLING
        else -> MotionState.DRIVING
    }

    /**
     * Displacement-derived speed with the fixes' combined accuracy subtracted:
     * drift inside the uncertainty reads as zero, real movement beyond it registers
     * (underestimated, which the classification hysteresis tolerates).
     */
    fun derivedSpeedMps(distanceM: Float, dtSec: Float, accuracySumM: Float): Float =
        (distanceM - accuracySumM).coerceAtLeast(0f) / dtSec

    /**
     * Consecutive agreeing samples required to switch state. Asymmetric on purpose:
     * quick to detect motion, slow to settle — a couple of zero-speed fixes at a
     * red light must not drop a drive into STATIONARY and its low-power polling.
     */
    fun samplesRequiredToSwitch(to: MotionState): Int =
        if (to == MotionState.STATIONARY) 4 else 2

    /**
     * True when the device has provably left the place where it became stationary:
     * displacement from the anchor exceeds both fixes' uncertainty plus a buffer.
     */
    fun shouldExitStationary(distFromAnchorM: Float, anchorAccM: Float, fixAccM: Float): Boolean =
        distFromAnchorM > anchorAccM + fixAccM + STATIONARY_EXIT_BUFFER_M

    /**
     * True when a strictly better fix confirms the same place (its accuracy circle
     * is contained in the anchor's), so the anchor can tighten without ever chasing
     * a moving device.
     */
    fun shouldTightenAnchor(distFromAnchorM: Float, anchorAccM: Float, fixAccM: Float): Boolean =
        fixAccM < anchorAccM && distFromAnchorM + fixAccM <= anchorAccM
}
