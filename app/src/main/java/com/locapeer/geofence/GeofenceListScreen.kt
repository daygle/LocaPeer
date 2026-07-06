package com.locapeer.geofence

import android.annotation.SuppressLint
import android.view.ScaleGestureDetector
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fence
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.android.gms.location.LocationServices
import com.locapeer.R
import com.locapeer.supervised.SupervisionGate
import com.locapeer.supervised.SupervisionGateViewModel
import com.locapeer.data.dao.AssignmentWithArea
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

    if (peerId == null) {
        GlobalGeofencesScreen(vm = vm, onNavigateBack = onNavigateBack)
    } else {
        ContactGeofencesScreen(peerId = peerId, vm = vm, onNavigateBack = onNavigateBack)
    }
}

/** Manage the shared geofence areas (location + radius). Contacts are assigned separately. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlobalGeofencesScreen(
    vm: GeofenceViewModel,
    onNavigateBack: () -> Unit
) {
    val areas by vm.geofences.collectAsState()
    val assignments by vm.allAssignments.collectAsState()
    val broadcasters by vm.receiveContactsWithLocation.collectAsState()

    var showCreateDialog by remember { mutableStateOf(false) }
    var editingArea by remember { mutableStateOf<GeofenceEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<GeofenceEntity?>(null) }

    val nameByDevice = broadcasters.associate { it.peer.deviceId to it.peer.displayName }
    val assignmentsByFence = assignments.groupBy { it.geofenceId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Geofences") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add geofence area")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            items(areas, key = { it.id }) { area ->
                val names = assignmentsByFence[area.id].orEmpty()
                    .map { nameByDevice[it.trackedDeviceId] ?: "Unknown" }
                    .distinct()
                GeofenceAreaCard(
                    area = area,
                    assignedNames = names,
                    onEdit = { editingArea = area },
                    onDelete = { pendingDelete = area }
                )
            }
            if (areas.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Default.Fence,
                        title = "No geofences",
                        subtitle = "Tap + to create a geofence area, then assign contacts to it from their sharing settings.",
                        modifier = Modifier.fillParentMaxSize()
                    )
                }
            }
        }
    }

    if (showCreateDialog || editingArea != null) {
        GeofenceAreaDialog(
            existing = editingArea,
            onDismiss = { showCreateDialog = false; editingArea = null },
            onSave = { name, lat, lng, radius ->
                val current = editingArea
                if (current != null) {
                    vm.updateArea(current.copy(name = name, lat = lat, lng = lng, radiusMetres = radius))
                } else {
                    vm.createArea(name, lat, lng, radius)
                }
                showCreateDialog = false
                editingArea = null
            }
        )
    }

    pendingDelete?.let { area ->
        val count = assignmentsByFence[area.id].orEmpty().size
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete geofence?") },
            text = {
                Text(
                    if (count > 0)
                        "\"${area.name}\" is assigned to $count contact${if (count == 1) "" else "s"}. Deleting it removes those assignments too."
                    else
                        "Delete \"${area.name}\"?"
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.deleteArea(area); pendingDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}

/** Assign shared geofence areas to a single contact, each with its own enter/exit trigger. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactGeofencesScreen(
    peerId: String,
    vm: GeofenceViewModel,
    onNavigateBack: () -> Unit
) {
    val areas by vm.geofences.collectAsState()
    val broadcasters by vm.receiveContactsWithLocation.collectAsState()
    val assignments by remember(peerId) { vm.assignmentsForContact(peerId) }
        .collectAsState(initial = emptyList())

    val contactName = broadcasters.find { it.peer.deviceId == peerId }?.peer?.displayName
    val title = if (contactName != null) "Geofences for $contactName" else "Geofences"

    var showAssignDialog by remember { mutableStateOf(false) }
    var editingAssignment by remember { mutableStateOf<AssignmentWithArea?>(null) }
    var showNoAreas by remember { mutableStateOf(false) }

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
            FloatingActionButton(onClick = { if (areas.isEmpty()) showNoAreas = true else showAssignDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Assign geofence")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            items(assignments, key = { it.assignmentId }) { assignment ->
                AssignmentCard(
                    assignment = assignment,
                    onToggle = { vm.setAssignmentActive(assignment.assignmentId, it) },
                    onEdit = { editingAssignment = assignment },
                    onDelete = { vm.removeAssignment(assignment.assignmentId) }
                )
            }
            if (assignments.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Default.Fence,
                        title = "No geofences for contact",
                        subtitle = "Tap + to assign a geofence area and choose whether to alert on arrival, departure, or both.",
                        modifier = Modifier.fillParentMaxSize()
                    )
                }
            }
        }
    }

    if (showAssignDialog || editingAssignment != null) {
        AssignmentDialog(
            existing = editingAssignment,
            areas = areas,
            onDismiss = { showAssignDialog = false; editingAssignment = null },
            onSave = { geofenceId, trigger ->
                val current = editingAssignment
                if (current != null) {
                    vm.updateAssignment(current, peerId, geofenceId, trigger)
                } else {
                    vm.addAssignment(geofenceId, peerId, trigger)
                }
                showAssignDialog = false
                editingAssignment = null
            }
        )
    }

    if (showNoAreas) {
        AlertDialog(
            onDismissRequest = { showNoAreas = false },
            title = { Text("No geofences yet") },
            text = { Text("Create a geofence area first from the Geofences screen, then assign it here.") },
            confirmButton = { TextButton(onClick = { showNoAreas = false }) { Text("OK") } }
        )
    }
}

@Composable
private fun GeofenceAreaCard(
    area: GeofenceEntity,
    assignedNames: List<String>,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        onClick = onEdit
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Fence,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(area.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "${com.locapeer.util.DisplayFormat.distanceValue(area.radiusMetres.toDouble())} radius",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (assignedNames.isNotEmpty()) {
                    Text(
                        "Contacts: ${assignedNames.joinToString(", ")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        "No contacts assigned",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun AssignmentCard(
    assignment: AssignmentWithArea,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val triggerColor = when (assignment.triggerOn) {
        "ENTER" -> GeofenceEnter
        "EXIT" -> GeofenceExit
        else -> GeofenceBoth
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(assignment.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = triggerColor.copy(alpha = 0.2f),
                        shape = MaterialTheme.shapes.extraSmall,
                        modifier = Modifier.padding(vertical = 2.dp)
                    ) {
                        Text(
                            assignment.triggerOn,
                            style = MaterialTheme.typography.labelSmall,
                            color = triggerColor,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        com.locapeer.util.DisplayFormat.distanceValue(assignment.radiusMetres.toDouble()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Switch(
                checked = assignment.active,
                onCheckedChange = onToggle,
                modifier = Modifier.scale(0.8f)
            )
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
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

/**
 * Full-screen editor for a shared geofence *area* (name + centre + radius).
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
private fun GeofenceAreaDialog(
    existing: GeofenceEntity?,
    onDismiss: () -> Unit,
    onSave: (String, Double, Double, Int) -> Unit
) {
    val context = LocalContext.current
    val fusedLocation = remember { LocationServices.getFusedLocationProviderClient(context) }

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var latText by remember { mutableStateOf(existing?.lat?.let { formatCoord(it) } ?: "") }
    var lngText by remember { mutableStateOf(existing?.lng?.let { formatCoord(it) } ?: "") }
    var radiusText by remember { mutableStateOf((existing?.radiusMetres ?: 500).toString()) }
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

    val initialCamera = remember { existing?.let { GeoPoint(it.lat, it.lng) } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (existing != null) "Edit Geofence" else "New Geofence") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel")
                        }
                    },
                    actions = {
                        Button(
                            onClick = {
                                submitted = true
                                if (isValid) onSave(name, lat!!, lng!!, radiusValue!!)
                            },
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text(if (existing != null) "Save" else "Create")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // Interactive map picker
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .clipToBounds()
                ) {
                    GeofencePickerMap(
                        lat = lat,
                        lng = lng,
                        radiusMetres = radiusForMap,
                        initialCamera = initialCamera,
                        recenterTo = recenterTo,
                        onRecentered = { recenterTo = null },
                        onPointSelected = { la, ln ->
                            latText = formatCoord(la)
                            lngText = formatCoord(ln)
                        },
                        onRadiusChange = { radiusText = it.toString() },
                        modifier = Modifier
                            .fillMaxSize()
                            .clipToBounds()
                    )

                    SmallFloatingActionButton(
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
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        if (loadingMyLocation) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.MyLocation, "My location")
                        }
                    }

                    val hint = if (lat == null || lng == null) {
                        "Tap the map to place"
                    } else {
                        "Pinch to resize • Drag to move"
                    }
                    val hintAlign = if (lat == null || lng == null) Alignment.TopCenter else Alignment.BottomCenter
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .align(hintAlign)
                            .padding(8.dp)
                    ) {
                        Text(
                            hint,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                // Configuration form
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Basic Info", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Name") },
                            placeholder = { Text("e.g. Home, Work") },
                            isError = nameError,
                            supportingText = if (nameError) { { Text("Name is required") } } else null,
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Fence, null) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    HorizontalDivider(thickness = 0.5.dp)

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Coordinates", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = latText,
                                onValueChange = { latText = it },
                                label = { Text("Latitude") },
                                isError = latError,
                                supportingText = if (latError) { { Text("−90 to 90") } } else null,
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                            )
                            OutlinedTextField(
                                value = lngText,
                                onValueChange = { lngText = it },
                                label = { Text("Longitude") },
                                isError = lngError,
                                supportingText = if (lngError) { { Text("−180 to 180") } } else null,
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                            )
                        }
                    }

                    HorizontalDivider(thickness = 0.5.dp)

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Radius", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            // Typing here updates radiusText, the same state the slider reads from,
                            // so entering a value moves the slider (and vice versa) automatically.
                            // Cap the digit count so a pasted value can't overflow Int and make
                            // toIntOrNull() return null (which would snap the slider to the fallback).
                            OutlinedTextField(
                                value = radiusText,
                                onValueChange = { input -> radiusText = input.filter { it.isDigit() }.take(6) },
                                label = { Text("Radius") },
                                isError = radiusError,
                                singleLine = true,
                                suffix = { Text("m") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(160.dp)
                            )
                        }
                        Slider(
                            value = radiusForMap.toFloat(),
                            onValueChange = { radiusText = it.roundToInt().toString() },
                            valueRange = MIN_RADIUS_M.toFloat()..MAX_RADIUS_M.toFloat(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            if (radiusError)
                                "Enter $MIN_RADIUS_M to $MAX_RADIUS_M m"
                            else
                                com.locapeer.util.DisplayFormat.distanceValue(radiusForMap.toDouble()),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (radiusError) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** Assign a shared geofence area to the current contact and pick its enter/exit trigger. */
