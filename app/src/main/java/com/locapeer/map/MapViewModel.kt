package com.locapeer.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.GeofenceDao
import com.locapeer.data.dao.HeartbeatDao
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.dao.PeerSharingConfigDao
import com.locapeer.data.entity.GeofenceEntity
import com.locapeer.data.entity.HeartbeatEntity
import com.locapeer.data.entity.PeerEntity
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.settings.AppPreferences
import com.locapeer.sos.SosManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import javax.inject.Inject

private val Context.mapPrefs by preferencesDataStore(name = "map_prefs")

data class PinData(
    val peer: PeerEntity,
    val heartbeat: HeartbeatEntity?,
    val isOverdue: Boolean
)

data class GeofenceOnMap(
    val fence: GeofenceEntity,
    /** Display name of the contact this geofence tracks. */
    val trackedName: String
)

data class MapUiState(
    val pins: List<PinData> = emptyList(),
    val geofences: List<GeofenceOnMap> = emptyList()
)

@HiltViewModel
class MapViewModel @Inject constructor(
    private val peerDao: PeerDao,
    private val heartbeatDao: HeartbeatDao,
    private val geofenceDao: GeofenceDao,
    private val sharingConfigDao: PeerSharingConfigDao,
    private val sosManager: SosManager,
    private val keyManager: KeyManager,
    private val relayClient: NostrRelayClient,
    private val appPreferences: AppPreferences,
    @ApplicationContext private val appContext: Context,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val relayStatus = relayClient.relayStatus

    val myDisplayName: StateFlow<String> = appPreferences.settings
        .map { it.displayName.ifBlank { "Me" } }
        .stateIn(viewModelScope, SharingStarted.Lazily, "Me")

    val myPinColor: StateFlow<String> = appPreferences.settings
        .map { it.pinColor }
        .stateIn(viewModelScope, SharingStarted.Lazily, "")

    val mapStartZoom: StateFlow<Double> = appPreferences.settings
        .map { it.mapStartZoom }
        .stateIn(viewModelScope, SharingStarted.Lazily, 16.0)

    val mapStartingPoint: StateFlow<String> = appPreferences.settings
        .map { it.mapStartingPoint }
        .stateIn(viewModelScope, SharingStarted.Lazily, "OWN_PIN")

    val mapFixedLat: StateFlow<Double> = appPreferences.settings
        .map { it.mapFixedLat }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0.0)

    val mapFixedLng: StateFlow<Double> = appPreferences.settings
        .map { it.mapFixedLng }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0.0)

    val showGeofences: StateFlow<Boolean> = appPreferences.settings
        .map { it.showGeofencesOnMap }
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    fun toggleGeofences() {
        viewModelScope.launch {
            appPreferences.setShowGeofencesOnMap(!showGeofences.value)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val userLocation: StateFlow<GeoPoint?> = flow {
        val (_, pubHex) = keyManager.ensureKeypair()
        emit(pubHex)
    }.flatMapLatest { myPubkey ->
        heartbeatDao.getLatestHeartbeatPerDevice().map { heartbeats ->
            heartbeats.find { it.deviceId == myPubkey }?.let { GeoPoint(it.lat, it.lng) }
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    private val _lastMapCenter = MutableStateFlow<Triple<Double, Double, Double>?>(null)
    val lastMapCenter: StateFlow<Triple<Double, Double, Double>?> = _lastMapCenter.asStateFlow()

    private val _centerOnArgs = MutableStateFlow<GeoPoint?>(null)
    val centerOnArgs: StateFlow<GeoPoint?> = _centerOnArgs.asStateFlow()

    val isSosActive: StateFlow<Boolean> = sosManager.isSosActive

    val hasSosContacts: StateFlow<Boolean> = sharingConfigDao.observeAll()
        .map { configs -> configs.any { it.isSosContact } }
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

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

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun fetchUserLocation() {
        // HeartbeatService is already updating DB; this just triggers a refresh if needed
        // but since userLocation now observes the DB, it will update automatically.
    }

    fun toggleSos() {
        if (sosManager.isSosActive.value) sosManager.deactivateSos() else sosManager.activateSos()
    }

    val uiState: StateFlow<MapUiState> = combine(
        peerDao.getReceiveContacts(),
        heartbeatDao.getLatestHeartbeatPerDevice(),
        geofenceDao.getAllGeofences()
    ) { peers, heartbeats, fences ->
        val heartbeatMap = heartbeats.associateBy { it.deviceId }
        val now = System.currentTimeMillis()
        val myPubkey = try { keyManager.ensureKeypair().second } catch (_: Exception) { "" }
        val pins = peers.filter { it.deviceId != myPubkey }.map { peer ->
            val hb = heartbeatMap[peer.deviceId]
            val overdue = hb != null && (now - hb.timestamp) > 30 * 60 * 1000L
            PinData(peer, hb, overdue)
        }
        val nameByDevice = peers.associate { it.deviceId to it.displayName }
        val fencesOnMap = fences.map {
            GeofenceOnMap(it, nameByDevice[it.trackedDeviceId] ?: "Unknown")
        }
        MapUiState(pins = pins, geofences = fencesOnMap)
    }.stateIn(viewModelScope, SharingStarted.Lazily, MapUiState())

    companion object {
        private val KEY_LAT = doublePreferencesKey("map_last_lat")
        private val KEY_LNG = doublePreferencesKey("map_last_lng")
        private val KEY_ZOOM = doublePreferencesKey("map_last_zoom")
    }

    fun formatTimestamp(millis: Long): String {
        val diffMs = System.currentTimeMillis() - millis
        return when {
            diffMs < 60_000 -> "Just now"
            diffMs < 3_600_000 -> "${diffMs / 60_000}m ago"
            diffMs < 86_400_000 -> java.text.SimpleDateFormat(com.locapeer.util.DisplayFormat.timePattern(), java.util.Locale.getDefault()).format(java.util.Date(millis))
            else -> {
                val cal = java.util.Calendar.getInstance().also { it.timeInMillis = millis }
                val today = java.util.Calendar.getInstance()
                val fmt = if (cal.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR))
                    java.text.SimpleDateFormat("d MMM, ${com.locapeer.util.DisplayFormat.timePattern()}", java.util.Locale.getDefault())
                else
                    java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
                fmt.format(java.util.Date(millis))
            }
        }
    }
}
