package com.locapeer.beacon

import android.util.Log
import com.locapeer.settings.AppSettings
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AdaptiveIntervalManager"

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
        if (currentBattery < 20) return (settings.lowBatteryIntervalMinutes * 60_000L).coerceAtLeast(60_000L)
        val interval = when (currentMotionState) {
            MotionState.STATIONARY -> settings.stationaryIntervalMinutes * 60_000L
            MotionState.WALKING -> settings.walkingIntervalMinutes * 60_000L
            MotionState.RUNNING -> settings.runningIntervalMinutes * 60_000L
            MotionState.CYCLING -> settings.cyclingIntervalMinutes * 60_000L
            MotionState.DRIVING -> settings.drivingIntervalMinutes * 60_000L
            MotionState.UNKNOWN -> settings.walkingIntervalMinutes * 60_000L
        }
        return interval.coerceAtLeast(15_000L) // Minimum 15 seconds to avoid battery drain and relay spam
    }

    /** Returns the expected interval in milliseconds for a given motion state (used for overdue checks). */
    fun getExpectedIntervalMillis(motionState: String, settings: AppSettings): Long {
        val state = MotionState.entries.firstOrNull { it.name == motionState }
            ?: MotionState.UNKNOWN.also { Log.w(TAG, "Unknown motion state '$motionState', defaulting to UNKNOWN") }
        return when (state) {
            MotionState.STATIONARY -> settings.stationaryIntervalMinutes * 60_000L
            MotionState.WALKING -> settings.walkingIntervalMinutes * 60_000L
            MotionState.RUNNING -> settings.runningIntervalMinutes * 60_000L
            MotionState.CYCLING -> settings.cyclingIntervalMinutes * 60_000L
            MotionState.DRIVING -> settings.drivingIntervalMinutes * 60_000L
            MotionState.UNKNOWN -> settings.walkingIntervalMinutes * 60_000L
        }
    }
}