@Composable
private fun AssignmentDialog(
    existing: AssignmentWithArea?,
    areas: List<GeofenceEntity>,
    onDismiss: () -> Unit,
    onSave: (geofenceId: String, triggerOn: String) -> Unit
) {
    var selectedGeofenceId by remember {
        mutableStateOf(existing?.geofenceId ?: areas.firstOrNull()?.id ?: "")
    }
    var triggerOn by remember { mutableStateOf(existing?.triggerOn ?: "ENTER") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing != null) "Edit Assignment" else "Assign Geofence") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Select Geofence Area", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                areas.forEach { area ->
                    Surface(
                        onClick = { selectedGeofenceId = area.id },
                        shape = MaterialTheme.shapes.medium,
                        color = if (selectedGeofenceId == area.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        border = if (selectedGeofenceId == area.id) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedGeofenceId == area.id,
                                onClick = null // Handled by Surface onClick
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(area.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text(
                                    com.locapeer.util.DisplayFormat.distanceValue(area.radiusMetres.toDouble()),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Text("Trigger Notification On", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("ENTER", "EXIT", "BOTH").forEach { t ->
                        FilterChip(
                            selected = triggerOn == t,
                            onClick = { triggerOn = t },
                            label = { Text(t.lowercase().replaceFirstChar { it.uppercase() }) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { if (selectedGeofenceId.isNotEmpty()) onSave(selectedGeofenceId, triggerOn) }) {
                Text(if (existing != null) "Save" else "Assign")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
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
                clipToOutline = true
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
                val strokeColor = GeofenceBoth
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

                    // Modern themed pin
                    val icon = ContextCompat.getDrawable(mv.context, R.drawable.ic_notif_location)?.apply {
                        val wrapped = DrawableCompat.wrap(this).mutate()
                        DrawableCompat.setTint(wrapped, strokeArgb)
                    }
                    if (icon != null) {
                        this.icon = icon
                    }

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
