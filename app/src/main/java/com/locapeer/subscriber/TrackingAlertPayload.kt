package com.locapeer.subscriber

import kotlinx.serialization.Serializable

@Serializable
data class TrackingAlertPayload(
    val type: String, // "PROXIMITY" or "GEOFENCE"
    val alertName: String? = null,
    val triggerName: String, // Name of the person who received the alert (User A)
    val timestamp: Long = System.currentTimeMillis()
)
