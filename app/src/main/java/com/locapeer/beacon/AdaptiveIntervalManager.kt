package com.locapeer.beacon

import com.locapeer.settings.AppSettings
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AdaptiveIntervalManager @Inject constructor() {

    @Volatile private var isSosMode = false
    @Volatile private var currentMotionState = MotionState.UNKNOWN
    @Volatile private var batteryLevel = 100

    fun setSosMode(enabled: Boolean) { isSosMode = enabled }
    fun updateMotionState(state: MotionState) { currentMotionState = state }
    fun updateBattery(level: Int) { batteryLevel = level }

    fun getIntervalMillis(settings: AppSettings): Long {
        if (isSosMode) return 15_000L
        val currentBattery = batteryLevel
        if (currentBattery < 20) return settings.lowBatteryIntervalMinutes * 60_000L
        return when (currentMotionState) {
            MotionState.STATIONARY -> settings.stationaryIntervalMinutes * 60_000L
            MotionState.WALKING, MotionState.RUNNING, MotionState.CYCLING ->
                settings.walkingIntervalMinutes * 60_000L
            MotionState.DRIVING -> settings.drivingIntervalMinutes * 60_000L
            MotionState.UNKNOWN -> settings.walkingIntervalMinutes * 60_000L
        }
    }

    /** Returns the expected interval in milliseconds for a given motion state (used for overdue checks). */
    fun getExpectedIntervalMillis(motionState: String, settings: AppSettings): Long {
        val state = MotionState.entries.firstOrNull { it.name == motionState } ?: MotionState.UNKNOWN
        return when (state) {
            MotionState.STATIONARY -> settings.stationaryIntervalMinutes * 60_000L
            MotionState.WALKING, MotionState.RUNNING, MotionState.CYCLING ->
                settings.walkingIntervalMinutes * 60_000L
            MotionState.DRIVING -> settings.drivingIntervalMinutes * 60_000L
            MotionState.UNKNOWN -> settings.walkingIntervalMinutes * 60_000L
        }
    }
}
