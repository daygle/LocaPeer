package com.locapeer.map

import android.content.Context
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
    /** Names of the contacts this shared geofence is assigned to (or "Unassigned"). */
    val assignedLabel: String
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
    private val geofenceAssignmentDao: com.locapeer.data.dao.GeofenceAssignmentDao,
    private val sharingConfigDao: PeerSharingConfigDao,
    private val sosManager: SosManager,
    private val liveViewSender: com.locapeer.beacon.LiveViewSender,
    private val keyManager: KeyManager,
    private val relayClient: NostrRelayClient,
    private val appPreferences: AppPreferences,
    @ApplicationContext private val appContext: Context,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    val relayStatus = relayClient.relayStatus

    val myDisplayName: StateFlow<String> = appPreferences.settings
        .map { it.displayName.ifBlank { appContext.getString(com.locapeer.R.string.fallback_me) } }
        .stateIn(viewModelScope, SharingStarted.Lazily, appContext.getString(com.locapeer.R.string.fallback_me))

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

    /**
     * Look up street addresses for tapped pins via the device geocoder. Off by default:
     * like the History report, this sends the queried coordinates to the OS geocoding
     * backend, so it stays behind the same explicit opt-in.
     */
    val reverseGeocodingEnabled: StateFlow<Boolean> = appPreferences.settings
        .map { it.reverseGeocodingEnabled }
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    fun toggleGeofences() {
        viewModelScope.launch {
            appPreferences.setShowGeofencesOnMap(!showGeofences.value)
        }
    }

    // Guards the live-view announce loop so the screen's lifecycle callbacks can be
    // idempotent: ON_RESUME / re-entry may both try to start, and ON_PAUSE / onDispose may
    // both try to stop. Only genuine transitions reach the reference-counted sender.
    private var liveViewOn = false

    /** Begin telling tracked contacts we are viewing them, if not already doing so. */
    fun startLiveView() {
        if (liveViewOn) return
        liveViewOn = true
        liveViewSender.startViewing()
    }

    /** Stop announcing that we are viewing tracked contacts, if currently doing so. */
    fun stopLiveView() {
        if (!liveViewOn) return
        liveViewOn = false
        liveViewSender.stopViewing()
    }

    override fun onCleared() {
        // The screen normally stops us via onDispose, but a process-level teardown may
        // clear the ViewModel without it; make sure the announce loop can't outlive us.
        stopLiveView()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val userLocation: StateFlow<GeoPoint?> = keyManager.publicKeyHexFlow
        .flatMapLatest { myPubkey ->
            if (myPubkey == null) flowOf(null)
            else heartbeatDao.observeLatestHeartbeat(myPubkey).map { hb ->
                hb?.let { GeoPoint(it.lat, it.lng) }
            }
        }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    private val _lastMapCenter = MutableStateFlow<Triple<Double, Double, Double>?>(null)
    val lastMapCenter: StateFlow<Triple<Double, Double, Double>?> = _lastMapCenter.asStateFlow()

    // Seed synchronously from the nav args so an explicit "show on map" target is already
    // visible on the screen's first composition, before the async collectors below run.
    // Otherwise the map's own start-up centering can win the race and jump to the user's
    // location instead of the requested contact location.
    private val _centerOnArgs = MutableStateFlow<GeoPoint?>(
        run {
            val lat = savedStateHandle.get<String>("lat")?.toDoubleOrNull()
            val lng = savedStateHandle.get<String>("lng")?.toDoubleOrNull()
            if (lat != null && lng != null) GeoPoint(lat, lng) else null
        }
    )
    val centerOnArgs: StateFlow<GeoPoint?> = _centerOnArgs.asStateFlow()

    val isSosActive: StateFlow<Boolean> = sosManager.isSosActive

    val hasSosContacts: StateFlow<Boolean> = sharingConfigDao.observeAll()
        .map { configs -> configs.any { it.isSosContact } }
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    init {
        // Observe navigation arguments. We use flows because if the user is already on the map
        // and taps a "show on map" button elsewhere, the same ViewModel instance may be
        // reused (due to launchSingleTop), and SavedStateHandle will be updated with new args.
        viewModelScope.launch {
            combine(
                savedStateHandle.getStateFlow<String?>("lat", null),
                savedStateHandle.getStateFlow<String?>("lng", null),
                savedStateHandle.getStateFlow<String?>("peerId", null)
            ) { lat, lng, peerId -> Triple(lat, lng, peerId) }
                .collect { (latStr, lngStr, peerId) ->
                    val lat = latStr?.toDoubleOrNull()
                    val lng = lngStr?.toDoubleOrNull()
                    if (lat != null && lng != null) {
                        _centerOnArgs.value = GeoPoint(lat, lng)
                    } else if (peerId != null) {
                        heartbeatDao.getLatestHeartbeat(peerId)?.let { hb ->
                            _centerOnArgs.value = GeoPoint(hb.lat, hb.lng)
                        }
                    }
                }
        }

        viewModelScope.launch {
            val prefs = appContext.mapPrefs.data.first()
            val lat = prefs[KEY_LAT]
            val lng = prefs[KEY_LNG]
            val zoom = prefs[KEY_ZOOM]

            val argLat = savedStateHandle.get<String>("lat")?.toDoubleOrNull()
            val argLng = savedStateHandle.get<String>("lng")?.toDoubleOrNull()

            if (argLat != null && argLng != null) {
                _lastMapCenter.value = Triple(argLat, argLng, 16.0)
            } else if (lat != null && lng != null && zoom != null) {
                // If we have center args, we'll use those instead of saved center for the initial view
                if (_centerOnArgs.value == null) {
                    _lastMapCenter.value = Triple(lat, lng, zoom)
                }
            }
        }
    }

    fun consumeCenterArgs() {
        _centerOnArgs.value = null
        // Clear the args from SavedStateHandle so they don't re-trigger on config change
        savedStateHandle["lat"] = null
        savedStateHandle["lng"] = null
        savedStateHandle["peerId"] = null
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

    fun toggleSos() {
        if (sosManager.isSosActive.value) sosManager.deactivateSos() else sosManager.activateSos()
    }

    /**
     * Emits [Unit] once per minute so [uiState] re-evaluates overdue flags even when
     * no new heartbeats arrive (the DB-backed flows only emit on row changes).
     */
    private val tickerFlow: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(60_000L)
        }
    }

    // Holds the latest raw data from the five DB/key flows so the ticker can combine
    // with it without nesting six incompatible types in a single combine call.
    private data class UiSnapshot(
        val peers: List<PeerEntity>,
        val heartbeats: List<HeartbeatEntity>,
        val fences: List<GeofenceEntity>,
        val assignments: List<com.locapeer.data.entity.GeofenceAssignmentEntity>,
        val myPubkey: String?
    )

    val uiState: StateFlow<MapUiState> = combine(
        combine(
            peerDao.getReceiveContacts(),
            heartbeatDao.getLatestHeartbeatPerDevice(),
            geofenceDao.getAllGeofences(),
            geofenceAssignmentDao.observeAll(),
            // Cached public-key flow instead of ensureKeypair() so a new heartbeat doesn't
            // trigger an Android Keystore decrypt on every emission just to filter own pin.
            keyManager.publicKeyHexFlow
        ) { peers, heartbeats, fences, assignments, myPubkey ->
            UiSnapshot(peers, heartbeats, fences, assignments, myPubkey)
        },
        tickerFlow
    ) { snapshot, _ ->
        val heartbeatMap = snapshot.heartbeats.associateBy { it.deviceId }
        val now = System.currentTimeMillis()
        val pins = snapshot.peers.filter { it.deviceId != snapshot.myPubkey }.map { peer ->
            val hb = heartbeatMap[peer.deviceId]
            val intervalSec = hb?.expectedIntervalSeconds ?: 900L
            // Use receivedAt (stamped by the receiver's own clock at insertion) rather than
            // timestamp (the sender's device clock) to avoid false overdue flags caused by
            // inter-device clock skew.
            val overdue = hb != null && (now - hb.receivedAt) > (intervalSec * 1000L * 2).coerceAtLeast(120_000L)
            PinData(peer, hb, overdue)
        }
        val nameByDevice = snapshot.peers.associate { it.deviceId to it.displayName }
        val assignmentsByFence = snapshot.assignments.groupBy { it.geofenceId }
        val unknownLabel = appContext.getString(com.locapeer.R.string.geo_unknown)
        val unassignedLabel = appContext.getString(com.locapeer.R.string.geo_unassigned)
        val fencesOnMap = snapshot.fences.map { fence ->
            val names = assignmentsByFence[fence.id].orEmpty()
                .map { nameByDevice[it.trackedDeviceId] ?: unknownLabel }
                .distinct()
            GeofenceOnMap(fence, if (names.isEmpty()) unassignedLabel else names.joinToString(", "))
        }
        MapUiState(pins = pins, geofences = fencesOnMap)
    }.stateIn(viewModelScope, SharingStarted.Lazily, MapUiState())

    companion object {
        private val KEY_LAT = doublePreferencesKey("map_last_lat")
        private val KEY_LNG = doublePreferencesKey("map_last_lng")
        private val KEY_ZOOM = doublePreferencesKey("map_last_zoom")
    }

    fun formatTimestamp(millis: Long): String = com.locapeer.util.DisplayFormat.relativeTimestamp(millis)
}
