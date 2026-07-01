package com.locapeer.history

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.HeartbeatDao
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.entity.HeartbeatEntity
import com.locapeer.data.entity.PeerEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject
import kotlin.coroutines.resume

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryReportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val heartbeatDao: HeartbeatDao,
    private val peerDao: PeerDao,
    private val keyManager: KeyManager
) : ViewModel() {

    private val geocoder = Geocoder(context, Locale.getDefault())

    val receiveContacts: StateFlow<List<PeerEntity>> = peerDao.getReceiveContacts()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _selfPubkeyHex = MutableStateFlow<String?>(null)
    val selfPubkeyHex: StateFlow<String?> = _selfPubkeyHex

    private val _selectedPeerId = MutableStateFlow<String?>(null)
    val selectedPeerId: StateFlow<String?> = _selectedPeerId

    private val _selectedDayStart = MutableStateFlow(todayStartMs())
    val selectedDayStart: StateFlow<Long> = _selectedDayStart

    private val _startTimeOffset = MutableStateFlow(0L) // ms from day start
    val startTimeOffset: StateFlow<Long> = _startTimeOffset

    private val _endTimeOffset = MutableStateFlow(DAY_MS - 1) // ms from day start
    val endTimeOffset: StateFlow<Long> = _endTimeOffset

    val heartbeats: StateFlow<List<HeartbeatEntity>> =
        combine(_selectedPeerId, _selectedDayStart, _startTimeOffset, _endTimeOffset) { peerId, dayStart, startOff, endOff ->
            if (peerId == null) flowOf(emptyList())
            else heartbeatDao.getHeartbeatsForDay(peerId, dayStart + startOff, dayStart + endOff)
        }
            .flatMapLatest { it }
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _addresses = MutableStateFlow<Map<Long, String>>(emptyMap())
    val addresses: StateFlow<Map<Long, String>> = _addresses

    init {
        viewModelScope.launch {
            val (_, pubHex) = keyManager.ensureKeypair()
            _selfPubkeyHex.value = pubHex
        }
        viewModelScope.launch {
            receiveContacts.collect { list ->
                if (_selectedPeerId.value == null && list.isNotEmpty()) {
                    _selectedPeerId.value = list.first().deviceId
                }
            }
        }
        viewModelScope.launch {
            heartbeats.collectLatest { pings ->
                val cached = _addresses.value
                val pending = pings.filter { it.id !in cached }
                pending.forEachIndexed { index, ping ->
                    if (index > 0) delay(250)
                    val addr = geocodeLocation(ping.lat, ping.lng) ?: return@forEachIndexed
                    _addresses.update { it + (ping.id to addr) }
                }
            }
        }
    }

    fun selectPeer(peerId: String) {
        _selectedPeerId.value = peerId
        _addresses.value = emptyMap()
    }

    fun selectDay(dayStartMs: Long) {
        _selectedDayStart.value = dayStartMs
        _addresses.value = emptyMap()
        resetTimeRange()
    }

    fun setTimeRange(startMs: Long, endMs: Long) {
        _startTimeOffset.value = startMs
        _endTimeOffset.value = endMs
        _addresses.value = emptyMap()
    }

    fun resetTimeRange() {
        _startTimeOffset.value = 0L
        _endTimeOffset.value = DAY_MS - 1
    }

    fun prevDay() {
        _selectedDayStart.update { it - DAY_MS }
        _addresses.value = emptyMap()
        resetTimeRange()
    }

    fun nextDay() {
        val today = todayStartMs()
        _selectedDayStart.update { ms -> minOf(ms + DAY_MS, today) }
        _addresses.value = emptyMap()
        resetTimeRange()
    }

    fun isToday(): Boolean = _selectedDayStart.value == todayStartMs()

    @Suppress("DEPRECATION")
    private suspend fun geocodeLocation(lat: Double, lng: Double): String? =
        withContext(Dispatchers.IO) {
            if (!Geocoder.isPresent()) return@withContext null
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    suspendCancellableCoroutine { cont ->
                        geocoder.getFromLocation(lat, lng, 1) { addresses ->
                            cont.resume(addresses.firstOrNull()?.let { formatAddress(it) })
                        }
                    }
                } else {
                    geocoder.getFromLocation(lat, lng, 1)?.firstOrNull()?.let { formatAddress(it) }
                }
            } catch (e: Exception) { null }
        }

    private fun formatAddress(addr: Address): String =
        listOfNotNull(addr.thoroughfare, addr.locality, addr.adminArea)
            .joinToString(", ")
            .ifBlank { addr.getAddressLine(0) ?: "" }

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
