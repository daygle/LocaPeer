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

    /**
     * Cap on how much combined fix accuracy is subtracted from displacement when
     * deriving speed *while already moving* (see [derivedSpeedMps]). Subtracting the
     * full accuracy is right for rejecting drift while parked, but on a poor-accuracy
     * pair mid-trip (a tunnel, a metal carriage) it can zero out real motion and
     * demote a drive. Once the device is established as moving, drift is no longer the
     * risk, so the subtraction is capped and true speed registers sooner.
     */
    const val MOVING_ACCURACY_SUBTRACT_CAP_M = 150f

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
     *
     * [maxAccuracySubtractM] caps how much accuracy is removed. The default of no cap
     * gives the fully-conservative reading used while parked/UNKNOWN, where drift
     * rejection matters most; callers pass [MOVING_ACCURACY_SUBTRACT_CAP_M] once the
     * device is established as moving so a coarse fix can't zero out real speed.
     */
    fun derivedSpeedMps(
        distanceM: Float,
        dtSec: Float,
        accuracySumM: Float,
        maxAccuracySubtractM: Float = Float.MAX_VALUE,
    ): Float =
        (distanceM - accuracySumM.coerceAtMost(maxAccuracySubtractM)).coerceAtLeast(0f) / dtSec

    /**
     * Consecutive agreeing samples required to switch state. Asymmetric on purpose:
     * quick to detect faster motion, slow to give it up. Accelerating into a faster
     * tier trips in 2 samples; decelerating is stickier so a brief slow stretch — a
     * train dwelling at a platform, a car at a red light, a patch of poor-accuracy
     * GPS in a tunnel or metal carriage — doesn't drop an established drive to
     * WALKING (and its slower pulse cadence) or STATIONARY (and its low-power
     * polling) before the device has provably slowed for good.
     */
    fun samplesRequiredToSwitch(from: MotionState, to: MotionState): Int {
        val fromRank = speedRank(from)
        val toRank = speedRank(to)
        return when {
            // Accelerating or holding tier: detect motion promptly.
            toRank >= fromRank -> 2
            // Settling all the way to stationary: needs the most evidence.
            to == MotionState.STATIONARY -> 4
            // Dropping out of vehicle/cycling speed: hold through brief dips.
            fromRank >= speedRank(MotionState.CYCLING) -> 4
            // Other slow-downs: mild stickiness.
            else -> 3
        }
    }

    /**
     * States ordered by representative speed, for the deceleration hysteresis in
     * [samplesRequiredToSwitch]. Not the enum's declaration order — DRIVING is
     * declared before CYCLING there. UNKNOWN sits at the walking tier, matching how
     * it is treated for interval selection.
     */
    private fun speedRank(state: MotionState): Int = when (state) {
        MotionState.STATIONARY -> 0
        MotionState.WALKING -> 1
        MotionState.UNKNOWN -> 1
        MotionState.RUNNING -> 2
        MotionState.CYCLING -> 3
        MotionState.DRIVING -> 4
    }

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
