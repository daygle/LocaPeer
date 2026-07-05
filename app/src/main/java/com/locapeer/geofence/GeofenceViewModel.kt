package com.locapeer.geofence

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locapeer.data.dao.AssignmentWithArea
import com.locapeer.data.dao.GeofenceAssignmentDao
import com.locapeer.data.dao.GeofenceDao
import com.locapeer.data.dao.HeartbeatDao
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.entity.GeofenceAssignmentEntity
import com.locapeer.data.entity.GeofenceEntity
import com.locapeer.data.entity.HeartbeatEntity
import com.locapeer.data.entity.PeerEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class BroadcasterWithLocation(
    val peer: PeerEntity,
    val lastHeartbeat: HeartbeatEntity?
)

@HiltViewModel
class GeofenceViewModel @Inject constructor(
    private val geofenceDao: GeofenceDao,
    private val assignmentDao: GeofenceAssignmentDao,
    private val peerDao: PeerDao,
    private val heartbeatDao: HeartbeatDao
) : ViewModel() {

    /** Shared geofence areas (location + radius), independent of any contact. */
    val geofences = geofenceDao.getAllGeofences()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Every assignment across all contacts — used to show which contacts an area is assigned to. */
    val allAssignments = assignmentDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val receiveContacts = peerDao.getReceiveContacts()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val receiveContactsWithLocation = combine(
        peerDao.getReceiveContacts(),
        heartbeatDao.getLatestHeartbeatPerDevice()
    ) { peers, heartbeats ->
        val hbMap = heartbeats.associateBy { it.deviceId }
        peers.map { peer -> BroadcasterWithLocation(peer, hbMap[peer.deviceId]) }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Assignments (area + trigger) that watch a single tracked contact. */
    fun assignmentsForContact(deviceId: String): Flow<List<AssignmentWithArea>> =
        assignmentDao.observeAssignmentsForContact(deviceId)

    // --- Geofence areas ---

    fun createArea(name: String, lat: Double, lng: Double, radiusMetres: Int) {
        viewModelScope.launch {
            geofenceDao.upsert(
                GeofenceEntity(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    lat = lat,
                    lng = lng,
                    radiusMetres = radiusMetres
                )
            )
        }
    }

    fun updateArea(area: GeofenceEntity) {
        viewModelScope.launch { geofenceDao.upsert(area) }
    }

    fun deleteArea(area: GeofenceEntity) {
        viewModelScope.launch {
            assignmentDao.deleteForGeofence(area.id)
            geofenceDao.delete(area)
        }
    }

    // --- Contact assignments ---

    fun addAssignment(geofenceId: String, trackedDeviceId: String, triggerOn: String) {
        viewModelScope.launch {
            assignmentDao.upsert(
                GeofenceAssignmentEntity(
                    id = UUID.randomUUID().toString(),
                    geofenceId = geofenceId,
                    trackedDeviceId = trackedDeviceId,
                    triggerOn = triggerOn
                )
            )
        }
    }

    fun updateAssignment(
        existing: AssignmentWithArea,
        trackedDeviceId: String,
        geofenceId: String,
        triggerOn: String
    ) {
        viewModelScope.launch {
            assignmentDao.upsert(
                GeofenceAssignmentEntity(
                    id = existing.assignmentId,
                    geofenceId = geofenceId,
                    trackedDeviceId = trackedDeviceId,
                    triggerOn = triggerOn,
                    active = existing.active
                )
            )
        }
    }

    fun setAssignmentActive(assignmentId: String, active: Boolean) {
        viewModelScope.launch { assignmentDao.setActive(assignmentId, active) }
    }

    fun removeAssignment(assignmentId: String) {
        viewModelScope.launch { assignmentDao.deleteById(assignmentId) }
    }
}
