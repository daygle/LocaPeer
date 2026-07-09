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
import com.locapeer.settings.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
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
    private val keyManager: KeyManager,
    private val prefs: AppPreferences
) : ViewModel() {

    private val geocoder = Geocoder(context, Locale.getDefault())

    val receiveContacts: StateFlow<List<PeerEntity>> = peerDao.getReceiveContacts()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _selfPubkeyHex = MutableStateFlow<String?>(null)
    val selfPubkeyHex: StateFlow<String?> = _selfPubkeyHex

    val selfDisplayName: StateFlow<String> = prefs.settings
        .map { it.displayName.ifBlank { "Me" } }
        .stateIn(viewModelScope, SharingStarted.Lazily, "Me")

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
            if (peerId == null) {
                flowOf(emptyList())
            } else {
                // DST-aware day bounds: a local day can be 23h or 25h, so take the exclusive
                // end from the next local midnight instead of a fixed 24h. Clamp the selected
                // sub-range into [dayStart, nextMidnight); an un-narrowed end extends to the
                // true day end so a 25h fall-back day's final hour isn't dropped and a 23h
                // spring-forward day's window doesn't spill into the next day.
                val dayEnd = shiftDayStart(dayStart, 1)
                val from = (dayStart + startOff).coerceIn(dayStart, dayEnd)
                val to = if (endOff >= DAY_MS - 1) dayEnd
                         else (dayStart + endOff + 1).coerceIn(from, dayEnd)
                heartbeatDao.getHeartbeatsForDay(peerId, from, to)
            }
        }
            .flatMapLatest { it }
            .combine(
                prefs.settings
                    .map { it.historyMinDistanceMeters to it.historyMaxAccuracyMeters }
                    .distinctUntilChanged()
            ) { pings, (minDistanceM, maxAccuracyM) ->
                // Drop low-accuracy fixes first, then thin the survivors by spacing.
                val accurate = HistoryThinning.filterByAccuracy(pings, maxAccuracyM)
                HistoryThinning.thin(accurate, minDistanceM)
            }
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _addresses = MutableStateFlow<Map<Long, String>>(emptyMap())
    val addresses: StateFlow<Map<Long, String>> = _addresses

    init {
        viewModelScope.launch {
            val (_, pubHex) = keyManager.ensureKeypair()
            _selfPubkeyHex.value = pubHex
            // Default to "Me" if no peer selected yet
            if (_selectedPeerId.value == null) {
                _selectedPeerId.value = pubHex
            }
        }
        viewModelScope.launch {
            // Reverse geocoding is opt-in: the platform Geocoder sends the queried
            // coordinates off-device, so only look up addresses when the user has enabled it.
            // Re-evaluates live when the toggle flips (collectLatest cancels the in-flight run).
            heartbeats
                .combine(
                    prefs.settings.map { it.reverseGeocodingEnabled }.distinctUntilChanged()
                ) { pings, enabled -> pings to enabled }
                .collectLatest { (pings, enabled) ->
                    if (!enabled) {
                        _addresses.value = emptyMap()
                        return@collectLatest
                    }
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
        _selectedDayStart.update { shiftDayStart(it, -1) }
        _addresses.value = emptyMap()
        resetTimeRange()
    }

    fun nextDay() {
        val today = todayStartMs()
        _selectedDayStart.update { ms -> minOf(shiftDayStart(ms, 1), today) }
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
                        // Handle both callbacks: on failure the platform invokes onError instead
                        // of onGeocode. Without an onError branch the continuation never resumes,
                        // hanging this lookup and stalling the sequential loop that awaits it.
                        geocoder.getFromLocation(lat, lng, 1, object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) {
                                if (cont.isActive) cont.resume(addresses.firstOrNull()?.let { formatAddress(it) })
                            }

                            override fun onError(errorMessage: String?) {
                                if (cont.isActive) cont.resume(null)
                            }
                        })
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

        fun todayStartMs(): Long = startOfDay(Calendar.getInstance())

        /**
         * Local midnight [deltaDays] away from the day containing [dayStartMs]. Uses calendar
         * arithmetic rather than adding a fixed 24h so the result stays anchored to local
         * midnight across daylight-saving transitions (a local day can be 23h or 25h).
         */
        private fun shiftDayStart(dayStartMs: Long, deltaDays: Int): Long {
            val cal = Calendar.getInstance().apply {
                timeInMillis = dayStartMs
                add(Calendar.DAY_OF_YEAR, deltaDays)
            }
            return startOfDay(cal)
        }

        private fun startOfDay(cal: Calendar): Long {
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
    }
}
