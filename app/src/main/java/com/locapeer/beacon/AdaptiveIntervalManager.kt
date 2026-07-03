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
    fun isLowBattery(): Boolean = batteryLevel < 20

    fun getIntervalMillis(settings: AppSettings): Long {
        if (isSosMode) return 15_000L
        val currentBattery = batteryLevel
        if (currentBattery < 20) return (settings.lowBatteryIntervalMinutes * 60_000L).coerceAtLeast(60_000L)
        return motionIntervalMillis(currentMotionState, settings)
            .coerceAtLeast(15_000L) // Minimum 15 seconds to avoid battery drain and relay spam
    }

    fun getExpectedIntervalMillis(motionStateName: String, settings: AppSettings, batteryLevel: Int): Long {
        val state = try {
            MotionState.valueOf(motionStateName)
        } catch (_: Exception) {
            MotionState.UNKNOWN
        }
        val motionInterval = motionIntervalMillis(state, settings).coerceAtLeast(15_000L)
        if (batteryLevel < 20) {
            val lowBatteryInterval = (settings.lowBatteryIntervalMinutes * 60_000L).coerceAtLeast(60_000L)
            return maxOf(motionInterval, lowBatteryInterval)
        }
        return motionInterval
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
