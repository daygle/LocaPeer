package com.locapeer.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToGeofences: () -> Unit,
    vm: SettingsViewModel = hiltViewModel()
) {
    val settings by vm.settings.collectAsState()
    val peers by vm.peers.collectAsState()

    var nameInput by remember(settings.displayName) { mutableStateOf(settings.displayName) }
    var relayInput by remember(settings.relayUrl) { mutableStateOf(settings.relayUrl) }
    var showKeyDialog by remember { mutableStateOf(false) }
    var exportedKey by remember { mutableStateOf("") }

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
                            "No peers yet. Use the Invite tab to add people.",
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
                        onClick = { vm.clearLocationHistory() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Clear Location History") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { vm.clearMessageHistory() },
                        modifier = Modifier.fillMaxWidth()
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
