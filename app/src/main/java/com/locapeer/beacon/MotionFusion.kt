package com.locapeer.beacon

/**
 * Fuses the two independent motion signals into the single [MotionState] that gets
 * stored in history and broadcast to peers:
 *
 *  - the GPS-speed classifier ([MotionMath.classify] via HeartbeatService), which
 *    reacts fast but is fooled by GPS scatter (indoor multipath can fabricate a
 *    driving-speed reading) and by its own deceleration hysteresis (an established
 *    DRIVING state is deliberately sticky), and
 *  - Android Activity Recognition, which reads low-power motion sensors rather than
 *    position, so it can't be fooled by GPS scatter and gives a genuine "STILL"
 *    ground truth that the GPS side lacks.
 *
 * Kept free of Android types (the `DetectedActivity` constants are mirrored below) so
 * it can be unit-tested on the JVM, mirroring [MotionMath]. HeartbeatService owns the
 * live AR state; this owns the mapping and the fusion rule.
 */
object MotionFusion {

    // com.google.android.gms.location.DetectedActivity constants, mirrored here so this
    // file stays Android-free and JVM-testable. Kept in sync with the SDK values.
    const val IN_VEHICLE = 0
    const val ON_BICYCLE = 1
    const val ON_FOOT = 2
    const val STILL = 3
    const val UNKNOWN = 4
    const val TILTING = 5
    const val WALKING = 7
    const val RUNNING = 8

    /**
     * Maps an Activity Recognition activity type to a [MotionState], or null for types
     * that carry no usable motion signal (UNKNOWN, TILTING, anything unrecognised) so
     * the caller leaves the previous AR reading untouched rather than clobbering it.
     */
    fun fromActivityType(activityType: Int): MotionState? = when (activityType) {
        STILL -> MotionState.STATIONARY
        WALKING, ON_FOOT -> MotionState.WALKING
        RUNNING -> MotionState.RUNNING
        ON_BICYCLE -> MotionState.CYCLING
        IN_VEHICLE -> MotionState.DRIVING
        else -> null
    }

    /**
     * The label to store/broadcast, given the GPS-derived state and the latest AR
     * reading ([arState] is null when AR hasn't reported yet, is unavailable, or the
     * permission was denied).
     *
     * Rules, in order:
     *  1. When AR has a reading it is authoritative for the *label*. This is what fixes
     *     "a walk read as driving": AR positively identifies on-foot motion from the
     *     accelerometer, so GPS scatter can no longer assert DRIVING. The known cost is
     *     AR's transition latency briefly lagging the label at the very start of a trip,
     *     and the rare slow-vehicle case AR reports as on-foot — both far milder than a
     *     wholesale wrong state. GPS remains authoritative for position, speed, bearing
     *     and the pulse cadence, none of which this touches.
     *  2. With no AR reading, fall back to the GPS state, except that a transitional /
     *     cold-start UNKNOWN resolves to STATIONARY so history never shows the raw
     *     "Unknown" token when there is genuinely nothing better to say.
     */
    fun fuse(gpsState: MotionState, arState: MotionState?): MotionState = when {
        arState != null -> arState
        gpsState == MotionState.UNKNOWN -> MotionState.STATIONARY
        else -> gpsState
    }

    /** States ordered by poll cadence (faster motion → shorter interval). UNKNOWN sits at
     *  the walking tier, matching how the interval manager already treats it. */
    private fun cadenceRank(state: MotionState): Int = when (state) {
        MotionState.STATIONARY -> 0
        MotionState.WALKING -> 1
        MotionState.UNKNOWN -> 1
        MotionState.RUNNING -> 2
        MotionState.CYCLING -> 3
        MotionState.DRIVING -> 4
    }

    /**
     * The state that should drive **pulse cadence and GPS power** — deliberately different
     * from [fuse], which drives the label. For the label AR always wins; for cadence that
     * is unsafe, because AR's STILL lags at the start of a trip and would slow GPS down
     * exactly when a drive is beginning. So:
     *
     *  1. A positive on-foot / cycling AR reading (WALKING, RUNNING, CYCLING) is
     *     authoritative: it is incompatible with being in a vehicle and never appears at
     *     drive start (the sequence there is STILL → IN_VEHICLE), so it can safely cap a
     *     stale GPS DRIVING and relax the power profile — the shop-visit battery case.
     *  2. Otherwise use whichever signal demands the faster cadence (higher rank). This
     *     keeps GPS's fast motion-onset detection and never lets a lagging AR STILL slow a
     *     freshly detected drive; STILL (rank 0) can only ever match, never lower, GPS.
     */
    fun fuseForInterval(gpsState: MotionState, arState: MotionState?): MotionState = when {
        arState == null -> gpsState
        arState == MotionState.WALKING || arState == MotionState.RUNNING || arState == MotionState.CYCLING -> arState
        cadenceRank(arState) > cadenceRank(gpsState) -> arState
        else -> gpsState
    }
}
