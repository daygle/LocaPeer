package com.locapeer.sharing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.locapeer.data.entity.PrecisionMode
import com.locapeer.data.entity.scheduleRules
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerSharingScreen(
    peerId: String,
    peerName: String,
    onNavigateBack: () -> Unit,
    onNavigateToSchedule: () -> Unit = {},
    vm: PeerSharingViewModel = hiltViewModel()
) {
    LaunchedEffect(peerId) { vm.init(peerId) }

    val state by vm.uiState.collectAsState()
    val cfg = state.config
    val sharingEnabled = cfg?.sharingEnabled ?: true
    val messagingEnabled = cfg?.messagingEnabled ?: true
    val precisionMode = cfg?.precisionMode ?: PrecisionMode.EXACT.name
    val isSosContact = cfg?.isSosContact ?: false
    val scheduleRules = cfg?.scheduleRules() ?: emptyList()
    val retentionDaysLocation = cfg?.retentionDaysLocation ?: 30
    val retentionDaysMessages = cfg?.retentionDaysMessages ?: 0
    val proximityAlert = state.proximityAlert
    val purgeResult by vm.lastPurgeResult.collectAsState()

    var showPrecisionDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Contact Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // ── Peer Header ──────────────────────────────────────────────────
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            peerName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Text(
                        peerName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            item { SectionLabel("Location Sharing") }
            item {
                SettingsCard {
                    ListItem(
                        headlineContent = { Text("Share with $peerName") },
                        supportingContent = { Text("Let this contact track your location") },
                        leadingContent = { Icon(Icons.Default.LocationOn, contentDescription = null, tint = if (sharingEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                        trailingContent = {
                            Switch(checked = sharingEnabled, onCheckedChange = { vm.setSharingEnabled(it) })
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    ListItem(
                        headlineContent = { Text("Precision") },
                        supportingContent = { Text(if (precisionMode == PrecisionMode.EXACT.name) "Exact GPS" else "Suburb (~1km)") },
                        leadingContent = { Icon(if (precisionMode == PrecisionMode.EXACT.name) Icons.Default.GpsFixed else Icons.Default.LocationCity, contentDescription = null) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                        modifier = Modifier.clickable(enabled = sharingEnabled) { showPrecisionDialog = true },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    ListItem(
                        headlineContent = { Text("Sharing schedule") },
                        supportingContent = {
                            Text(if (scheduleRules.isEmpty()) "Always share"
                                 else "${scheduleRules.size} rule${if (scheduleRules.size == 1) "" else "s"} active")
                        },
                        leadingContent = { Icon(Icons.Default.Schedule, contentDescription = null) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                        modifier = Modifier.clickable(enabled = sharingEnabled) { onNavigateToSchedule() },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }

            item { SectionLabel("Messaging") }
            item {
                SettingsCard {
                    ListItem(
                        headlineContent = { Text("Allow messages") },
                        supportingContent = { Text("Receive chat messages from this contact") },
                        leadingContent = { Icon(Icons.Default.Chat, contentDescription = null, tint = if (messagingEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                        trailingContent = {
                            Switch(checked = messagingEnabled, onCheckedChange = { vm.setMessagingEnabled(it) })
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }

            item { SectionLabel("Alerts & Proximity") }
            item {
                SettingsCard {
                    ListItem(
                        headlineContent = { Text("SOS Contact") },
                        supportingContent = { Text("Receives high-priority alerts if you activate SOS") },
                        leadingContent = { Icon(Icons.Default.Warning, contentDescription = null, tint = if (isSosContact) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) },
                        trailingContent = {
                            Switch(checked = isSosContact, onCheckedChange = { vm.setSosContact(it) })
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))

                    val alertActive = proximityAlert?.active ?: false
                    ListItem(
                        headlineContent = { Text("Proximity alert") },
                        supportingContent = { Text("Notify me when $peerName is nearby") },
                        leadingContent = { Icon(Icons.Default.NearMe, contentDescription = null, tint = if (alertActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                        trailingContent = {
                            Switch(checked = alertActive, onCheckedChange = { vm.setProximityAlertEnabled(it) })
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )

                    if (alertActive) {
                        val radius = proximityAlert?.radiusMetres ?: 500
                        Column(modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 12.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Alert radius", style = MaterialTheme.typography.bodySmall)
                                Text("${radius}m", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                            }
                            Slider(
                                value = radius.toFloat(),
                                onValueChange = { vm.setProximityAlertRadius(it.roundToInt()) },
                                valueRange = 100f..5000f,
                                steps = 48
                            )
                        }
                    }
                }
            }

            item { SectionLabel("Retention on ${peerName}'s device") }
            item {
                SettingsCard {
                    purgeResult?.let { msg ->
                        Text(
                            msg,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (msg.contains("not", ignoreCase = true) ||
                                msg.contains("Forever", ignoreCase = true) ||
                                msg.contains("not found", ignoreCase = true))
                                MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.primary
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                    RetentionRow(
                        icon = Icons.Default.LocationOff,
                        title = "Location data",
                        subtitle = "How long $peerName keeps your location data on their device",
                        selected = retentionDaysLocation,
                        onSelected = { vm.setRetentionDaysLocation(it) },
                        purgeLabel = "Ask $peerName to purge now",
                        onPurge = if (retentionDaysLocation > 0) ({ vm.sendLocationPurgeNow() }) else null
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    RetentionRow(
                        icon = Icons.Default.DeleteSweep,
                        title = "Messages",
                        subtitle = if (retentionDaysMessages == 0)
                            "Forever — change below to enable a limit"
                        else "How long $peerName keeps messages you sent",
                        selected = retentionDaysMessages,
                        onSelected = { vm.setRetentionDaysMessages(it) },
                        purgeLabel = "Ask $peerName to purge now",
                        onPurge = if (retentionDaysMessages > 0) ({ vm.sendMessagePurgeNow() }) else null
                    )
                }
            }
        }
    }

    if (showPrecisionDialog) {
        AlertDialog(
            onDismissRequest = { showPrecisionDialog = false },
            title = { Text("Location Precision") },
            text = {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { vm.setPrecisionMode(PrecisionMode.EXACT); showPrecisionDialog = false }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = precisionMode == PrecisionMode.EXACT.name, onClick = { vm.setPrecisionMode(PrecisionMode.EXACT); showPrecisionDialog = false })
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Exact GPS", style = MaterialTheme.typography.bodyLarge)
                            Text("Precise coordinates", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { vm.setPrecisionMode(PrecisionMode.SUBURB); showPrecisionDialog = false }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = precisionMode == PrecisionMode.SUBURB.name, onClick = { vm.setPrecisionMode(PrecisionMode.SUBURB); showPrecisionDialog = false })
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Suburb (~1km)", style = MaterialTheme.typography.bodyLarge)
                            Text("Neighbourhood-level only", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showPrecisionDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(content = content)
    }
}
