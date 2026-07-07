package com.locapeer.data.dao

import androidx.room.*
import com.locapeer.data.entity.GeofenceAssignmentEntity
import kotlinx.coroutines.flow.Flow

/** A geofence area joined with the trigger for a specific assignment — what the engine evaluates. */
data class ActiveGeofence(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radiusMetres: Int,
    val triggerOn: String,
    val scheduleRules: String
)

/** An assignment joined with its area, for showing a contact's geofences in the UI. */
data class AssignmentWithArea(
    val assignmentId: String,
    val geofenceId: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radiusMetres: Int,
    val triggerOn: String,
    val active: Boolean,
    val scheduleRules: String
)

@Dao
interface GeofenceAssignmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(assignment: GeofenceAssignmentEntity)

    @Delete
    suspend fun delete(assignment: GeofenceAssignmentEntity)

    @Query("UPDATE geofence_assignments SET active = :active WHERE id = :id")
    suspend fun setActive(id: String, active: Boolean)

    @Query("DELETE FROM geofence_assignments WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM geofence_assignments WHERE geofenceId = :geofenceId")
    suspend fun deleteForGeofence(geofenceId: String)

    /** Active geofences (area + trigger) watching a given tracked contact, for the engine. */
    @Query(
        """
        SELECT g.id AS id, g.name AS name, g.lat AS lat, g.lng AS lng,
               g.radiusMetres AS radiusMetres, a.triggerOn AS triggerOn,
               a.scheduleRules AS scheduleRules
        FROM geofence_assignments a
        INNER JOIN geofences g ON g.id = a.geofenceId
        WHERE a.trackedDeviceId = :deviceId AND a.active = 1
        """
    )
    suspend fun getActiveGeofencesForDevice(deviceId: String): List<ActiveGeofence>

    /** All assignments (with their area) for a contact, for the per-contact geofence screen. */
    @Query(
        """
        SELECT a.id AS assignmentId, a.geofenceId AS geofenceId, g.name AS name,
               g.lat AS lat, g.lng AS lng, g.radiusMetres AS radiusMetres,
               a.triggerOn AS triggerOn, a.active AS active,
               a.scheduleRules AS scheduleRules
        FROM geofence_assignments a
        INNER JOIN geofences g ON g.id = a.geofenceId
        WHERE a.trackedDeviceId = :deviceId
        ORDER BY g.name ASC
        """
    )
    fun observeAssignmentsForContact(deviceId: String): Flow<List<AssignmentWithArea>>

    @Query("SELECT * FROM geofence_assignments")
    fun observeAll(): Flow<List<GeofenceAssignmentEntity>>
}
