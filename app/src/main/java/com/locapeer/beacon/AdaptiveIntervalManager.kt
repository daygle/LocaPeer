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
    fun isLowBattery(): Boolean = batteryLevel < 20

    fun getIntervalMillis(settings: AppSettings): Long {
        if (isSosMode) return 15_000L
        val currentBattery = batteryLevel
        if (currentBattery < 20) return (settings.lowBatteryIntervalMinutes * 60_000L).coerceAtLeast(60_000L)
        return motionIntervalMillis(currentMotionState, settings)
            .coerceAtLeast(15_000L) // Minimum 15 seconds to avoid battery drain and relay spam
    }

    /**
     * Returns the expected interval in milliseconds for a given motion state (used for
     * overdue checks). When the sender's last reported battery is below 20% they beat
     * at their low-battery interval instead, so expect at least that (using our own
     * low-battery setting as the best available estimate of theirs).
     */
    fun getExpectedIntervalMillis(motionState: String, settings: AppSettings, batteryLevel: Int = 100): Long {
        val state = MotionState.entries.firstOrNull { it.name == motionState }
            ?: MotionState.UNKNOWN.also { Log.w(TAG, "Unknown motion state '$motionState', defaulting to UNKNOWN") }
        val interval = motionIntervalMillis(state, settings)
        return if (batteryLevel < 20) {
            maxOf(interval, settings.lowBatteryIntervalMinutes * 60_000L)
        } else {
            interval
        }
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
