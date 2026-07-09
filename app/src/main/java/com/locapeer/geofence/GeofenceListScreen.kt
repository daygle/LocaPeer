package com.locapeer.geofence

import android.annotation.SuppressLint
import android.view.ScaleGestureDetector
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import com.locapeer.sharing.DayPicker
import com.locapeer.sharing.RuleEditDialog
import com.locapeer.sharing.ScheduleRule
import com.locapeer.sharing.SharingSchedule
import com.locapeer.sharing.newScheduleRule
import com.locapeer.sharing.toScheduleRules
import com.locapeer.ui.components.EmptyState
import com.locapeer.ui.theme.GeofenceBoth
import com.locapeer.ui.theme.GeofenceEnter
import com.locapeer.ui.theme.GeofenceExit
import com.locapeer.util.Geocoding
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
                title = { Text(stringResource(R.string.settings_geofences)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.geo_cd_add_area))
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            items(areas, key = { it.id }) { area ->
                val unknownName = stringResource(R.string.geo_unknown)
                val names = assignmentsByFence[area.id].orEmpty()
                    .map { nameByDevice[it.trackedDeviceId] ?: unknownName }
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
                        title = stringResource(R.string.geo_empty_title),
                        subtitle = stringResource(R.string.geo_empty_sub),
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
            title = { Text(stringResource(R.string.geo_delete_title)) },
            text = {
                Text(
                    if (count > 0)
                        pluralStringResource(R.plurals.geo_delete_assigned, count, area.name, count)
                    else
                        stringResource(R.string.geo_delete_simple, area.name)
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.deleteArea(area); pendingDelete = null }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel)) } }
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
    val title = if (contactName != null) stringResource(R.string.geo_for_contact, contactName) else stringResource(R.string.settings_geofences)

    var showAssignDialog by remember { mutableStateOf(false) }
    var editingAssignment by remember { mutableStateOf<AssignmentWithArea?>(null) }
    var showNoAreas by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { if (areas.isEmpty()) showNoAreas = true else showAssignDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.geo_cd_assign))
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
                        title = stringResource(R.string.geo_empty_contact_title),
                        subtitle = stringResource(R.string.geo_empty_contact_sub),
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
            onSave = { geofenceId, trigger, scheduleRules ->
                val current = editingAssignment
                if (current != null) {
                    vm.updateAssignment(current, peerId, geofenceId, trigger, scheduleRules)
                } else {
                    vm.addAssignment(geofenceId, peerId, trigger, scheduleRules)
                }
                showAssignDialog = false
                editingAssignment = null
            }
        )
    }

    if (showNoAreas) {
        AlertDialog(
            onDismissRequest = { showNoAreas = false },
            title = { Text(stringResource(R.string.geo_no_areas_title)) },
            text = { Text(stringResource(R.string.geo_no_areas_msg)) },
            confirmButton = { TextButton(onClick = { showNoAreas = false }) { Text(stringResource(R.string.common_ok)) } }
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
            .padding(horizontal = 16.dp, vertical = 6.dp)
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
                    stringResource(R.string.geo_radius_label, com.locapeer.util.DisplayFormat.distanceValue(area.radiusMetres.toDouble())),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (assignedNames.isNotEmpty()) {
                    Text(
                        stringResource(R.string.geo_contacts_label, assignedNames.joinToString(", ")),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        stringResource(R.string.geo_no_contacts_assigned),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.common_edit))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.common_delete), tint = MaterialTheme.colorScheme.error)
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
    val triggerLabel = when (assignment.triggerOn) {
        "ENTER" -> stringResource(R.string.geo_trigger_enter)
        "EXIT" -> stringResource(R.string.geo_trigger_exit)
        else -> stringResource(R.string.geo_trigger_both)
    }
    val rules = remember(assignment.scheduleRules) { assignment.scheduleRules.toScheduleRules() }
    val hasSchedule = rules.isNotEmpty()

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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        color = triggerColor.copy(alpha = 0.2f),
                        shape = MaterialTheme.shapes.extraSmall,
                        modifier = Modifier.padding(vertical = 2.dp)
                    ) {
                        Text(
                            triggerLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = triggerColor,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                    if (hasSchedule) {
                        Icon(Icons.Default.Schedule, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(
                            pluralStringResource(R.plurals.geo_rules_count, rules.size, rules.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
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
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.common_edit))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.common_remove), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * Formats a coordinate with a fixed locale. These strings are parsed back with
 * toDoubleOrNull(), which only accepts '.' - a locale-dependent ',' separator would
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
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var latText by remember { mutableStateOf(existing?.lat?.let { formatCoord(it) } ?: "") }
    var lngText by remember { mutableStateOf(existing?.lng?.let { formatCoord(it) } ?: "") }
    var radiusText by remember { mutableStateOf((existing?.radiusMetres ?: 500).toString()) }
    var submitted by remember { mutableStateOf(false) }
    var loadingMyLocation by remember { mutableStateOf(false) }
    var recenterTo by remember { mutableStateOf<GeoPoint?>(null) }

    // Address search (forward geocoding). Hidden entirely when the device has no
    // geocoder backend, e.g. emulators or de-Googled ROMs.
    val geocoderAvailable = remember { Geocoding.isAvailable() }
    var addressQuery by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<Geocoding.Match>>(emptyList()) }
    var searchAttempted by remember { mutableStateOf(false) }
    val performSearch: () -> Unit = {
        val query = addressQuery.trim()
        if (query.isNotEmpty() && !searching) {
            searching = true
            searchResults = emptyList()
            searchAttempted = false
            scope.launch {
                searchResults = Geocoding.forwardGeocode(context, query)
                searching = false
                searchAttempted = true
            }
        }
    }

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
                    title = { Text(if (existing != null) stringResource(R.string.geo_edit_title) else stringResource(R.string.geo_new_title)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_cancel))
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
                            Text(if (existing != null) stringResource(R.string.common_save) else stringResource(R.string.common_create))
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
                            Icon(Icons.Default.MyLocation, stringResource(R.string.geo_cd_my_location))
                        }
                    }

                    val hint = if (lat == null || lng == null) {
                        stringResource(R.string.geo_hint_tap)
                    } else {
                        stringResource(R.string.geo_hint_pinch)
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
                        Text(stringResource(R.string.geo_basic_info), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text(stringResource(R.string.common_name)) },
                            placeholder = { Text(stringResource(R.string.geo_name_placeholder)) },
                            isError = nameError,
                            supportingText = if (nameError) { { Text(stringResource(R.string.geo_name_required)) } } else null,
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Fence, null) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (geocoderAvailable) {
                        HorizontalDivider(thickness = 0.5.dp)

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.geo_search_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            OutlinedTextField(
                                value = addressQuery,
                                onValueChange = { addressQuery = it; searchAttempted = false },
                                label = { Text(stringResource(R.string.geo_search_label)) },
                                placeholder = { Text(stringResource(R.string.geo_search_placeholder)) },
                                singleLine = true,
                                leadingIcon = { Icon(Icons.Default.Search, null) },
                                trailingIcon = {
                                    if (searching) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    } else if (addressQuery.isNotBlank()) {
                                        IconButton(onClick = performSearch) {
                                            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.geo_cd_search))
                                        }
                                    }
                                },
                                supportingText = if (searchAttempted && searchResults.isEmpty()) {
                                    { Text(stringResource(R.string.geo_search_no_results)) }
                                } else null,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { performSearch() }),
                                modifier = Modifier.fillMaxWidth()
                            )
                            searchResults.forEach { match ->
                                Surface(
                                    onClick = {
                                        latText = formatCoord(match.lat)
                                        lngText = formatCoord(match.lng)
                                        recenterTo = GeoPoint(match.lat, match.lng)
                                        addressQuery = match.label
                                        searchResults = emptyList()
                                        searchAttempted = false
                                    },
                                    shape = MaterialTheme.shapes.medium,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.LocationOn,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Text(match.label, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(thickness = 0.5.dp)

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.history_detail_coordinates), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = latText,
                                onValueChange = { latText = it },
                                label = { Text(stringResource(R.string.geo_latitude)) },
                                isError = latError,
                                supportingText = if (latError) { { Text(stringResource(R.string.geo_lat_range)) } } else null,
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                            )
                            OutlinedTextField(
                                value = lngText,
                                onValueChange = { lngText = it },
                                label = { Text(stringResource(R.string.geo_longitude)) },
                                isError = lngError,
                                supportingText = if (lngError) { { Text(stringResource(R.string.geo_lng_range)) } } else null,
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
                            Text(stringResource(R.string.geo_radius), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            // Typing here updates radiusText, the same state the slider reads from,
                            // so entering a value moves the slider (and vice versa) automatically.
                            // Cap the digit count so a pasted value can't overflow Int and make
                            // toIntOrNull() return null (which would snap the slider to the fallback).
                            // One digit beyond MAX_RADIUS_M's width still lets an out-of-range entry
                            // surface through normal validation.
                            val maxRadiusDigits = MAX_RADIUS_M.toString().length + 1
                            OutlinedTextField(
                                value = radiusText,
                                onValueChange = { input -> radiusText = input.filter { it.isDigit() }.take(maxRadiusDigits) },
                                label = { Text(stringResource(R.string.geo_radius)) },
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
                                stringResource(R.string.geo_radius_range, MIN_RADIUS_M, MAX_RADIUS_M)
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
    onSave: (geofenceId: String, triggerOn: String, scheduleRules: String) -> Unit
) {
    var selectedGeofenceId by remember {
        mutableStateOf(existing?.geofenceId ?: areas.firstOrNull()?.id ?: "")
    }
    var triggerOn by remember { mutableStateOf(existing?.triggerOn ?: "ENTER") }
    var scheduleRules by remember {
        mutableStateOf(existing?.scheduleRules?.toScheduleRules() ?: emptyList())
    }

    var editingRule by remember { mutableStateOf<ScheduleRule?>(null) }
    var isNewRule by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing != null) stringResource(R.string.geo_edit_assignment) else stringResource(R.string.geo_assign_title)) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.geo_select_area), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
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

                Text(stringResource(R.string.geo_trigger_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("ENTER", "EXIT", "BOTH").forEach { t ->
                        val chipLabel = when (t) {
                            "ENTER" -> stringResource(R.string.geo_trigger_enter)
                            "EXIT" -> stringResource(R.string.geo_trigger_exit)
                            else -> stringResource(R.string.geo_trigger_both)
                        }
                        FilterChip(
                            selected = triggerOn == t,
                            onClick = { triggerOn = t },
                            label = { Text(chipLabel) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.peer_alert_schedule), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = { editingRule = newScheduleRule(); isNewRule = true }) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.geo_add_rule))
                    }
                }

                if (scheduleRules.isEmpty()) {
                    Text(
                        stringResource(R.string.geo_alerts_all_times),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    scheduleRules.forEach { rule ->
                        Card(
                            onClick = { editingRule = rule; isNewRule = false },
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    if (rule.label.isNotBlank()) {
                                        Text(rule.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                    }
                                    Text(
                                        "${SharingSchedule.formatDays(rule.days)} • ${SharingSchedule.formatTime(rule.startMinute)} - ${SharingSchedule.formatTime(rule.endMinute)}",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                IconButton(onClick = { scheduleRules = scheduleRules.filter { it.id != rule.id } }) {
                                    Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (selectedGeofenceId.isNotEmpty()) {
                    val rulesJson = Json.encodeToString(scheduleRules)
                    onSave(selectedGeofenceId, triggerOn, rulesJson)
                }
            }) {
                Text(if (existing != null) stringResource(R.string.common_save) else stringResource(R.string.geo_assign_action))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )

    editingRule?.let { rule ->
        RuleEditDialog(
            rule = rule,
            onRuleChanged = { editingRule = it },
            onConfirm = {
                scheduleRules = if (isNewRule) scheduleRules + rule
                               else scheduleRules.map { if (it.id == rule.id) rule else it }
                editingRule = null
            },
            onDismiss = { editingRule = null }
        )
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
