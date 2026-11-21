package com.locapeer.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A geofence *area*: a named region on the map, either a circle (centre + radius) or a
 * polygon. Areas are global and can be shared across contacts - which contact a fence
 * watches, and whether it fires on entry/exit, lives in [GeofenceAssignmentEntity].
 *
 * For a polygon, [polygon] holds its ring (`com.locapeer.util.GeofencePolygon`); [lat] /
 * [lng] are the ring's centroid and [radiusMetres] its bounding radius, so code paths
 * that still speak of a centre/radius (labels, backups, accuracy gates) keep working.
 */
@Entity(tableName = "geofences")
data class GeofenceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radiusMetres: Int,
    val createdAt: Long = System.currentTimeMillis(),
    /** Encoded polygon ring (see `com.locapeer.util.GeofencePolygon`); null for a circle. */
    val polygon: String? = null
)
