package com.locapeer.geofence

import com.locapeer.ui.components.AppSlider
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.net.Uri
import android.provider.OpenableColumns
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import com.locapeer.ui.theme.locaPeerTopAppBarColors
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.DrawableCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.android.gms.location.LocationServices
import com.locapeer.R
import com.locapeer.supervised.SupervisionGate
import com.locapeer.supervised.SupervisionGateViewModel
import com.locapeer.data.dao.AssignmentWithArea
import com.locapeer.data.entity.GeofenceEntity
import com.locapeer.sharing.RuleEditDialog
import com.locapeer.sharing.ScheduleRule
import com.locapeer.sharing.SharingSchedule
import com.locapeer.sharing.newScheduleRule
import com.locapeer.sharing.toScheduleRules
import com.locapeer.ui.components.CardDivider
import com.locapeer.ui.components.EmptyState
import com.locapeer.ui.components.SettingsCard
import com.locapeer.ui.theme.GeofenceBoth
import com.locapeer.ui.theme.GeofenceEnter
import com.locapeer.ui.theme.GeofenceExit
import com.locapeer.util.GeoMath
import com.locapeer.util.Geocoding
import com.locapeer.util.GeofencePolygon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.maplibre.android.geometry.LatLng
import com.locapeer.map.LocaMap
import com.locapeer.map.LocaMapController
import com.locapeer.map.MapCircle
import com.locapeer.map.MapGeometry
import com.locapeer.map.MapLine
import com.locapeer.map.MapMarker
import com.locapeer.map.MapPolygon
import com.locapeer.map.MarkerAnchor
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.roundToInt

@Composable
fun GeofenceListScreen(
    peerId: String? = null,
    initialAreaId: String? = null,
    onNavigateBack: () -> Unit,
    vm: GeofenceViewModel = hiltViewModel()
) {
    val gateVm: SupervisionGateViewModel = hiltViewModel()
    val supervisedModeEnabled by gateVm.supervisedModeEnabled.collectAsStateWithLifecycle()
    val gateUnlockState by gateVm.unlockState.collectAsStateWithLifecycle()
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
        GlobalGeofencesScreen(
            vm = vm,
            onNavigateBack = onNavigateBack,
            initialAreaId = initialAreaId
        )
    } else {
        ContactGeofencesScreen(peerId = peerId, vm = vm, onNavigateBack = onNavigateBack)
    }
}

