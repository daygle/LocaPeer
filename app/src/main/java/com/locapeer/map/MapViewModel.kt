package com.locapeer.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locapeer.data.dao.GeofenceDao
import com.locapeer.data.dao.HeartbeatDao
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.entity.GeofenceEntity
import com.locapeer.data.entity.HeartbeatEntity
import com.locapeer.data.entity.PeerEntity
import com.locapeer.sos.SosManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class PinData(
    val peer: PeerEntity,
    val heartbeat: HeartbeatEntity?,
    val isOverdue: Boolean
)

data class MapUiState(
    val pins: List<PinData> = emptyList(),
    val geofences: List<GeofenceEntity> = emptyList()
)

@HiltViewModel
class MapViewModel @Inject constructor(
    private val peerDao: PeerDao,
    private val heartbeatDao: HeartbeatDao,
    private val geofenceDao: GeofenceDao,
    private val sosManager: SosManager
) : ViewModel() {

    val isSosActive: StateFlow<Boolean> = sosManager.isSosActive

    fun toggleSos() {
        if (sosManager.isSosActive.value) sosManager.deactivateSos() else sosManager.activateSos()
    }

    val uiState: StateFlow<MapUiState> = combine(
        peerDao.getBroadcasters(),
        heartbeatDao.getLatestHeartbeatPerDevice(),
        geofenceDao.getAllGeofences()
    ) { peers, heartbeats, fences ->
        val heartbeatMap = heartbeats.associateBy { it.deviceId }
        val now = System.currentTimeMillis()
        val pins = peers.map { peer ->
            val hb = heartbeatMap[peer.deviceId]
            val overdue = hb != null && (now - hb.timestamp) > 30 * 60 * 1000L
            PinData(peer, hb, overdue)
        }
        MapUiState(pins = pins, geofences = fences)
    }.stateIn(viewModelScope, SharingStarted.Lazily, MapUiState())

    fun getHistoryForToday(deviceId: String) =
        heartbeatDao.getHeartbeatsSince(
            deviceId,
            Instant.now().atZone(ZoneId.systemDefault()).toLocalDate()
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )

    fun formatTimestamp(millis: Long): String =
        DateTimeFormatter.ofPattern("HH:mm, dd MMM")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(millis))
}
