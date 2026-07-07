package com.locapeer.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Assigns a shared [GeofenceEntity] area to a tracked contact, with the trigger to watch
 * for. A single area can be assigned to many contacts; a contact can be assigned to many
 * areas. The (geofenceId, trackedDeviceId) pair is unique so the same area isn't attached
 * to the same person twice.
 */
@Entity(
    tableName = "geofence_assignments",
    indices = [
        Index(value = ["geofenceId", "trackedDeviceId"], unique = true),
        Index(value = ["trackedDeviceId"])
    ]
)
data class GeofenceAssignmentEntity(
    @PrimaryKey val id: String,
    val geofenceId: String,
    val trackedDeviceId: String,
    /** ENTER, EXIT, or BOTH */
    val triggerOn: String,
    val active: Boolean = true,
    val scheduleRules: String = "[]",
    val createdAt: Long = System.currentTimeMillis()
)
