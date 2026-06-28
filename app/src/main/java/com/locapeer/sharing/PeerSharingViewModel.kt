package com.locapeer.sharing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locapeer.data.dao.MessageDao
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.dao.PeerSharingConfigDao
import com.locapeer.data.dao.ProximityAlertDao
import com.locapeer.data.entity.PeerEntity
import com.locapeer.data.entity.PeerSharingConfig
import com.locapeer.data.entity.PrecisionMode
import com.locapeer.data.entity.ProximityAlertEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

data class PeerSharingUiState(
    val peer: PeerEntity? = null,
    val config: PeerSharingConfig? = null,
    val proximityAlert: ProximityAlertEntity? = null
)

@HiltViewModel
class PeerSharingViewModel @Inject constructor(
    private val peerDao: PeerDao,
    private val configDao: PeerSharingConfigDao,
    private val messageDao: MessageDao,
    private val proximityAlertDao: ProximityAlertDao
) : ViewModel() {

    private var currentPeerId: String = ""

    private val _uiState = MutableStateFlow(PeerSharingUiState())
    val uiState: StateFlow<PeerSharingUiState> = _uiState.asStateFlow()

    fun init(peerId: String) {
        if (currentPeerId == peerId) return
        currentPeerId = peerId
        viewModelScope.launch {
            val peerFlow = flowOf(peerDao.getPeer(peerId))
            val configFlow = configDao.observeForPeer(peerId)
            val alertFlow = proximityAlertDao.observeForPeer(peerId)

            combine(peerFlow, configFlow, alertFlow) { peer, config, alert ->
                PeerSharingUiState(peer, config, alert)
            }.collect {
                _uiState.value = it
            }
        }
    }

    private fun defaultConfig() = PeerSharingConfig(peerDeviceId = currentPeerId)

    fun setSharingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val existing = configDao.getForPeer(currentPeerId)
            if (existing != null) configDao.setSharingEnabled(currentPeerId, enabled)
            else configDao.upsert(defaultConfig().copy(sharingEnabled = enabled))
        }
    }

    fun setMessagingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val existing = configDao.getForPeer(currentPeerId)
            if (existing != null) configDao.setMessagingEnabled(currentPeerId, enabled)
            else configDao.upsert(defaultConfig().copy(messagingEnabled = enabled))
            if (enabled) messageDao.unblockMessagesFromPeer(currentPeerId)
        }
    }

    fun setPrecisionMode(mode: PrecisionMode) {
        viewModelScope.launch {
            val existing = configDao.getForPeer(currentPeerId)
            if (existing != null) configDao.setPrecisionMode(currentPeerId, mode.name)
            else configDao.upsert(defaultConfig().copy(precisionMode = mode.name))
        }
    }

    fun setScheduleRules(rules: List<ScheduleRule>) {
        viewModelScope.launch {
            val rulesJson = Json.encodeToString(rules)
            val existing = configDao.getForPeer(currentPeerId)
            if (existing != null) configDao.setScheduleRules(currentPeerId, rulesJson)
            else configDao.upsert(defaultConfig().copy(scheduleRulesJson = rulesJson))
        }
    }

    fun setSosContact(enabled: Boolean) {
        viewModelScope.launch {
            val cfg = configDao.getForPeer(currentPeerId) ?: defaultConfig()
            configDao.upsert(cfg.copy(isSosContact = enabled))
        }
    }

    fun setProximityAlertEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val existing = proximityAlertDao.getForPeer(currentPeerId)
            if (existing != null) {
                proximityAlertDao.setActive(currentPeerId, enabled)
            } else if (enabled) {
                proximityAlertDao.upsert(ProximityAlertEntity(peerDeviceId = currentPeerId, active = true))
            }
        }
    }

    fun setProximityAlertRadius(radius: Int) {
        viewModelScope.launch {
            val existing = proximityAlertDao.getForPeer(currentPeerId)
            if (existing != null) {
                proximityAlertDao.setRadius(currentPeerId, radius)
            } else {
                proximityAlertDao.upsert(ProximityAlertEntity(peerDeviceId = currentPeerId, radiusMetres = radius))
            }
        }
    }
}