/** Manage the shared geofence areas (location + radius). Contacts are assigned separately. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlobalGeofencesScreen(
    vm: GeofenceViewModel,
    onNavigateBack: () -> Unit,
    initialAreaId: String? = null
) {
    val areas by vm.geofences.collectAsStateWithLifecycle()
    val assignments by vm.allAssignments.collectAsStateWithLifecycle()
    val broadcasters by vm.receiveContactsWithLocation.collectAsStateWithLifecycle()
    val addressSearchEnabled by vm.addressSearchEnabled.collectAsStateWithLifecycle()

    var showCreateDialog by remember { mutableStateOf(false) }
    var editingArea by remember { mutableStateOf<GeofenceEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<GeofenceEntity?>(null) }
    // A map tap on a fence label navigates here with that area id; open its editor once
    // the areas have loaded, and never re-open it after the user dismisses the dialog.
    var consumedInitialAreaId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(areas, initialAreaId) {
        val id = initialAreaId ?: return@LaunchedEffect
        if (id == consumedInitialAreaId) return@LaunchedEffect
        areas.firstOrNull { it.id == id }?.let {
            editingArea = it
            consumedInitialAreaId = id
        }
    }

    val nameByDevice = broadcasters.associate { it.peer.deviceId to it.peer.displayName }
    val assignmentsByFence = assignments.groupBy { it.geofenceId }

    // Import/export. Both formats are plain text, so the picker accepts any file and the
    // parser decides by content - a GeoJSON file named .txt (or vice versa) still works.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exchangeMessage by remember { mutableStateOf<ExchangeMessage?>(null) }
    var exportMenuOpen by remember { mutableStateOf(false) }

    fun readText(uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
    }.getOrNull()

    fun writeText(uri: Uri, payload: String) {
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)
                        ?.bufferedWriter()?.use { it.write(payload) } != null
                }.getOrDefault(false)
            }
            if (!written) exchangeMessage = ExchangeMessage.FileError
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val fileName = withContext(Dispatchers.IO) { displayName(context, uri) }
                val text = withContext(Dispatchers.IO) { readText(uri) }
                val imported = text?.let {
                    runCatching { GeofenceExchange.parse(it, fileName) }.getOrNull()
                }
                when {
                    imported == null -> exchangeMessage = ExchangeMessage.FileError
                    imported.isEmpty() -> exchangeMessage = ExchangeMessage.NoAreas
                    else -> {
                        imported.forEach {
                            vm.createArea(it.name, it.lat, it.lng, it.radiusMetres, it.polygon)
                        }
                        exchangeMessage = ExchangeMessage.Imported(imported.size)
                    }
                }
            }
        }
    }

    val exportGeoJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/geo+json")
    ) { uri -> if (uri != null) writeText(uri, GeofenceExchange.exportGeoJson(areas)) }

    val exportKmlLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.google-earth.kml+xml")
    ) { uri -> if (uri != null) writeText(uri, GeofenceExchange.exportKml(areas)) }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = locaPeerTopAppBarColors(),
                title = { Text(stringResource(R.string.settings_geofences)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                        Icon(
                            Icons.Default.FileUpload,
                            contentDescription = stringResource(R.string.geo_import)
                        )
                    }
                    Box {
                        IconButton(onClick = { exportMenuOpen = true }) {
                            Icon(
                                Icons.Default.FileDownload,
                                contentDescription = stringResource(R.string.geo_export)
                            )
                        }
                        DropdownMenu(
                            expanded = exportMenuOpen,
                            onDismissRequest = { exportMenuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.geo_export_geojson)) },
                                onClick = {
                                    exportMenuOpen = false
                                    exportGeoJsonLauncher.launch("locapeer-geofences.json")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.geo_export_kml)) },
                                onClick = {
                                    exportMenuOpen = false
                                    exportKmlLauncher.launch("locapeer-geofences.kml")
                                }
                            )
                        }
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
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp)
        ) {
            if (areas.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Default.Fence,
                        title = stringResource(R.string.geo_empty_title),
                        subtitle = stringResource(R.string.geo_empty_sub),
                        modifier = Modifier.fillParentMaxSize()
                    )
                }
            } else {
                item {
                    val unknownName = stringResource(R.string.geo_unknown)
                    SettingsCard(
                        headerIcon = Icons.Default.Fence,
                        headerTitle = stringResource(R.string.geo_section_areas)
                    ) {
                        areas.forEachIndexed { index, area ->
                            val names = assignmentsByFence[area.id].orEmpty()
                                .map { nameByDevice[it.trackedDeviceId] ?: unknownName }
                                .distinct()
                            GeofenceAreaRow(
                                area = area,
                                assignedNames = names,
                                onEdit = { editingArea = area },
                                onDelete = { pendingDelete = area }
                            )
                            if (index < areas.lastIndex) {
                                CardDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog || editingArea != null) {
        GeofenceAreaDialog(
            existing = editingArea,
            addressSearchEnabled = addressSearchEnabled,
            onDismiss = { showCreateDialog = false; editingArea = null },
            onSave = { name, lat, lng, radius, polygon ->
                val current = editingArea
                if (current != null) {
                    vm.updateArea(current.copy(name = name, lat = lat, lng = lng, radiusMetres = radius, polygon = polygon))
                } else {
                    vm.createArea(name, lat, lng, radius, polygon)
                }
                showCreateDialog = false
                editingArea = null
            }
        )
    }

    exchangeMessage?.let { message ->
        val text = when (message) {
            ExchangeMessage.FileError -> stringResource(R.string.geo_file_failed)
            ExchangeMessage.NoAreas -> stringResource(R.string.geo_import_empty)
            is ExchangeMessage.Imported ->
                stringResource(R.string.geo_import_done, message.count)
        }
        AlertDialog(
            onDismissRequest = { exchangeMessage = null },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { exchangeMessage = null }) {
                    Text(stringResource(R.string.common_ok))
                }
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
    val areas by vm.geofences.collectAsStateWithLifecycle()
    val broadcasters by vm.receiveContactsWithLocation.collectAsStateWithLifecycle()
    val assignments by remember(peerId) { vm.assignmentsForContact(peerId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val contactName = broadcasters.find { it.peer.deviceId == peerId }?.peer?.displayName
    val title = if (contactName != null) stringResource(R.string.geo_for_contact, contactName) else stringResource(R.string.settings_geofences)

    var showAssignDialog by remember { mutableStateOf(false) }
    var editingAssignment by remember { mutableStateOf<AssignmentWithArea?>(null) }
    var pendingDelete by remember { mutableStateOf<AssignmentWithArea?>(null) }
    var showNoAreas by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = locaPeerTopAppBarColors(),
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
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp)
        ) {
            if (assignments.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Default.Fence,
                        title = stringResource(R.string.geo_empty_contact_title),
                        subtitle = stringResource(R.string.geo_empty_contact_sub),
                        modifier = Modifier.fillParentMaxSize()
                    )
                }
            } else {
                item {
                    SettingsCard(
                        headerIcon = Icons.Default.Fence,
                        headerTitle = stringResource(R.string.geo_section_assignments)
                    ) {
                        assignments.forEachIndexed { index, assignment ->
                            AssignmentRow(
                                assignment = assignment,
                                onToggle = { vm.setAssignmentActive(assignment.assignmentId, it) },
                                onEdit = { editingAssignment = assignment },
                                onDelete = { pendingDelete = assignment }
                            )
                            if (index < assignments.lastIndex) {
                                CardDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { assignment ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.geo_delete_title)) },
            text = { Text(stringResource(R.string.geo_delete_simple, assignment.name)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeAssignment(assignment.assignmentId)
                    pendingDelete = null
                }) { Text(stringResource(R.string.common_remove)) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
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
private fun GeofenceAreaRow(
    area: GeofenceEntity,
    assignedNames: List<String>,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onEdit),
        leadingContent = {
            Icon(Icons.Default.Fence, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        supportingContent = {
            val ring = remember(area.polygon) { GeofencePolygon.decode(area.polygon) }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (ring != null) stringResource(R.string.geo_polygon_summary, ring.size)
                    else stringResource(R.string.geo_radius_label, com.locapeer.util.DisplayFormat.distanceValue(area.radiusMetres.toDouble())),
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
        },
        trailingContent = {
            RowOverflowMenu(onEdit = onEdit, onDelete = onDelete, deleteLabel = stringResource(R.string.common_delete))
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    ) {
        Text(area.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AssignmentRow(
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
    val ring = remember(assignment.polygon) { GeofencePolygon.decode(assignment.polygon) }

    ListItem(
        modifier = Modifier.clickable(onClick = onEdit),
        leadingContent = {
            Icon(Icons.Default.Fence, contentDescription = null, tint = triggerColor)
        },
        supportingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 2.dp)
            ) {
                Surface(
                    color = triggerColor.copy(alpha = 0.2f),
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Text(
                        triggerLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = triggerColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
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
                    if (ring != null) stringResource(R.string.geo_polygon_summary, ring.size)
                    else com.locapeer.util.DisplayFormat.distanceValue(assignment.radiusMetres.toDouble()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = assignment.active, onCheckedChange = onToggle)
                RowOverflowMenu(onEdit = onEdit, onDelete = onDelete, deleteLabel = stringResource(R.string.common_remove))
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    ) {
        Text(assignment.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** Overflow (⋮) menu offering Edit + a destructive Delete/Remove, matching the app-wide row idiom. */
