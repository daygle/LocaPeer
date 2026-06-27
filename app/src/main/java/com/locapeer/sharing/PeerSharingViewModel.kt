package com.locapeer.sharing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.dao.PeerSharingConfigDao
import com.locapeer.data.entity.PeerEntity
import com.locapeer.data.entity.PeerSharingConfig
import com.locapeer.data.entity.PrecisionMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PeerSharingUiState(
    val peer: PeerEntity? = null,
    val config: PeerSharingConfig? = null
)

@HiltViewModel
class PeerSharingViewModel @Inject constructor(
    private val peerDao: PeerDao,
    private val configDao: PeerSharingConfigDao
) : ViewModel() {

    private var currentPeerId: String = ""

    lateinit var uiState: StateFlow<PeerSharingUiState>
        private set

    fun init(peerId: String) {
        if (currentPeerId == peerId) return
        currentPeerId = peerId
        uiState = combine(
            flowOf(peerId).let { _ ->
                kotlinx.coroutines.flow.flow { emit(peerDao.getPeer(peerId)) }
            },
            configDao.observeForPeer(peerId)
        ) { peer, config ->
            PeerSharingUiState(peer = peer, config = config)
        }.stateIn(viewModelScope, SharingStarted.Lazily, PeerSharingUiState())
    }

    private fun defaultConfig() = PeerSharingConfig(peerDeviceId = currentPeerId)

    private fun currentConfig(state: PeerSharingUiState) = state.config ?: defaultConfig()

    fun setSharingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val existing = configDao.getForPeer(currentPeerId)
            if (existing != null) {
                configDao.setSharingEnabled(currentPeerId, enabled)
            } else {
                configDao.upsert(defaultConfig().copy(sharingEnabled = enabled))
            }
        }
    }

    fun setMessagingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val existing = configDao.getForPeer(currentPeerId)
            if (existing != null) {
                configDao.setMessagingEnabled(currentPeerId, enabled)
            } else {
                configDao.upsert(defaultConfig().copy(messagingEnabled = enabled))
            }
        }
    }

    fun setPrecisionMode(mode: PrecisionMode) {
        viewModelScope.launch {
            val existing = configDao.getForPeer(currentPeerId)
            if (existing != null) {
                configDao.setPrecisionMode(currentPeerId, mode.name)
            } else {
                configDao.upsert(defaultConfig().copy(precisionMode = mode.name))
            }
        }
    }

    fun setScheduleEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val cfg = configDao.getForPeer(currentPeerId) ?: defaultConfig()
            configDao.upsert(cfg.copy(scheduleEnabled = enabled))
        }
    }

    fun setScheduleDays(days: Int) {
        viewModelScope.launch {
            val cfg = configDao.getForPeer(currentPeerId) ?: defaultConfig()
            configDao.upsert(cfg.copy(scheduleDays = days))
        }
    }

    fun setScheduleStart(minuteOfDay: Int) {
        viewModelScope.launch {
            val cfg = configDao.getForPeer(currentPeerId) ?: defaultConfig()
            configDao.upsert(cfg.copy(scheduleStartMinute = minuteOfDay))
        }
    }

    fun setScheduleEnd(minuteOfDay: Int) {
        viewModelScope.launch {
            val cfg = configDao.getForPeer(currentPeerId) ?: defaultConfig()
            configDao.upsert(cfg.copy(scheduleEndMinute = minuteOfDay))
        }
    }

    fun setSosContact(enabled: Boolean) {
        viewModelScope.launch {
            val cfg = configDao.getForPeer(currentPeerId) ?: defaultConfig()
            configDao.upsert(cfg.copy(isSosContact = enabled))
        }
    }
}
