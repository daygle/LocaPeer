package com.locapeer.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToGeofences: () -> Unit,
    vm: SettingsViewModel = hiltViewModel()
) {
    val settings by vm.settings.collectAsState()
    val peers by vm.peers.collectAsState()
    val publicKeyHex by vm.publicKeyHex.collectAsState()
    val profileQr by vm.profileQr.collectAsState()

    var nameInput by remember(settings.displayName) { mutableStateOf(settings.displayName) }
    var relayInput by remember(settings.relayUrl) { mutableStateOf(settings.relayUrl) }
    var showKeyDialog by remember { mutableStateOf(false) }
    var exportedKey by remember { mutableStateOf("") }
    var showProfileQr by remember { mutableStateOf(false) }
    var showClearLocationConfirm by remember { mutableStateOf(false) }
    var showClearMessageConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // Profile card
            item {
                SettingsSection("My Profile") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                settings.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                settings.displayName.ifBlank { "No name set" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (publicKeyHex.isNotEmpty()) {
                                Text(
                                    publicKeyHex.take(16) + "…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(onClick = { showProfileQr = true }) {
                            Icon(Icons.Default.QrCode, contentDescription = "Show invite QR")
                        }
                    }
                }
            }

            item {
                SettingsSection("Identity") {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Display Name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = { vm.updateDisplayName(nameInput) },
                        modifier = Modifier.align(Alignment.End)
                    ) { Text("Save") }
                }
            }

            item {
                SettingsSection("Relay") {
                    OutlinedTextField(
                        value = relayInput,
                        onValueChange = { relayInput = it },
                        label = { Text("Nostr Relay URL") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = { vm.updateRelayUrl(relayInput) },
                        modifier = Modifier.align(Alignment.End)
                    ) { Text("Save") }
                }
            }

            item {
                SettingsSection("Broadcasting") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Share my location")
                        Switch(
                            checked = settings.heartbeatEnabled,
                            onCheckedChange = { vm.setHeartbeatEnabled(it) }
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(12.dp))

                    Text(
                        "Update Intervals",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))

                    IntervalSlider(
                        label = "Stationary",
                        value = settings.stationaryIntervalMinutes,
                        range = 5f..60f,
                        steps = 10,
                        onChanged = { vm.updateIntervals(stationary = it) }
                    )
                    IntervalSlider(
                        label = "Walking / Running",
                        value = settings.walkingIntervalMinutes,
                        range = 1f..15f,
                        steps = 13,
                        onChanged = { vm.updateIntervals(walking = it) }
                    )
                    IntervalSlider(
                        label = "Driving",
                        value = settings.drivingIntervalMinutes,
                        range = 1f..10f,
                        steps = 8,
                        onChanged = { vm.updateIntervals(driving = it) }
                    )
                    IntervalSlider(
                        label = "Low Battery (< 20%)",
                        value = settings.lowBatteryIntervalMinutes,
                        range = 15f..120f,
                        steps = 6,
                        onChanged = { vm.updateIntervals(lowBattery = it) }
                    )
                }
            }

            item {
                SettingsSection("Peers") {
                    val broadcasters = peers.filter { it.role == "BROADCASTER" }
                    val subscribers = peers.filter { it.role == "SUBSCRIBER" }

                    if (broadcasters.isNotEmpty()) {
                        Text("Tracked people", style = MaterialTheme.typography.labelSmall)
                        broadcasters.forEach { peer ->
                            ListItem(
                                headlineContent = { Text(peer.displayName) },
                                supportingContent = { Text(peer.publicKeyHex.take(16) + "…") },
                                trailingContent = {
                                    IconButton(onClick = { vm.removePeer(peer.deviceId) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Remove")
                                    }
                                }
                            )
                        }
                    }
                    if (subscribers.isNotEmpty()) {
                        Text("My subscribers", style = MaterialTheme.typography.labelSmall)
                        subscribers.forEach { peer ->
                            ListItem(
                                headlineContent = { Text(peer.displayName) },
                                supportingContent = { Text(peer.publicKeyHex.take(16) + "…") },
                                trailingContent = {
                                    IconButton(onClick = { vm.removePeer(peer.deviceId) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Revoke")
                                    }
                                }
                            )
                        }
                    }
                    if (peers.isEmpty()) {
                        Text(
                            "No peers yet. Use the Share tab to invite people.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                SettingsSection("Geofences") {
                    Button(onClick = onNavigateToGeofences, modifier = Modifier.fillMaxWidth()) {
                        Text("Manage Geofences")
                    }
                }
            }

            item {
                SettingsSection("Data") {
                    OutlinedButton(
                        onClick = { showClearLocationConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text("Clear Location History") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showClearMessageConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text("Clear Message History") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            vm.exportPrivateKey { key ->
                                exportedKey = key
                                showKeyDialog = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Export / Backup Keypair") }
                }
            }
        }
    }

    if (showProfileQr) {
        AlertDialog(
            onDismissRequest = { showProfileQr = false },
            title = { Text("My Invite QR") },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Others can scan this to start tracking you.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    profileQr?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Invite QR",
                            modifier = Modifier.size(220.dp)
                        )
                    } ?: CircularProgressIndicator()
                }
            },
            confirmButton = {
                TextButton(onClick = { showProfileQr = false }) { Text("Done") }
            }
        )
    }

    if (showKeyDialog) {
        AlertDialog(
            onDismissRequest = { showKeyDialog = false },
            title = { Text("Private Key") },
            text = {
                Column {
                    Text(
                        "Keep this safe. Anyone with this key can impersonate you.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer { Text(exportedKey, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(onClick = { showKeyDialog = false }) { Text("Done") }
            }
        )
    }

    if (showClearLocationConfirm) {
        AlertDialog(
            onDismissRequest = { showClearLocationConfirm = false },
            title = { Text("Clear Location History?") },
            text = { Text("All stored location pings will be permanently deleted. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.clearLocationHistory()
                        showClearLocationConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showClearLocationConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showClearMessageConfirm) {
        AlertDialog(
            onDismissRequest = { showClearMessageConfirm = false },
            title = { Text("Clear Message History?") },
            text = { Text("All stored messages will be permanently deleted. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.clearMessageHistory()
                        showClearMessageConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showClearMessageConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun IntervalSlider(
    label: String,
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChanged: (Int) -> Unit
) {
    var sliderValue by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(
                "${sliderValue.roundToInt()} min",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onChanged(sliderValue.roundToInt()) },
            valueRange = range,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun SelectionContainer(content: @Composable () -> Unit) {
    androidx.compose.foundation.text.selection.SelectionContainer { content() }
}
