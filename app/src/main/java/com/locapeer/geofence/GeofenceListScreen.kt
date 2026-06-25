package com.locapeer.geofence

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.locapeer.data.entity.GeofenceEntity
import com.locapeer.ui.theme.GeofenceBoth
import com.locapeer.ui.theme.GeofenceEnter
import com.locapeer.ui.theme.GeofenceExit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeofenceListScreen(
    onNavigateBack: () -> Unit,
    vm: GeofenceViewModel = hiltViewModel()
) {
    val geofences by vm.geofences.collectAsState()
    val broadcasters by vm.broadcasters.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Geofences") })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add geofence")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            items(geofences, key = { it.id }) { fence ->
                GeofenceCard(
                    fence = fence,
                    trackedName = broadcasters.firstOrNull { it.deviceId == fence.trackedDeviceId }?.displayName ?: "Unknown",
                    onToggle = { vm.setActive(fence.id, it) },
                    onDelete = { vm.delete(fence) }
                )
            }
            if (geofences.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillParentMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No geofences yet.\nTap + to create one.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog && broadcasters.isNotEmpty()) {
        CreateGeofenceDialog(
            broadcasters = broadcasters,
            onDismiss = { showCreateDialog = false },
            onCreate = { name, lat, lng, radius, deviceId, trigger ->
                vm.createGeofence(name, lat, lng, radius, deviceId, trigger)
                showCreateDialog = false
            }
        )
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
                    "Tracking: $trackedName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "${fence.radiusMetres}m • ${fence.triggerOn}",
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

@Composable
private fun CreateGeofenceDialog(
    broadcasters: List<com.locapeer.data.entity.PeerEntity>,
    onDismiss: () -> Unit,
    onCreate: (String, Double, Double, Int, String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var latText by remember { mutableStateOf("") }
    var lngText by remember { mutableStateOf("") }
    var radius by remember { mutableFloatStateOf(500f) }
    var selectedPeer by remember { mutableStateOf(broadcasters.first()) }
    var triggerOn by remember { mutableStateOf("ENTER") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Geofence") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                OutlinedTextField(value = latText, onValueChange = { latText = it }, label = { Text("Latitude") })
                OutlinedTextField(value = lngText, onValueChange = { lngText = it }, label = { Text("Longitude") })
                Text("Radius: ${radius.toInt()}m")
                Slider(
                    value = radius,
                    onValueChange = { radius = it },
                    valueRange = 100f..50000f,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Tracked person", style = MaterialTheme.typography.labelSmall)
                broadcasters.forEach { peer ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = selectedPeer.deviceId == peer.deviceId,
                            onClick = { selectedPeer = peer }
                        )
                        Text(peer.displayName)
                    }
                }
                Text("Trigger on", style = MaterialTheme.typography.labelSmall)
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
                    val lat = latText.toDoubleOrNull() ?: return@TextButton
                    val lng = lngText.toDoubleOrNull() ?: return@TextButton
                    onCreate(name, lat, lng, radius.toInt(), selectedPeer.deviceId, triggerOn)
                }
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
