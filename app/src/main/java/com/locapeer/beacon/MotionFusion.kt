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
}
