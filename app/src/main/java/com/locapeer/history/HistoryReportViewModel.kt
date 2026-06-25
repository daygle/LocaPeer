package com.locapeer.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locapeer.data.dao.HeartbeatDao
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.entity.HeartbeatEntity
import com.locapeer.data.entity.PeerEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

@HiltViewModel
class HistoryReportViewModel @Inject constructor(
    private val heartbeatDao: HeartbeatDao,
    private val peerDao: PeerDao
) : ViewModel() {

    val broadcasters: StateFlow<List<PeerEntity>> = peerDao.getBroadcasters()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _selectedPeerId = MutableStateFlow<String?>(null)
    val selectedPeerId: StateFlow<String?> = _selectedPeerId

    private val _selectedDayStart = MutableStateFlow(todayStartMs())
    val selectedDayStart: StateFlow<Long> = _selectedDayStart

    val heartbeats: StateFlow<List<HeartbeatEntity>> =
        combine(_selectedPeerId, _selectedDayStart) { peerId, dayStart -> peerId to dayStart }
            .flatMapLatest { (peerId, dayStart) ->
                if (peerId == null) flowOf(emptyList())
                else heartbeatDao.getHeartbeatsForDay(peerId, dayStart, dayStart + DAY_MS)
            }
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        viewModelScope.launch {
            broadcasters.collect { list ->
                if (_selectedPeerId.value == null && list.isNotEmpty()) {
                    _selectedPeerId.value = list.first().deviceId
                }
            }
        }
    }

    fun selectPeer(peerId: String) { _selectedPeerId.value = peerId }

    fun selectDay(dayStartMs: Long) { _selectedDayStart.value = dayStartMs }

    fun prevDay() { _selectedDayStart.update { it - DAY_MS } }

    fun nextDay() {
        val today = todayStartMs()
        _selectedDayStart.update { ms -> minOf(ms + DAY_MS, today) }
    }

    fun isToday(): Boolean = _selectedDayStart.value == todayStartMs()

    companion object {
        private const val DAY_MS = 24 * 60 * 60 * 1000L

        fun todayStartMs(): Long {
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
    }
}
