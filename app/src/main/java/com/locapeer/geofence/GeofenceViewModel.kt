package com.locapeer.geofence

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locapeer.data.dao.GeofenceDao
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.entity.GeofenceEntity
import com.locapeer.data.entity.PeerEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class GeofenceViewModel @Inject constructor(
    private val geofenceDao: GeofenceDao,
    private val peerDao: PeerDao
) : ViewModel() {

    val geofences = geofenceDao.getAllGeofences()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val broadcasters = peerDao.getBroadcasters()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun createGeofence(
        name: String,
        lat: Double,
        lng: Double,
        radiusMetres: Int,
        trackedDeviceId: String,
        triggerOn: String
    ) {
        viewModelScope.launch {
            geofenceDao.upsert(
                GeofenceEntity(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    lat = lat,
                    lng = lng,
                    radiusMetres = radiusMetres,
                    trackedDeviceId = trackedDeviceId,
                    triggerOn = triggerOn
                )
            )
        }
    }

    fun setActive(id: String, active: Boolean) {
        viewModelScope.launch { geofenceDao.setActive(id, active) }
    }

    fun delete(geofence: GeofenceEntity) {
        viewModelScope.launch { geofenceDao.delete(geofence) }
    }

    fun updateGeofence(geofence: GeofenceEntity) {
        viewModelScope.launch { geofenceDao.upsert(geofence) }
    }
}
