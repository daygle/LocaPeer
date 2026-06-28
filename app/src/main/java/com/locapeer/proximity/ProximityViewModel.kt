package com.locapeer.proximity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.dao.ProximityAlertDao
import com.locapeer.data.entity.PeerEntity
import com.locapeer.data.entity.ProximityAlertEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PeerProximityState(
    val peer: PeerEntity,
    val alert: ProximityAlertEntity?
)

@HiltViewModel
class ProximityViewModel @Inject constructor(
    private val peerDao: PeerDao,
    private val proximityAlertDao: ProximityAlertDao
) : ViewModel() {

    val peerStates = combine(
        peerDao.getReceiveContacts(),
        proximityAlertDao.getAll()
    ) { peers, alerts ->
        val alertMap = alerts.associateBy { it.peerDeviceId }
        peers.map { peer -> PeerProximityState(peer, alertMap[peer.deviceId]) }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun setActive(peerDeviceId: String, active: Boolean, currentRadius: Int) {
        viewModelScope.launch {
            if (active) {
                proximityAlertDao.upsert(
                    ProximityAlertEntity(peerDeviceId = peerDeviceId, radiusMetres = currentRadius, active = true)
                )
            } else {
                proximityAlertDao.setActive(peerDeviceId, false)
            }
        }
    }

    fun setRadius(peerDeviceId: String, radiusMetres: Int) {
        viewModelScope.launch {
            val existing = proximityAlertDao.getForPeer(peerDeviceId)
            if (existing != null) {
                proximityAlertDao.setRadius(peerDeviceId, radiusMetres)
            } else {
                proximityAlertDao.upsert(
                    ProximityAlertEntity(peerDeviceId = peerDeviceId, radiusMetres = radiusMetres, active = false)
                )
            }
        }
    }
}