@Composable
private fun RowOverflowMenu(
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    deleteLabel: String
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_more_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.common_edit)) },
                leadingIcon = { Icon(Icons.Default.Edit, null, Modifier.size(18.dp)) },
                onClick = { expanded = false; onEdit() }
            )
            DropdownMenuItem(
                text = { Text(deleteLabel, color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                },
                onClick = { expanded = false; onDelete() }
            )
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
 * Which geometry an editor is drawing. Both shapes share the name, map and validation
 * plumbing; the circle path keeps its centre + radius fields, the polygon path draws a
 * ring point by point on the map.
 */
private enum class GeofenceShape { CIRCLE, POLYGON }

/** One undoable editor state: the shape, the ring and the circle fields as they were. */
private data class PolygonEditorSnapshot(
    val shape: GeofenceShape,
    val points: List<Pair<Double, Double>>,
    val latText: String,
    val lngText: String,
    val radiusText: String
)

/** Cap on undo depth: enough for a drawing session, bounded so state stays small. */
private const val MAX_UNDO_STEPS = 50

/** Result of an import or export, resolved to text during composition. */
private sealed interface ExchangeMessage {
    data object FileError : ExchangeMessage
    data object NoAreas : ExchangeMessage
    data class Imported(val count: Int) : ExchangeMessage
}

/**
 * Full-screen editor for a shared geofence *area*: a circle (centre + radius) or a
 * polygon ring. Existing areas open in the shape they were saved as.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
private fun GeofenceAreaDialog(
    existing: GeofenceEntity?,
    addressSearchEnabled: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, lat: Double, lng: Double, radiusMetres: Int, polygon: String?) -> Unit
) {
    val context = LocalContext.current
    val fusedLocation = remember { LocationServices.getFusedLocationProviderClient(context) }
    val scope = rememberCoroutineScope()

    val existingRing = remember(existing?.id) { GeofencePolygon.decode(existing?.polygon) }
    var shape by remember { mutableStateOf(if (existingRing != null) GeofenceShape.POLYGON else GeofenceShape.CIRCLE) }
    var polygonPoints by remember { mutableStateOf(existingRing ?: emptyList()) }

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var latText by remember { mutableStateOf(existing?.lat?.let { formatCoord(it) } ?: "") }
    var lngText by remember { mutableStateOf(existing?.lng?.let { formatCoord(it) } ?: "") }
    var radiusText by remember { mutableStateOf((existing?.radiusMetres ?: 500).toString()) }
    var submitted by remember { mutableStateOf(false) }
    var loadingMyLocation by remember { mutableStateOf(false) }
    var recenterTo by remember { mutableStateOf<LatLng?>(null) }
    var capReached by remember { mutableStateOf(false) }
    var editingPointIndex by remember { mutableStateOf<Int?>(null) }
    var undoStack by remember { mutableStateOf<List<PolygonEditorSnapshot>>(emptyList()) }
    var redoStack by remember { mutableStateOf<List<PolygonEditorSnapshot>>(emptyList()) }

    fun snapshot() = PolygonEditorSnapshot(shape, polygonPoints, latText, lngText, radiusText)

    fun recordEdit() {
        undoStack = (undoStack + snapshot()).takeLast(MAX_UNDO_STEPS)
        redoStack = emptyList()
    }

    fun restoreSnapshot(value: PolygonEditorSnapshot) {
        shape = value.shape
        polygonPoints = value.points
        latText = value.latText
        lngText = value.lngText
        radiusText = value.radiusText
    }

    fun undoEdit() {
        val previous = undoStack.lastOrNull() ?: return
        redoStack = (redoStack + snapshot()).takeLast(MAX_UNDO_STEPS)
        undoStack = undoStack.dropLast(1)
        restoreSnapshot(previous)
        capReached = false
    }

    fun redoEdit() {
        val next = redoStack.lastOrNull() ?: return
        undoStack = (undoStack + snapshot()).takeLast(MAX_UNDO_STEPS)
        redoStack = redoStack.dropLast(1)
        restoreSnapshot(next)
        capReached = false
    }

    // Address search (forward geocoding). Hidden while the "Look Up Addresses" opt-in
    // is off (queries go to the OS geocoding backend) and when the device has no
    // geocoder backend at all, e.g. emulators or de-Googled ROMs.
    val geocoderAvailable = remember { Geocoding.isAvailable() }
    val showAddressSearch = addressSearchEnabled && geocoderAvailable
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
                try {
                    val results = Geocoding.forwardGeocode(context, query)
                    // Drop stale results: the user may have edited the query mid-flight.
                    if (addressQuery.trim() == query) {
                        searchResults = results
                        searchAttempted = true
                    }
                } finally {
                    searching = false
                }
            }
        }
    }

    val lat = latText.toDoubleOrNull()
    val lng = lngText.toDoubleOrNull()
    val radiusValue = radiusText.toIntOrNull()
    val radiusForMap = (radiusValue ?: 500).coerceIn(MIN_RADIUS_M, MAX_RADIUS_M)
    val nameError = submitted && name.isBlank()
    val latError = submitted && shape == GeofenceShape.CIRCLE && (lat == null || lat !in -90.0..90.0)
    val lngError = submitted && shape == GeofenceShape.CIRCLE && (lng == null || lng !in -180.0..180.0)
    val radiusError = submitted && shape == GeofenceShape.CIRCLE &&
        (radiusValue == null || radiusValue !in MIN_RADIUS_M..MAX_RADIUS_M)
    val polygonValid = polygonPoints.size >= 3 && GeoMath.isRingSimple(polygonPoints)
    val polygonError = submitted && shape == GeofenceShape.POLYGON && !polygonValid
    val isValid = name.isNotBlank() && when (shape) {
        GeofenceShape.CIRCLE ->
            lat != null && lat in -90.0..90.0 &&
                lng != null && lng in -180.0..180.0 &&
                radiusValue != null && radiusValue in MIN_RADIUS_M..MAX_RADIUS_M
        GeofenceShape.POLYGON -> polygonValid
    }

    /**
     * Switching to a polygon keeps the user's circle intent by seeding a ring from it.
     * The 24-gon is scaled so its area matches the circle's: an inscribed polygon is
     * slightly smaller than the circle it approximates.
     */
    fun circleToPolygon() {
        if (polygonPoints.isNotEmpty()) return
        val cLat = lat ?: return
        val cLng = lng ?: return
        val segments = 24
        val areaPreservingFactor = sqrt(
            2 * PI / (segments * sin(2 * PI / segments))
        )
        // MapGeometry.circle repeats its first point to close the ring; the editor keeps
        // rings open, with the closing edge implicit, so drop that duplicate.
        polygonPoints = MapGeometry.circle(
            cLat, cLng, radiusForMap * areaPreservingFactor, segments = segments
        ).dropLast(1)
    }

    /**
     * Switching back to a circle uses the ring's centroid and the radius of a circle with
     * the same area. The bounding radius would almost double a long, thin area.
     */
    fun polygonToCircle() {
        if (polygonPoints.size < 3) return
        val (cLat, cLng) = GeoMath.ringCentroid(polygonPoints)
        latText = formatCoord(cLat)
        lngText = formatCoord(cLng)
        radiusText = GeoMath.ringEffectiveRadiusMetres(polygonPoints)
            .roundToInt()
            .coerceIn(MIN_RADIUS_M, MAX_RADIUS_M)
            .toString()
    }

    val initialCamera = remember { existing?.let { LatLng(it.lat, it.lng) } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    colors = locaPeerTopAppBarColors(),
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
                                if (!isValid) return@Button
                                when (shape) {
                                    GeofenceShape.CIRCLE -> onSave(name, lat!!, lng!!, radiusValue!!, null)
                                    GeofenceShape.POLYGON -> {
                                        val (cLat, cLng) = GeoMath.ringCentroid(polygonPoints)
                                        val boundingRadius = GeoMath.ringBoundingRadiusMetres(polygonPoints)
                                            .roundToInt()
                                            .coerceIn(MIN_RADIUS_M, MAX_RADIUS_M)
                                        onSave(name, cLat, cLng, boundingRadius, GeofencePolygon.encode(polygonPoints))
                                    }
                                }
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
                        polygon = polygonPoints,
                        polygonMode = shape == GeofenceShape.POLYGON,
                        initialCamera = initialCamera,
                        recenterTo = recenterTo,
                        onRecentered = { recenterTo = null },
                        onPointSelected = { la, ln ->
                            if (shape == GeofenceShape.POLYGON) {
                                if (polygonPoints.size < GeofencePolygon.MAX_POINTS) {
                                    recordEdit()
                                    polygonPoints = polygonPoints + (la to ln)
                                    capReached = false
                                } else {
                                    capReached = true
                                }
                            } else {
                                latText = formatCoord(la)
                                lngText = formatCoord(ln)
                            }
                        },
                        onVertexMoved = { index, la, ln ->
                            if (index in polygonPoints.indices) {
                                polygonPoints = polygonPoints.toMutableList()
                                    .also { it[index] = la to ln }
                            }
                        },
                        onVertexDragStart = { recordEdit() },
                        modifier = Modifier
                            .fillMaxSize()
                            .clipToBounds()
                    )

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        if (shape == GeofenceShape.CIRCLE) {
                            SmallFloatingActionButton(
                                onClick = { radiusText = stepRadius(radiusForMap, up = true).toString() },
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Icon(Icons.Default.Add, stringResource(R.string.geo_cd_radius_increase))
                            }
                            SmallFloatingActionButton(
                                onClick = { radiusText = stepRadius(radiusForMap, up = false).toString() },
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Icon(Icons.Default.Remove, stringResource(R.string.geo_cd_radius_decrease))
                            }
                        }
                        SmallFloatingActionButton(
                            onClick = {
                                loadingMyLocation = true
                                fusedLocation.lastLocation.addOnSuccessListener { loc ->
                                    loadingMyLocation = false
                                    if (loc != null) {
                                        if (shape == GeofenceShape.CIRCLE) {
                                            latText = formatCoord(loc.latitude)
                                            lngText = formatCoord(loc.longitude)
                                        }
                                        recenterTo = LatLng(loc.latitude, loc.longitude)
                                    }
                                }.addOnFailureListener { loadingMyLocation = false }
                            },
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            if (loadingMyLocation) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.MyLocation, stringResource(R.string.geo_cd_my_location))
                            }
                        }
                    }

                    val hint = when {
                        shape == GeofenceShape.POLYGON -> stringResource(R.string.geo_polygon_hint)
                        lat == null || lng == null -> stringResource(R.string.geo_hint_tap)
                        else -> stringResource(R.string.geo_hint_move)
                    }
                    val showHintAtTop = shape == GeofenceShape.POLYGON || lat == null || lng == null
                    val hintAlign = if (showHintAtTop) Alignment.TopCenter else Alignment.BottomCenter
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

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.geo_shape), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = shape == GeofenceShape.CIRCLE,
                                onClick = {
                                    if (shape != GeofenceShape.CIRCLE) {
                                        recordEdit()
                                        if (shape == GeofenceShape.POLYGON) polygonToCircle()
                                        shape = GeofenceShape.CIRCLE
                                    }
                                },
                                label = { Text(stringResource(R.string.geo_shape_circle)) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = shape == GeofenceShape.POLYGON,
                                onClick = {
                                    if (shape != GeofenceShape.POLYGON) {
                                        recordEdit()
                                        if (shape == GeofenceShape.CIRCLE) circleToPolygon()
                                        shape = GeofenceShape.POLYGON
                                    }
                                },
                                label = { Text(stringResource(R.string.geo_shape_polygon)) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    if (showAddressSearch) {
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
                                        if (shape == GeofenceShape.CIRCLE) {
                                            latText = formatCoord(match.lat)
                                            lngText = formatCoord(match.lng)
                                        } else if (polygonPoints.size < GeofencePolygon.MAX_POINTS) {
                                            // A place picked in polygon mode becomes the next vertex.
                                            recordEdit()
                                            polygonPoints = polygonPoints + (match.lat to match.lng)
                                            capReached = false
                                        } else {
                                            capReached = true
                                        }
                                        recenterTo = LatLng(match.lat, match.lng)
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

                    if (shape == GeofenceShape.CIRCLE) {
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
                            AppSlider(
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
                    } else {
                        HorizontalDivider(thickness = 0.5.dp)

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.geo_shape_polygon), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            Text(
                                stringResource(R.string.geo_polygon_summary, polygonPoints.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val polygonArea = remember(polygonPoints) { GeoMath.ringAreaSquareMetres(polygonPoints) }
                            val polygonPerimeter = remember(polygonPoints) { GeoMath.ringPerimeterMetres(polygonPoints) }
                            if (polygonPoints.size >= 3) {
                                Text(
                                    "${com.locapeer.util.DisplayFormat.areaValue(polygonArea)} · " +
                                        com.locapeer.util.DisplayFormat.distanceValue(polygonPerimeter),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (polygonValid) MaterialTheme.colorScheme.onSurfaceVariant
                                            else MaterialTheme.colorScheme.error
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { undoEdit() },
                                    enabled = undoStack.isNotEmpty(),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.geo_polygon_undo))
                                }
                                OutlinedButton(
                                    onClick = { redoEdit() },
                                    enabled = redoStack.isNotEmpty(),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.geo_polygon_redo))
                                }
                                OutlinedButton(
                                    onClick = {
                                        recordEdit()
                                        polygonPoints = emptyList()
                                        capReached = false
                                    },
                                    enabled = polygonPoints.isNotEmpty(),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.geo_polygon_clear))
                                }
                            }
                            if (polygonError) {
                                Text(
                                    stringResource(
                                        if (polygonPoints.size < 3) R.string.geo_polygon_min_points
                                        else R.string.geo_polygon_edges_cross
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            if (capReached) {
                                Text(
                                    stringResource(
                                        R.string.geo_polygon_max_points,
                                        GeofencePolygon.MAX_POINTS
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            polygonPoints.forEachIndexed { index, point ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(
                                            onClickLabel = stringResource(R.string.common_edit)
                                        ) { editingPointIndex = index },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "${index + 1}. ${formatCoord(point.first)}, ${formatCoord(point.second)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = {
                                            if (polygonPoints.size >= GeofencePolygon.MAX_POINTS) {
                                                capReached = true
                                            } else {
                                                recordEdit()
                                                val next = polygonPoints[(index + 1) % polygonPoints.size]
                                                val middle = GeoMath.ringCentroid(listOf(point, next))
                                                polygonPoints = polygonPoints.toMutableList()
                                                    .also { it.add(index + 1, middle) }
                                                capReached = false
                                            }
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.Add,
                                            contentDescription = stringResource(R.string.geo_polygon_insert_point),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            recordEdit()
                                            polygonPoints = polygonPoints.filterIndexed { i, _ -> i != index }
                                            capReached = false
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = stringResource(R.string.common_remove),
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Tapping a point row opens numeric editing: an accessible, precise alternative to
    // dragging the vertex on the map.
    editingPointIndex?.let { index ->
        val point = polygonPoints.getOrNull(index)
        if (point != null) {
            var editLat by remember(index) { mutableStateOf(formatCoord(point.first)) }
            var editLng by remember(index) { mutableStateOf(formatCoord(point.second)) }
            val parsedLat = editLat.toDoubleOrNull()
            val parsedLng = editLng.toDoubleOrNull()
            val valuesValid = parsedLat != null && parsedLat in -90.0..90.0 &&
                parsedLng != null && parsedLng in -180.0..180.0
            AlertDialog(
                onDismissRequest = { editingPointIndex = null },
                title = { Text(stringResource(R.string.common_edit)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = editLat,
                            onValueChange = { editLat = it },
                            label = { Text(stringResource(R.string.geo_latitude)) },
                            isError = parsedLat == null || parsedLat !in -90.0..90.0,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = editLng,
                            onValueChange = { editLng = it },
                            label = { Text(stringResource(R.string.geo_longitude)) },
                            isError = parsedLng == null || parsedLng !in -180.0..180.0,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (valuesValid) {
                                recordEdit()
                                polygonPoints = polygonPoints.toMutableList()
                                    .also { it[index] = parsedLat to parsedLng }
                                editingPointIndex = null
                            }
                        },
                        enabled = valuesValid
                    ) {
                        Text(stringResource(R.string.common_save))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { editingPointIndex = null }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            )
        }
    }
}

/** Assign a shared geofence area to the current contact and pick its enter/exit trigger. */
@OptIn(ExperimentalMaterial3Api::class)
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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    colors = locaPeerTopAppBarColors(),
                    title = { Text(if (existing != null) stringResource(R.string.geo_edit_assignment) else stringResource(R.string.geo_assign_title)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_cancel))
                        }
                    },
                    actions = {
                        Button(
                            onClick = {
                                if (selectedGeofenceId.isNotEmpty()) {
                                    val rulesJson = Json.encodeToString(scheduleRules)
                                    onSave(selectedGeofenceId, triggerOn, rulesJson)
                                }
                            },
                            enabled = selectedGeofenceId.isNotEmpty(),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text(if (existing != null) stringResource(R.string.common_save) else stringResource(R.string.geo_assign_action))
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .selectableGroup()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.geo_select_area), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    areas.forEach { area ->
                        Surface(
                            onClick = { selectedGeofenceId = area.id },
                            shape = MaterialTheme.shapes.medium,
                            color = if (selectedGeofenceId == area.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            border = if (selectedGeofenceId == area.id) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
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
                                        GeofencePolygon.decode(area.polygon)?.let {
                                            stringResource(R.string.geo_polygon_summary, it.size)
                                        } ?: com.locapeer.util.DisplayFormat.distanceValue(area.radiusMetres.toDouble()),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(thickness = 0.5.dp)

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                }

                HorizontalDivider(thickness = 0.5.dp)

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            }
        }
    }

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

/** Display name of a picked document, used as the fallback name for imported areas. */
private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}.getOrNull()

/** Tap tolerance (dp) for reusing an existing polygon vertex instead of adding a new one. */
private const val VERTEX_TOUCH_RADIUS_DP = 24

/**
 * Whether [tap] lands within [radiusPx] of an existing vertex. Uses the map projection so the
 * tolerance stays constant on screen at any zoom; without a controller nothing is close.
 */
private fun isNearExistingVertex(
    controller: LocaMapController?,
    vertices: List<Pair<Double, Double>>,
    tap: LatLng,
    radiusPx: Float
): Boolean {
    val projection = controller?.map?.projection ?: return false
    val screen = projection.toScreenLocation(tap)
    return nearestVertexIndex(controller, vertices, screen.x, screen.y, radiusPx) != null
}

/**
 * Index of the nearest vertex within [radiusPx] of a map-view point (x, y), or null when the
 * touch starts clear of every vertex. Ties go to the first match, which is what a tap on a
 * shared point should do.
 */
private fun nearestVertexIndex(
    controller: LocaMapController?,
    vertices: List<Pair<Double, Double>>,
    x: Float,
    y: Float,
    radiusPx: Float
): Int? {
    val projection = controller?.map?.projection ?: return null
    var best: Int? = null
    var bestDistance = radiusPx
    vertices.forEachIndexed { index, (lat, lng) ->
        val point = projection.toScreenLocation(LatLng(lat, lng))
        val distance = hypot((point.x - x).toDouble(), (point.y - y).toDouble()).toFloat()
        if (distance <= bestDistance) {
            best = index
            bestDistance = distance
        }
    }
    return best
}

private const val MIN_RADIUS_M = 50
private const val MAX_RADIUS_M = 50000

/** Breathing room (px) around the geofence circle when fitting the camera on open. */
private const val MAP_FIT_PADDING_PX = 80

/**
 * Steps the radius up or down by ~10% so the on-map buttons feel proportionate
 * across the whole 50 m - 50 km range, rounding to a tidy multiple of 10 m.
 */
private fun stepRadius(current: Int, up: Boolean): Int {
    val step = (current / 10).coerceAtLeast(10)
    val next = if (up) current + step else current - step
    return (next / 10 * 10).coerceIn(MIN_RADIUS_M, MAX_RADIUS_M)
}

/**
 * A map for picking a geofence:
 * - circle mode: tap or long-press sets the centre, with the radius drawn around it,
 * - polygon mode: tap or long-press adds a vertex, and dragging an existing vertex moves it.
 * Pinch zooms the map in both modes.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
private fun GeofencePickerMap(
    lat: Double?,
    lng: Double?,
    radiusMetres: Int,
    polygon: List<Pair<Double, Double>>,
    polygonMode: Boolean,
    initialCamera: LatLng?,
    recenterTo: LatLng?,
    onRecentered: () -> Unit,
    onPointSelected: (Double, Double) -> Unit,
    onVertexMoved: (index: Int, lat: Double, lng: Double) -> Unit,
    onVertexDragStart: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var map by remember { mutableStateOf<LocaMapController?>(null) }
    // A tap this close (in screen pixels) to an existing vertex is treated as hitting that
    // vertex rather than adding a nearly identical point on top of it.
    val vertexTouchRadiusPx = with(LocalDensity.current) { VERTEX_TOUCH_RADIUS_DP.dp.toPx() }
    val addPointIfNew: (LatLng) -> Unit = { point ->
        if (!polygonMode || !isNearExistingVertex(map, polygon, point, vertexTouchRadiusPx)) {
            onPointSelected(point.latitude, point.longitude)
        }
    }

    // Vertex dragging is handled at the MapView touch level instead of with a Compose overlay:
    // returning false lets MapLibre handle taps, pans and pinch exactly as before, and true is
    // returned only for a gesture that began on a vertex (so that drag moves the point instead
    // of the map). The updated-state holders keep the listener - which LocaMap creates once with
    // the first composition's lambda - reading current values.
    val currentPolygon by rememberUpdatedState(polygon)
    val currentPolygonMode by rememberUpdatedState(polygonMode)
    val currentTouchRadiusPx by rememberUpdatedState(vertexTouchRadiusPx)
    val currentOnVertexMoved by rememberUpdatedState(onVertexMoved)
    val currentOnVertexDragStart by rememberUpdatedState(onVertexDragStart)
    val draggedVertex = remember { intArrayOf(-1) }

    LaunchedEffect(recenterTo, map) {
        val mv = map ?: return@LaunchedEffect
        val target = recenterTo ?: return@LaunchedEffect
        mv.animateTo(target.latitude, target.longitude, MapGeometry.fromLegacyZoom(15.0))
        onRecentered()
    }

    val strokeColor = GeofenceBoth
    val fillArgb = android.graphics.Color.argb(40,
        (strokeColor.red * 255).toInt(),
        (strokeColor.green * 255).toInt(),
        (strokeColor.blue * 255).toInt())
    val strokeArgb = android.graphics.Color.argb(220,
        (strokeColor.red * 255).toInt(),
        (strokeColor.green * 255).toInt(),
        (strokeColor.blue * 255).toInt())
    // Themed pin, tinted to match the circle outline.
    val pin = remember(strokeArgb) {
        ContextCompat.getDrawable(context, R.drawable.ic_notif_location)?.let {
            val wrapped = DrawableCompat.wrap(it).mutate()
            DrawableCompat.setTint(wrapped, strokeArgb)
            wrapped.toBitmap()
        }
    }

    val centreLat = lat
    val centreLng = lng
    // A small dot per polygon vertex, tinted like the outline. MapLibre sizes images by
    // density, and LocaMap assigns the screen density when it registers them.
    val vertexIcon = remember(strokeArgb) {
        val sizePx = 14
        createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = strokeArgb
                style = Paint.Style.FILL
            }
            canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f - 3f, fill)
            val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 2f
            }
            canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f - 3f, outline)
        }
    }
    val showPolygon = polygonMode && polygon.size >= 3
    // Highlight the edges that make the ring invalid, so the problem is visible while
    // drawing rather than only in the error text under the form.
    val crossingEdges = remember(polygon) { GeoMath.crossingEdgeIndices(polygon) }
    val crossingLines = if (crossingEdges.isEmpty()) emptyList() else crossingEdges.map { index ->
        val a = polygon[index]
        val b = polygon[(index + 1) % polygon.size]
        MapLine(
            points = listOf(LatLng(a.first, a.second), LatLng(b.first, b.second)),
            argb = 0xFFFF3B30.toInt(),
            widthDp = 3f
        )
    }
    LocaMap(
        modifier = modifier,
        lines = crossingLines,
        circles = if (!polygonMode && centreLat != null && centreLng != null) listOf(
            MapCircle(centreLat, centreLng, radiusMetres.toDouble(), fillArgb = fillArgb, strokeArgb = strokeArgb, strokeWidthDp = 2f)
        ) else emptyList(),
        polygons = if (showPolygon) listOf(
            MapPolygon(polygon, fillArgb = fillArgb, strokeArgb = strokeArgb, strokeWidthDp = 2f)
        ) else emptyList(),
        markers = when {
            polygonMode -> polygon.mapIndexed { index, point ->
                MapMarker(
                    id = "vertex-$index",
                    lat = point.first,
                    lng = point.second,
                    icon = vertexIcon,
                    anchor = MarkerAnchor.CENTER,
                    clickable = false,
                    zIndex = 3f
                )
            }
            centreLat != null && centreLng != null && pin != null -> listOf(
                MapMarker(id = "centre", lat = centreLat, lng = centreLng, icon = pin, clickable = false)
            )
            else -> emptyList()
        },
        onMapClick = { p -> addPointIfNew(p); true },
        onMapLongClick = { p -> addPointIfNew(p); true },
        configureView = { view ->
            view.setOnTouchListener { _, event ->
                val controller = map
                if (!currentPolygonMode || controller == null) {
                    draggedVertex[0] = -1
                    return@setOnTouchListener false
                }
                val vertices = currentPolygon
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        draggedVertex[0] = nearestVertexIndex(
                            controller, vertices, event.x, event.y, currentTouchRadiusPx
                        ) ?: -1
                        if (draggedVertex[0] >= 0) currentOnVertexDragStart()
                        draggedVertex[0] >= 0
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val index = draggedVertex[0]
                        if (index !in vertices.indices) {
                            false
                        } else {
                            val point = controller.map.projection
                                .fromScreenLocation(PointF(event.x, event.y))
                            currentOnVertexMoved(index, point.latitude, point.longitude)
                            true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val wasDragging = draggedVertex[0] >= 0
                        draggedVertex[0] = -1
                        wasDragging
                    }
                    // Pointer add/remove mid-drag must not tilt the map under the finger.
                    else -> draggedVertex[0] >= 0
                }
            }
        },
        onReady = { controller ->
            map = controller
            // Frame the whole area rather than a fixed zoom, so a large radius/polygon
            // isn't cut off.
            val fitRing = when {
                polygon.isNotEmpty() -> polygon
                initialCamera != null -> MapGeometry.circle(initialCamera.latitude, initialCamera.longitude, radiusMetres.toDouble())
                else -> emptyList()
            }
            if (fitRing.isNotEmpty()) {
                controller.fit(
                    fitRing.map { (la, ln) -> LatLng(la, ln) },
                    MAP_FIT_PADDING_PX,
                    animate = false,
                    singlePointZoom = MapGeometry.fromLegacyZoom(15.0)
                )
            } else {
                controller.moveTo(0.0, 0.0, MapGeometry.fromLegacyZoom(4.0))
            }
        }
    )
}
