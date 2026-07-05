package com.locapeer.geofence

import android.annotation.SuppressLint
import android.view.ScaleGestureDetector
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fence
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PersonPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.android.gms.location.LocationServices
import com.locapeer.supervised.SupervisionGate
import com.locapeer.supervised.SupervisionGateViewModel
import com.locapeer.data.entity.GeofenceEntity
import com.locapeer.ui.components.EmptyState
import com.locapeer.ui.theme.GeofenceBoth
import com.locapeer.ui.theme.GeofenceEnter
import com.locapeer.ui.theme.GeofenceExit
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeofenceListScreen(
    peerId: String? = null,
    onNavigateBack: () -> Unit,
    vm: GeofenceViewModel = hiltViewModel()
) {
    val gateVm: SupervisionGateViewModel = hiltViewModel()
    val supervisedModeEnabled by gateVm.supervisedModeEnabled.collectAsState()
    val gateUnlockState by gateVm.unlockState.collectAsState()
    var sessionUnlocked by remember { mutableStateOf(false) }
    if (supervisedModeEnabled && !sessionUnlocked) {
        SupervisionGate(
            unlockState = gateUnlockState,
            onRequestAccess = gateVm::requestAccess,
            onReset = gateVm::reset,
            onNavigateBack = onNavigateBack
        ) { sessionUnlocked = true }
        return
    }

    val geofences by vm.geofences.collectAsState()
    val broadcastersWithLocation by vm.receiveContactsWithLocation.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingFence by remember { mutableStateOf<GeofenceEntity?>(null) }

    val filteredGeofences = remember(geofences, peerId) {
        if (peerId == null) geofences else geofences.filter { it.trackedDeviceId == peerId }
    }

    val title = if (peerId != null) {
        val name = broadcastersWithLocation.find { it.peer.deviceId == peerId }?.peer?.displayName
        if (name != null) "Geofences for $name" else "Geofences"
    } else {
        "Geofences"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Geofence")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            items(filteredGeofences, key = { it.id }) { fence ->
                GeofenceCard(
                    fence = fence,
                    trackedName = broadcastersWithLocation
                        .firstOrNull { it.peer.deviceId == fence.trackedDeviceId }
                        ?.peer?.displayName ?: "Unknown",
                    onToggle = { vm.setActive(fence.id, it) },
                    onEdit = { editingFence = fence },
                    onDelete = { vm.delete(fence) }
                )
            }
            if (filteredGeofences.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Default.Fence,
                        title = if (peerId != null) "No geofences for contact" else "No geofences",
                        subtitle = "Tap the + button to create a new arrival or departure alert.",
                        modifier = Modifier.fillParentMaxSize()
                    )
                }
            }
        }
    }

    if (showCreateDialog || editingFence != null) {
        val availableBroadcasters = if (peerId != null) {
            broadcastersWithLocation.filter { it.peer.deviceId == peerId }
        } else {
            broadcastersWithLocation
        }
        val dismiss = { showCreateDialog = false; editingFence = null }

        if (availableBroadcasters.isEmpty()) {
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text("No Contacts Found") },
                text = { Text(if (peerId != null) "This contact hasn't shared their location yet." else "You need at least one tracked contact before creating a geofence.") },
                confirmButton = {
                    TextButton(onClick = dismiss) { Text("OK") }
                }
            )
        } else {
            GeofenceDialog(
                existing = editingFence,
                broadcastersWithLocation = availableBroadcasters,
                onDismiss = dismiss,
                onSave = { name, lat, lng, radius, deviceId, trigger ->
                    val current = editingFence
                    if (current != null) {
                        vm.updateGeofence(
                            current.copy(
                                name = name,
                                lat = lat,
                                lng = lng,
                                radiusMetres = radius,
                                trackedDeviceId = deviceId,
                                triggerOn = trigger
                            )
                        )
                    } else {
                        vm.createGeofence(name, lat, lng, radius, deviceId, trigger)
                    }
                    dismiss()
                }
            )
        }
    }
}

