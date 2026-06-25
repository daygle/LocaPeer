package com.locapeer.beacon

enum class MotionState(val displayName: String) {
    STATIONARY("Stationary"),
    WALKING("Walking"),
    RUNNING("Running"),
    DRIVING("Driving"),
    CYCLING("Cycling"),
    UNKNOWN("Unknown");

    companion object {
        fun fromDetectedActivity(type: Int): MotionState {
            return when (type) {
                com.google.android.gms.location.DetectedActivity.STILL -> STATIONARY
                com.google.android.gms.location.DetectedActivity.WALKING -> WALKING
                com.google.android.gms.location.DetectedActivity.RUNNING -> RUNNING
                com.google.android.gms.location.DetectedActivity.IN_VEHICLE -> DRIVING
                com.google.android.gms.location.DetectedActivity.ON_BICYCLE -> CYCLING
                else -> UNKNOWN
            }
        }
    }
}
