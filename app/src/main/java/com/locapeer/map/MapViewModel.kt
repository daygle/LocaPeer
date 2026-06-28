package com.locapeer.map

import android.annotation.SuppressLint
import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import com.locapeer.data.dao.GeofenceDao
import com.locapeer.data.dao.HeartbeatDao
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.entity.GeofenceEntity
import com.locapeer.data.entity.HeartbeatEntity
import com.locapeer.data.entity.PeerEntity
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.sos.SosManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

private val Context.mapPrefs by preferencesDataStore(name = "map_prefs")

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
    private val sosManager: SosManager,
    private val relayClient: NostrRelayClient,
    @ApplicationContext private val appContext: Context,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val relayStatus = relayClient.relayStatus

    private val _userLocation = MutableStateFlow<GeoPoint?>(null)
    val userLocation: StateFlow<GeoPoint?> = _userLocation.asStateFlow()

    private val _lastMapCenter = MutableStateFlow<Triple<Double, Double, Double>?>(null)
    val lastMapCenter: StateFlow<Triple<Double, Double, Double>?> = _lastMapCenter.asStateFlow()

    private val _centerOnArgs = MutableStateFlow<GeoPoint?>(null)
    val centerOnArgs: StateFlow<GeoPoint?> = _centerOnArgs.asStateFlow()

    val isSosActive: StateFlow<Boolean> = sosManager.isSosActive

    init {
        val argLat = savedStateHandle.get<String>("lat")?.toDoubleOrNull()
        val argLng = savedStateHandle.get<String>("lng")?.toDoubleOrNull()

        if (argLat != null && argLng != null) {
            _centerOnArgs.value = GeoPoint(argLat, argLng)
        }

        viewModelScope.launch {
            val prefs = appContext.mapPrefs.data.first()
            val lat = prefs[KEY_LAT]
            val lng = prefs[KEY_LNG]
            val zoom = prefs[KEY_ZOOM]
            if (lat != null && lng != null && zoom != null) {
                // If we have center args, we'll use those instead of saved center for the initial view
                if (_centerOnArgs.value == null) {
                    _lastMapCenter.value = Triple(lat, lng, zoom)
                } else {
                    _lastMapCenter.value = Triple(argLat!!, argLng!!, 16.0)
                }
            } else if (argLat != null && argLng != null) {
                _lastMapCenter.value = Triple(argLat, argLng, 16.0)
            }
        }
    }

    fun consumeCenterArgs() {
        _centerOnArgs.value = null
    }

    fun saveMapPosition(lat: Double, lng: Double, zoom: Double) {
        viewModelScope.launch {
            appContext.mapPrefs.edit { prefs ->
                prefs[KEY_LAT] = lat
                prefs[KEY_LNG] = lng
                prefs[KEY_ZOOM] = zoom
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun fetchUserLocation() {
        LocationServices.getFusedLocationProviderClient(appContext)
            .lastLocation
            .addOnSuccessListener { loc ->
                loc?.let { _userLocation.value = GeoPoint(it.latitude, it.longitude) }
            }
    }

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

    companion object {
        private val KEY_LAT = doublePreferencesKey("map_last_lat")
        private val KEY_LNG = doublePreferencesKey("map_last_lng")
        private val KEY_ZOOM = doublePreferencesKey("map_last_zoom")
    }

    fun formatTimestamp(millis: Long): String =
        DateTimeFormatter.ofPattern("HH:mm, dd MMM")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(millis))
}