@Composable
private fun GeofenceCard(
    fence: GeofenceEntity,
    trackedName: String,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val triggerColor = when (fence.triggerOn) {
        "ENTER" -> GeofenceEnter
        "EXIT" -> GeofenceExit
        else -> GeofenceBoth
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(fence.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Receiving location from: $trackedName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "${com.locapeer.util.DisplayFormat.distanceValue(fence.radiusMetres.toDouble())} • ${fence.triggerOn.lowercase().replaceFirstChar { it.uppercase() }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = triggerColor
                )
            }
            Switch(
                checked = fence.active,
                onCheckedChange = onToggle
            )
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
}

/**
 * Formats a coordinate with a fixed locale. These strings are parsed back with
 * toDoubleOrNull(), which only accepts '.' — a locale-dependent ',' separator would
 * make the field unparseable and block saving.
 */
private fun formatCoord(value: Double): String = "%.6f".format(java.util.Locale.US, value)

@SuppressLint("MissingPermission")
@Composable
private fun GeofenceDialog(
    existing: GeofenceEntity?,
    broadcastersWithLocation: List<BroadcasterWithLocation>,
    onDismiss: () -> Unit,
    onSave: (String, Double, Double, Int, String, String) -> Unit
) {
    val context = LocalContext.current
    val fusedLocation = remember { LocationServices.getFusedLocationProviderClient(context) }

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var latText by remember { mutableStateOf(existing?.lat?.let { formatCoord(it) } ?: "") }
    var lngText by remember { mutableStateOf(existing?.lng?.let { formatCoord(it) } ?: "") }
    var radiusText by remember { mutableStateOf((existing?.radiusMetres ?: 500).toString()) }
    var selectedDeviceId by remember {
        mutableStateOf(existing?.trackedDeviceId ?: broadcastersWithLocation.first().peer.deviceId)
    }
    val selectedEntry = broadcastersWithLocation.firstOrNull { it.peer.deviceId == selectedDeviceId }
        ?: broadcastersWithLocation.first()
    SideEffect {
        if (selectedEntry.peer.deviceId != selectedDeviceId) selectedDeviceId = selectedEntry.peer.deviceId
    }
    var triggerOn by remember { mutableStateOf(existing?.triggerOn ?: "ENTER") }
    var submitted by remember { mutableStateOf(false) }
    var loadingMyLocation by remember { mutableStateOf(false) }
    var recenterTo by remember { mutableStateOf<GeoPoint?>(null) }

    val lat = latText.toDoubleOrNull()
    val lng = lngText.toDoubleOrNull()
    val radiusValue = radiusText.toIntOrNull()
    val radiusForMap = (radiusValue ?: 500).coerceIn(MIN_RADIUS_M, MAX_RADIUS_M)
    val nameError = submitted && name.isBlank()
    val latError = submitted && (lat == null || lat !in -90.0..90.0)
    val lngError = submitted && (lng == null || lng !in -180.0..180.0)
    val radiusError = submitted && (radiusValue == null || radiusValue !in MIN_RADIUS_M..MAX_RADIUS_M)
    val isValid = name.isNotBlank() &&
        lat != null && lat in -90.0..90.0 &&
        lng != null && lng in -180.0..180.0 &&
        radiusValue != null && radiusValue in MIN_RADIUS_M..MAX_RADIUS_M

    // Where the map camera starts: the existing fence, else the tracked contact's last position.
    val initialCamera = remember {
        when {
            existing != null -> GeoPoint(existing.lat, existing.lng)
            selectedEntry.lastHeartbeat != null ->
                GeoPoint(selectedEntry.lastHeartbeat.lat, selectedEntry.lastHeartbeat.lng)
            else -> null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header with Cancel / title / Save
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                    Text(
                        if (existing != null) "Edit Geofence" else "New Geofence",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            submitted = true
                            if (isValid) {
                                onSave(name, lat!!, lng!!, radiusValue!!, selectedEntry.peer.deviceId, triggerOn)
                            }
                        }
                    ) { Text(if (existing != null) "Save" else "Create") }
                }

                // Interactive map picker: tap to place, pinch to resize, drag to move
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                ) {
                    GeofencePickerMap(
                        lat = lat,
                        lng = lng,
                        radiusMetres = radiusForMap,
                        triggerOn = triggerOn,
                        initialCamera = initialCamera,
                        recenterTo = recenterTo,
                        onRecentered = { recenterTo = null },
                        onPointSelected = { la, ln ->
                            latText = formatCoord(la)
                            lngText = formatCoord(ln)
                        },
                        onRadiusChange = { radiusText = it.toString() },
                        modifier = Modifier.fillMaxSize()
                    )

                    val hint = if (lat == null || lng == null) {
                        "Tap the map to place the geofence"
                    } else {
                        "Pinch to resize • drag or tap to move"
                    }
                    val hintAlign = if (lat == null || lng == null) Alignment.TopCenter else Alignment.BottomCenter
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .align(hintAlign)
                            .padding(8.dp)
                    ) {
                        Text(
                            hint,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                // Scrollable controls
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        isError = nameError,
                        supportingText = if (nameError) { { Text("Name is required") } } else null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Quick location helpers (also recentre the map)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                loadingMyLocation = true
                                fusedLocation.lastLocation.addOnSuccessListener { loc ->
                                    loadingMyLocation = false
                                    if (loc != null) {
                                        latText = formatCoord(loc.latitude)
                                        lngText = formatCoord(loc.longitude)
                                        recenterTo = GeoPoint(loc.latitude, loc.longitude)
                                    }
                                }.addOnFailureListener { loadingMyLocation = false }
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            if (loadingMyLocation) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.MyLocation, null, Modifier.size(14.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                            Text("My Location", style = MaterialTheme.typography.labelSmall)
                        }

                        selectedEntry.lastHeartbeat?.let { hb ->
                            OutlinedButton(
                                onClick = {
                                    latText = formatCoord(hb.lat)
                                    lngText = formatCoord(hb.lng)
                                    recenterTo = GeoPoint(hb.lat, hb.lng)
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.PersonPin, null, Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "${selectedEntry.peer.displayName}'s Location",
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    // Manual coordinate entry (kept in sync with the map)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = latText,
                            onValueChange = { latText = it },
                            label = { Text("Latitude") },
                            isError = latError,
                            supportingText = if (latError) { { Text("−90 to 90") } } else null,
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = lngText,
                            onValueChange = { lngText = it },
                            label = { Text("Longitude") },
                            isError = lngError,
                            supportingText = if (lngError) { { Text("−180 to 180") } } else null,
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Radius: manual entry + slider (pinch on the map also adjusts this)
                    OutlinedTextField(
                        value = radiusText,
                        onValueChange = { new -> radiusText = new.filter { it.isDigit() } },
                        label = { Text("Radius (metres)") },
                        isError = radiusError,
                        supportingText = {
                            if (radiusError) Text("$MIN_RADIUS_M to $MAX_RADIUS_M m")
                            else Text(com.locapeer.util.DisplayFormat.distanceValue(radiusForMap.toDouble()))
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Slider(
                        value = radiusForMap.toFloat(),
                        onValueChange = { radiusText = it.roundToInt().toString() },
                        valueRange = MIN_RADIUS_M.toFloat()..MAX_RADIUS_M.toFloat(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text("Tracked Person", style = MaterialTheme.typography.labelSmall)
                    broadcastersWithLocation.forEach { entry ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = selectedDeviceId == entry.peer.deviceId,
                                onClick = { selectedDeviceId = entry.peer.deviceId }
                            )
                            Column {
                                Text(entry.peer.displayName)
                                if (entry.lastHeartbeat != null) {
                                    Text(
                                        "Last seen: %.4f, %.4f".format(
                                            entry.lastHeartbeat.lat, entry.lastHeartbeat.lng
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    Text(
                                        "No location yet",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    Text("Trigger On", style = MaterialTheme.typography.labelSmall)
                    listOf("ENTER", "EXIT", "BOTH").forEach { t ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = triggerOn == t, onClick = { triggerOn = t })
                            Text(t.lowercase().replaceFirstChar { it.uppercase() })
                        }
                    }
                }
            }
        }
    }
}

private const val MIN_RADIUS_M = 50
private const val MAX_RADIUS_M = 50000

/**
 * An osmdroid map for picking a geofence centre and radius:
 * - tap (or drag the pin) sets the centre,
 * - a two-finger pinch resizes the circle (built-in pinch-zoom is disabled and replaced
 *   by the +/- buttons and double-tap so the gesture is free for resizing).
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
private fun GeofencePickerMap(
    lat: Double?,
    lng: Double?,
    radiusMetres: Int,
    triggerOn: String,
    initialCamera: GeoPoint?,
    recenterTo: GeoPoint?,
    onRecentered: () -> Unit,
    onPointSelected: (Double, Double) -> Unit,
    onRadiusChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var mapView by remember { mutableStateOf<MapView?>(null) }

    // Gesture callbacks are captured once in the factory; keep them reading the latest values.
    val pointCb by rememberUpdatedState(onPointSelected)
    val radiusCb by rememberUpdatedState(onRadiusChange)
    val currentRadius by rememberUpdatedState(radiusMetres)

    DisposableEffect(Unit) {
        onDispose {
            mapView?.onPause()
            mapView?.onDetach()
            mapView = null
        }
    }

    LaunchedEffect(recenterTo, mapView) {
        val mv = mapView ?: return@LaunchedEffect
        val target = recenterTo ?: return@LaunchedEffect
        mv.controller.setZoom(15.0)
        mv.controller.animateTo(target)
        onRecentered()
    }

    AndroidView(
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                // Pinch is repurposed for resizing, so disable pinch-zoom and expose zoom buttons.
                setMultiTouchControls(false)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
                isVerticalMapRepetitionEnabled = false

                val receiver = object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                        pointCb(p.latitude, p.longitude)
                        return true
                    }
                    override fun longPressHelper(p: GeoPoint): Boolean = false
                }
                overlays.add(MapEventsOverlay(receiver))

                val scaleDetector = ScaleGestureDetector(
                    ctx,
                    object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        override fun onScale(detector: ScaleGestureDetector): Boolean {
                            val next = (currentRadius * detector.scaleFactor).roundToInt()
                                .coerceIn(MIN_RADIUS_M, MAX_RADIUS_M)
                            radiusCb(next)
                            return true
                        }
                    }
                )
                setOnTouchListener { _, ev ->
                    scaleDetector.onTouchEvent(ev)
                    // Consume only while pinching so panning/tapping still reach the map.
                    scaleDetector.isInProgress
                }

                if (initialCamera != null) {
                    controller.setZoom(15.0)
                    controller.setCenter(initialCamera)
                } else {
                    controller.setZoom(4.0)
                }
                onResume()
            }.also { mapView = it }
        },
        update = { mv ->
            mv.overlays.removeAll { it is Marker || it is Polygon }
            if (lat != null && lng != null) {
                val center = GeoPoint(lat, lng)
                val strokeColor = when (triggerOn) {
                    "ENTER" -> GeofenceEnter
                    "EXIT" -> GeofenceExit
                    else -> GeofenceBoth
                }
                val fillArgb = android.graphics.Color.argb(40,
                    (strokeColor.red * 255).toInt(),
                    (strokeColor.green * 255).toInt(),
                    (strokeColor.blue * 255).toInt())
                val strokeArgb = android.graphics.Color.argb(220,
                    (strokeColor.red * 255).toInt(),
                    (strokeColor.green * 255).toInt(),
                    (strokeColor.blue * 255).toInt())
                val circle = Polygon().apply {
                    points = Polygon.pointsAsCircle(center, radiusMetres.toDouble())
                    fillPaint.color = fillArgb
                    outlinePaint.color = strokeArgb
                    outlinePaint.strokeWidth = 5f
                }
                mv.overlays.add(circle)

                val marker = Marker(mv).apply {
                    position = center
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    infoWindow = null
                    isDraggable = true
                    setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                        override fun onMarkerDrag(m: Marker) {}
                        override fun onMarkerDragEnd(m: Marker) {
                            pointCb(m.position.latitude, m.position.longitude)
                        }
                        override fun onMarkerDragStart(m: Marker) {}
                    })
                }
                mv.overlays.add(marker)
            }
            mv.invalidate()
        },
        modifier = modifier
    )
}
