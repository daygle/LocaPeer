package com.locapeer.beacon

import com.locapeer.settings.AppSettings
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AdaptiveIntervalManager @Inject constructor(
    private val liveViewRegistry: LiveViewRegistry
) {

    @Volatile private var isSosMode = false
    @Volatile private var currentMotionState = MotionState.UNKNOWN
    @Volatile private var batteryLevel = 100

    fun setSosMode(enabled: Boolean) { isSosMode = enabled }
    fun updateMotionState(state: MotionState) { currentMotionState = state }
    fun updateBattery(level: Int) { batteryLevel = level }
    fun isLowBattery(): Boolean = batteryLevel < 20

    /** True when a contact is actively viewing this device's location AND the battery can
     *  afford the fast cadence. Low battery deliberately suppresses live mode: unlike SOS,
     *  a viewer's convenience must not defeat battery protection. */
    fun isLiveViewActive(allowBoost: Boolean): Boolean =
        allowBoost && batteryLevel >= 20 && liveViewRegistry.isActive()

    fun getIntervalMillis(settings: AppSettings): Long {
        if (isSosMode) return 15_000L
        val currentBattery = batteryLevel
        if (currentBattery < 20) return (settings.lowBatteryIntervalMinutes * 60_000L).coerceAtLeast(60_000L)
        // A contact watching the map pulls updates to a near-real-time cadence, but only
        // while their lease is live; it lapses seconds after they close the map.
        if (isLiveViewActive(settings.allowLiveBoost)) return LIVE_VIEW_INTERVAL_MS
        return motionIntervalMillis(currentMotionState, settings)
            .coerceAtLeast(15_000L) // Minimum 15 seconds to avoid battery drain and relay spam
    }

    private fun motionIntervalMillis(state: MotionState, settings: AppSettings): Long = when (state) {
        MotionState.STATIONARY -> settings.stationaryIntervalMinutes * 60_000L
        MotionState.WALKING -> settings.walkingIntervalMinutes * 60_000L
        MotionState.RUNNING -> settings.runningIntervalMinutes * 60_000L
        MotionState.CYCLING -> settings.cyclingIntervalMinutes * 60_000L
        MotionState.DRIVING -> settings.drivingIntervalMinutes * 60_000L
        MotionState.UNKNOWN -> settings.walkingIntervalMinutes * 60_000L
    }
}
