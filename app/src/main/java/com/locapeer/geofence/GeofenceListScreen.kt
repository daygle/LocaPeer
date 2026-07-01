package com.locapeer.geofence

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PersonPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.android.gms.location.LocationServices
import com.locapeer.supervised.SupervisionGate
import com.locapeer.supervised.SupervisionGateViewModel
import com.locapeer.data.entity.GeofenceEntity
import com.locapeer.ui.theme.GeofenceBoth
import com.locapeer.ui.theme.GeofenceEnter
import com.locapeer.ui.theme.GeofenceExit
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
                    onDelete = { vm.delete(fence) }
                )
            }
            if (filteredGeofences.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillParentMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (peerId != null) "No geofences for this contact.\nTap + to create one."
                            else "No geofences yet.\nTap + to create one.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        val availableBroadcasters = if (peerId != null) {
            broadcastersWithLocation.filter { it.peer.deviceId == peerId }
        } else {
            broadcastersWithLocation
        }

        if (availableBroadcasters.isEmpty()) {
            AlertDialog(
                onDismissRequest = { showCreateDialog = false },
                title = { Text("No Contacts Found") },
                text = { Text(if (peerId != null) "This contact hasn't shared their location yet." else "You need at least one tracked contact before creating a geofence.") },
                confirmButton = {
                    TextButton(onClick = { showCreateDialog = false }) { Text("OK") }
                }
            )
        } else {
            CreateGeofenceDialog(
                broadcastersWithLocation = availableBroadcasters,
                onDismiss = { showCreateDialog = false },
                onCreate = { name, lat, lng, radius, deviceId, trigger ->
                    vm.createGeofence(name, lat, lng, radius, deviceId, trigger)
                    showCreateDialog = false
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
                    "${fence.radiusMetres}m • ${fence.triggerOn.lowercase().replaceFirstChar { it.uppercase() }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = triggerColor
                )
            }
            Switch(
                checked = fence.active,
                onCheckedChange = onToggle
            )
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun CreateGeofenceDialog(
    broadcastersWithLocation: List<BroadcasterWithLocation>,
    onDismiss: () -> Unit,
    onCreate: (String, Double, Double, Int, String, String) -> Unit
) {
    val context = LocalContext.current
    val fusedLocation = remember { LocationServices.getFusedLocationProviderClient(context) }

    var name by remember { mutableStateOf("") }
    var latText by remember { mutableStateOf("") }
    var lngText by remember { mutableStateOf("") }
    var radius by remember { mutableFloatStateOf(500f) }
    var selectedDeviceId by remember { mutableStateOf(broadcastersWithLocation.first().peer.deviceId) }
    val selectedEntry = broadcastersWithLocation.firstOrNull { it.peer.deviceId == selectedDeviceId }
        ?: broadcastersWithLocation.first()
    var triggerOn by remember { mutableStateOf("ENTER") }
    var submitted by remember { mutableStateOf(false) }
    var loadingMyLocation by remember { mutableStateOf(false) }

    val lat = latText.toDoubleOrNull()
    val lng = lngText.toDoubleOrNull()
    val nameError = submitted && name.isBlank()
    val latError = submitted && (lat == null || lat !in -90.0..90.0)
    val lngError = submitted && (lng == null || lng !in -180.0..180.0)
    val isValid = name.isNotBlank() && lat != null && lat in -90.0..90.0 && lng != null && lng in -180.0..180.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Geofence") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    isError = nameError,
                    supportingText = if (nameError) { { Text("Name is required") } } else null,
                    modifier = Modifier.fillMaxWidth()
                )

                // Location pre-fill helpers
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            loadingMyLocation = true
                            fusedLocation.lastLocation.addOnSuccessListener { loc ->
                                loadingMyLocation = false
                                if (loc != null) {
                                    latText = "%.6f".format(loc.latitude)
                                    lngText = "%.6f".format(loc.longitude)
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
                                latText = "%.6f".format(hb.lat)
                                lngText = "%.6f".format(hb.lng)
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

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = latText,
                        onValueChange = { latText = it },
                        label = { Text("Latitude") },
                        isError = latError,
                        supportingText = if (latError) { { Text("−90 to 90") } } else null,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = lngText,
                        onValueChange = { lngText = it },
                        label = { Text("Longitude") },
                        isError = lngError,
                        supportingText = if (lngError) { { Text("−180 to 180") } } else null,
                        modifier = Modifier.weight(1f)
                    )
                }

                Text("Radius: ${radius.roundToInt()}m")
                Slider(
                    value = radius,
                    onValueChange = { radius = it },
                    valueRange = 100f..50000f,
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
        },
        confirmButton = {
            TextButton(
                onClick = {
                    submitted = true
                    if (isValid) {
                        onCreate(name, lat!!, lng!!, radius.roundToInt(), selectedEntry.peer.deviceId, triggerOn)
                    }
                }
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
