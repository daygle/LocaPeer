package com.locapeer.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.locapeer.data.entity.PeerEntity
import com.locapeer.settings.BackupSection
import com.locapeer.supervised.SupervisedModeManager
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToGeofences: () -> Unit,
    onNavigateToProximityAlerts: () -> Unit = {},
    onNavigateToPeerSharing: (peerId: String, peerName: String) -> Unit = { _, _ -> },
    onNavigateToHistoryReport: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    onNavigateToCustomizeNav: () -> Unit = {},
    onNavigateToGlobalSchedule: () -> Unit = {},
    vm: SettingsViewModel = hiltViewModel(),
) {
    val settings by vm.settings.collectAsState()
    val peers by vm.peers.collectAsState()
    val publicKeyHex by vm.publicKeyHex.collectAsState()
    val profileQr by vm.profileQr.collectAsState()

    val unlockState by vm.unlockState.collectAsState()
    var sessionUnlocked by remember { mutableStateOf(false) }
    if (settings.supervisedModeEnabled && !sessionUnlocked) {
        SupervisedRemoteGate(
            unlockState = unlockState,
            onRequestAccess = { vm.requestSettingsUnlock() },
            onReset = { vm.resetUnlockState() },
        ) { sessionUnlocked = true }
        return
    }

    var showNameDialog by remember { mutableStateOf(false) }
    var nameInput by remember(settings.displayName) { mutableStateOf(settings.displayName) }
    var showKeyDialog by remember { mutableStateOf(false) }
    var exportedKey by remember { mutableStateOf("") }
    var showProfileQr by remember { mutableStateOf(false) }
    var showClearLocationConfirm by remember { mutableStateOf(false) }
    var showClearMessageConfirm by remember { mutableStateOf(false) }
    var showSupervisedSetup by remember { mutableStateOf(false) }
    var showDisableSupervisedConfirm by remember { mutableStateOf(false) }
    var intervalsExpanded by remember { mutableStateOf(false) }
    var showStartPageDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var exportSections by remember { mutableStateOf(setOf(BackupSection.PRIVATE_KEY, BackupSection.CONTACTS, BackupSection.GEOFENCES, BackupSection.SETTINGS)) }
    val exportLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { vm.exportBackup(it, exportSections) } }
    val backupResult by vm.backupResult.collectAsState()
    val pendingRestore by vm.pendingRestore.collectAsState()
    val importLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.loadBackupForRestore(it) } }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {

            // ── Profile ──────────────────────────────────────────────────────
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 20.dp),
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
                            settings.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            settings.displayName.ifBlank { "No name set" },
                            style = MaterialTheme.typography.titleLarge,
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { nameInput = settings.displayName; showNameDialog = true }) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Edit Name")
                        }
                        OutlinedButton(onClick = { showProfileQr = true }) {
                            Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("My QR")
                        }
                    }
                }
            }

            item { SectionLabel("Location Sharing") }

            // Share my location toggle
            item {
                SettingsCard {
                    ListItem(
                        headlineContent = { Text("Share my location") },
                        supportingContent = { Text(if (settings.heartbeatEnabled) "Broadcasting to your contacts" else "Not broadcasting") },
                        leadingContent = { Icon(Icons.Default.LocationOn, contentDescription = null, tint = if (settings.heartbeatEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                        trailingContent = {
                            Switch(
                                checked = settings.heartbeatEnabled,
                                onCheckedChange = { vm.setHeartbeatEnabled(it) }
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    // Update intervals — expandable
                    ListItem(
                        headlineContent = { Text("Update intervals") },
                        supportingContent = { Text("Stationary: ${settings.stationaryIntervalMinutes}min · Walking: ${settings.walkingIntervalMinutes}min") },
                        leadingContent = { Icon(Icons.Default.Timer, contentDescription = null) },
                        trailingContent = {
                            Icon(
                                if (intervalsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null
                            )
                        },
                        modifier = Modifier.clickable { intervalsExpanded = !intervalsExpanded },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    if (intervalsExpanded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 56.dp, end = 16.dp, bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            IntervalSlider("Stationary", settings.stationaryIntervalMinutes, 5f..60f, 10) { vm.updateIntervals(stationary = it) }
                            IntervalSlider("Walking",    settings.walkingIntervalMinutes,    1f..15f, 13) { vm.updateIntervals(walking = it) }
                            IntervalSlider("Running",    settings.runningIntervalMinutes,    1f..10f,  8) { vm.updateIntervals(running = it) }
                            IntervalSlider("Cycling",    settings.cyclingIntervalMinutes,    1f..10f,  8) { vm.updateIntervals(cycling = it) }
                            IntervalSlider("Driving",    settings.drivingIntervalMinutes,    1f..10f,  8) { vm.updateIntervals(driving = it) }
                            IntervalSlider("Low Battery (< 20%)", settings.lowBatteryIntervalMinutes, 15f..120f, 6) { vm.updateIntervals(lowBattery = it) }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    NavRow(
                        icon = Icons.Default.Schedule,
                        label = "Sharing schedule",
                        subtitle = if (settings.globalScheduleRules.isEmpty()) "Always on"
                                   else "${settings.globalScheduleRules.size} rule${if (settings.globalScheduleRules.size == 1) "" else "s"}",
                        onClick = onNavigateToGlobalSchedule
                    )
                }
            }

            item { SectionLabel("Alerts") }

            item {
                SettingsCard {
                    NavRow(
                        icon = Icons.Default.Fence,
                        label = "Geofences",
                        subtitle = "Notify when contacts enter/leave areas",
                        onClick = onNavigateToGeofences
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    NavRow(
                        icon = Icons.Default.NearMe,
                        label = "Proximity Alerts",
                        subtitle = "Notify when contacts are nearby",
                        onClick = onNavigateToProximityAlerts
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    NavRow(
                        icon = Icons.Default.History,
                        label = "Location History",
                        subtitle = "View and export movement history",
                        onClick = onNavigateToHistoryReport
                    )
                }
            }

            item { SectionLabel("Privacy & Data") }

            // Card 1: Data on peers' devices (remote purge)
            item {
                SettingsCard {
                    RetentionRow(
                        icon = Icons.Default.LocationOff,
                        title = "Location on peers' devices",
                        subtitle = "How long contacts keep your location data",
                        selected = settings.retentionDays,
                        onSelected = { vm.setRetentionDays(it) },
                        purgeLabel = "Delete from peers now",
                        onPurge = if (settings.retentionDays > 0) ({ vm.sendPurgeNow() }) else null
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    RetentionRow(
                        icon = Icons.Default.DeleteSweep,
                        title = "Messages on peers' devices",
                        subtitle = "How long contacts keep messages you sent",
                        selected = settings.messageRetentionDays,
                        onSelected = { vm.setMessageRetentionDays(it) },
                        purgeLabel = "Delete from peers now",
                        onPurge = if (settings.messageRetentionDays > 0) ({ vm.sendMessagePurgeNow() }) else null
                    )
                }
            }

            // Card 2: Data on this device (local retention + manual clear)
            item {
                SettingsCard {
                    RetentionRow(
                        icon = Icons.Default.LocationOn,
                        title = "Location on this device",
                        subtitle = "How long to keep contacts' location data locally",
                        selected = settings.localLocationRetentionDays,
                        onSelected = { vm.setLocalLocationRetentionDays(it) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    RetentionRow(
                        icon = Icons.Default.Message,
                        title = "Messages on this device",
                        subtitle = "How long to keep received messages locally",
                        selected = settings.localMessageRetentionDays,
                        onSelected = { vm.setLocalMessageRetentionDays(it) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showClearLocationConfirm = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) { Text("Clear locations", style = MaterialTheme.typography.labelMedium) }
                        OutlinedButton(
                            onClick = { showClearMessageConfirm = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) { Text("Clear messages", style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }

            item { SectionLabel("Keys & Backup") }

            // Card: Backup & keys
            item {
                SettingsCard {
                    backupResult?.let { msg ->
                        Text(
                            msg,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (msg.startsWith("Backup failed") || msg.startsWith("Restore failed") || msg.startsWith("Could not"))
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                    ListItem(
                        headlineContent = { Text("Export backup") },
                        supportingContent = { Text("Save key, contacts, geofences & settings") },
                        leadingContent = { Icon(Icons.Default.Upload, contentDescription = null) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                        modifier = Modifier.clickable { showExportDialog = true; vm.clearBackupResult() },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    ListItem(
                        headlineContent = { Text("Import backup") },
                        supportingContent = { Text("Restore from a previously exported file") },
                        leadingContent = { Icon(Icons.Default.Download, contentDescription = null) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                        modifier = Modifier.clickable { importLauncher.launch(arrayOf("application/json", "*/*")); vm.clearBackupResult() },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    ListItem(
                        headlineContent = { Text("View private key") },
                        supportingContent = { Text("Show your 64-character hex identity key") },
                        leadingContent = { Icon(Icons.Default.VpnKey, contentDescription = null) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                        modifier = Modifier.clickable { vm.exportPrivateKey { key -> exportedKey = key; showKeyDialog = true } },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }

            item { SectionLabel("Security") }

            item {
                SettingsCard {
                    if (settings.supervisedModeEnabled) {
                        ListItem(
                            headlineContent = { Text("Supervision active") },
                            supportingContent = { Text("Settings require supervisor approval") },
                            leadingContent = { Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                        ListItem(
                            headlineContent = { Text("Disable Supervised Mode", color = MaterialTheme.colorScheme.error) },
                            leadingContent = { Icon(Icons.Default.LockOpen, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            modifier = Modifier.clickable { showDisableSupervisedConfirm = true },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    } else {
                        ListItem(
                            headlineContent = { Text("Supervised Mode") },
                            supportingContent = { Text("Require supervisor approval to access settings") },
                            leadingContent = { Icon(Icons.Default.Lock, contentDescription = null) },
                            trailingContent = {
                                Switch(checked = false, onCheckedChange = { if (it) showSupervisedSetup = true })
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            }

            item { SectionLabel("Appearance") }

            item {
                SettingsCard {
                    NavRow(
                        icon = Icons.Default.GridView,
                        label = "Customize Navigation",
                        subtitle = "Choose and reorder bottom tabs",
                        onClick = onNavigateToCustomizeNav
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    NavRow(
                        icon = Icons.Default.Home,
                        label = "Start Page",
                        subtitle = settings.navTabIds
                            .firstOrNull { it == settings.startRoute }
                            ?.replaceFirstChar { it.uppercaseChar() }
                            ?: "Map",
                        onClick = { showStartPageDialog = true }
                    )
                }
            }


            item { SectionLabel("About") }

            item {
                SettingsCard {
                    NavRow(
                        icon = Icons.Default.Info,
                        label = "About LocaPeer",
                        subtitle = "Version, relay status, open source",
                        onClick = onNavigateToAbout
                    )
                }
            }
        }
    }

    // ─── Dialogs ─────────────────────────────────────────────────────────────

    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text("Edit Display Name") },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Display Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.updateDisplayName(nameInput); showNameDialog = false },
                    enabled = nameInput.isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showNameDialog = false }) { Text("Cancel") } }
        )
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
                        Image(bitmap = bmp.asImageBitmap(), contentDescription = "Invite QR", modifier = Modifier.size(220.dp))
                    } ?: CircularProgressIndicator()
                }
            },
            confirmButton = { TextButton(onClick = { showProfileQr = false }) { Text("Done") } }
        )
    }

    if (showKeyDialog) {
        AlertDialog(
            onDismissRequest = { showKeyDialog = false },
            title = { Text("Private Key") },
            text = {
                Column {
                    Text("Keep this safe. Anyone with this key can impersonate you.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer { Text(exportedKey, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(onClick = { showKeyDialog = false }) { Text("Done") } }
        )
    }

    if (showClearLocationConfirm) {
        AlertDialog(
            onDismissRequest = { showClearLocationConfirm = false },
            title = { Text("Clear Location History?") },
            text = { Text("All stored location pings will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = { vm.clearLocationHistory(); showClearLocationConfirm = false }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showClearLocationConfirm = false }) { Text("Cancel") } }
        )
    }

    if (showClearMessageConfirm) {
        AlertDialog(
            onDismissRequest = { showClearMessageConfirm = false },
            title = { Text("Clear Message History?") },
            text = { Text("All stored messages will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = { vm.clearMessageHistory(); showClearMessageConfirm = false }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showClearMessageConfirm = false }) { Text("Cancel") } }
        )
    }

    pendingRestore?.let { restore ->
        var importSections by remember(restore) { mutableStateOf(restore.availableSections) }
        AlertDialog(
            onDismissRequest = { vm.dismissPendingRestore() },
            title = { Text("Select data to restore") },
            text = {
                Column {
                    BackupSectionItem("Private Key", BackupSection.PRIVATE_KEY, importSections, restore.availableSections) { importSections = it }
                    BackupSectionItem("Contacts", BackupSection.CONTACTS, importSections, restore.availableSections) { importSections = it }
                    BackupSectionItem("Geofences", BackupSection.GEOFENCES, importSections, restore.availableSections) { importSections = it }
                    BackupSectionItem("Settings", BackupSection.SETTINGS, importSections, restore.availableSections) { importSections = it }
                }
            },
            confirmButton = {
                Button(onClick = { vm.applyRestore(importSections) }, enabled = importSections.isNotEmpty()) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissPendingRestore() }) { Text("Cancel") }
            }
        )
    }

    if (showSupervisedSetup) {
        SupervisedModeSetupDialog(
            peers = peers,
            onConfirm = { supervisorPubkey -> vm.enableSupervisedMode(supervisorPubkey); showSupervisedSetup = false },
            onDismiss = { showSupervisedSetup = false }
        )
    }

    if (showDisableSupervisedConfirm) {
        AlertDialog(
            onDismissRequest = { showDisableSupervisedConfirm = false },
            title = { Text("Disable Supervised Mode?") },
            text = { Text("Settings will be accessible without supervisor approval.") },
            confirmButton = {
                TextButton(onClick = { vm.disableSupervisedMode(); showDisableSupervisedConfirm = false }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Disable") }
            },
            dismissButton = { TextButton(onClick = { showDisableSupervisedConfirm = false }) { Text("Cancel") } }
        )
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("Select data to export") },
            text = {
                Column {
                    BackupSectionItem("Private Key", BackupSection.PRIVATE_KEY, exportSections, BackupSection.entries.toSet()) { exportSections = it }
                    BackupSectionItem("Contacts", BackupSection.CONTACTS, exportSections, BackupSection.entries.toSet()) { exportSections = it }
                    BackupSectionItem("Geofences", BackupSection.GEOFENCES, exportSections, BackupSection.entries.toSet()) { exportSections = it }
                    BackupSectionItem("Settings", BackupSection.SETTINGS, exportSections, BackupSection.entries.toSet()) { exportSections = it }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExportDialog = false
                        exportLauncher.launch("locapeer-backup.json")
                    },
                    enabled = exportSections.isNotEmpty()
                ) { Text("Choose file location") }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showStartPageDialog) {
        val tabLabels = mapOf(
            "map" to "Map",
            "messages" to "Messages",
            "contacts" to "Contacts",
            "invite" to "QR",
            "settings" to "Settings"
        )
        AlertDialog(
            onDismissRequest = { showStartPageDialog = false },
            title = { Text("Start Page") },
            text = {
                Column {
                    settings.navTabIds.forEach { route ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.setStartRoute(route)
                                    showStartPageDialog = false
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.startRoute == route,
                                onClick = {
                                    vm.setStartRoute(route)
                                    showStartPageDialog = false
                                }
                            )
                            Text(
                                tabLabels[route] ?: route.replaceFirstChar { it.uppercaseChar() },
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showStartPageDialog = false }) { Text("Cancel") }
            }
        )
    }
}

// ─── Shared composables ──────────────────────────────────────────────────────

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

@Composable
private fun NavRow(icon: ImageVector, label: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

private val RETENTION_OPTIONS = listOf(
    0 to "Forever",
    1 to "1 day",
    3 to "3 days",
    7 to "7 days",
    14 to "14 days",
    30 to "30 days",
    90 to "90 days"
)

@Composable
private fun RetentionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    selected: Int,
    onSelected: (Int) -> Unit,
    purgeLabel: String? = null,
    onPurge: (() -> Unit)? = null
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(10.dp))
        RetentionSelector(selected = selected, onSelected = onSelected)
        if (onPurge != null && purgeLabel != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onPurge, modifier = Modifier.fillMaxWidth()) {
                Text(purgeLabel)
            }
        }
    }
}

@Composable
private fun RetentionSelector(selected: Int, onSelected: (Int) -> Unit) {
    val rows = RETENTION_OPTIONS.chunked(4)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { rowItems ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowItems.forEach { (days, label) ->
                    FilterChip(
                        selected = selected == days,
                        onClick = { onSelected(days) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(4 - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun IntervalSlider(label: String, value: Int, range: ClosedFloatingPointRange<Float>, steps: Int, onChanged: (Int) -> Unit) {
    var sliderValue by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text("${sliderValue.roundToInt()} min", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
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
private fun SelectionContainer(content: @Composable () -> Unit) {
    androidx.compose.foundation.text.selection.SelectionContainer { content() }
}

// ─── Supervised Mode UI ───────────────────────────────────────────────────────

@Composable
private fun SupervisedRemoteGate(
    unlockState: SupervisedModeManager.UnlockState,
    onRequestAccess: () -> Unit,
    onReset: () -> Unit,
    onUnlocked: () -> Unit
) {
    LaunchedEffect(unlockState) {
        if (unlockState is SupervisedModeManager.UnlockState.Approved) onUnlocked()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Device is Supervised", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("Supervisor approval is required to access settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))

        when (unlockState) {
            is SupervisedModeManager.UnlockState.Idle -> {
                Button(onClick = onRequestAccess, modifier = Modifier.fillMaxWidth()) { Text("Request Access") }
            }
            is SupervisedModeManager.UnlockState.Requesting -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Waiting for supervisor approval…", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onReset) { Text("Cancel") }
            }
            is SupervisedModeManager.UnlockState.Denied -> {
                Text("Access denied by supervisor.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onReset, modifier = Modifier.fillMaxWidth()) { Text("Try Again") }
            }
            is SupervisedModeManager.UnlockState.TimedOut -> {
                Text("Request timed out. Supervisor did not respond.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onReset, modifier = Modifier.fillMaxWidth()) { Text("Try Again") }
            }
            is SupervisedModeManager.UnlockState.Approved -> CircularProgressIndicator()
        }
    }
}

@Composable
private fun SupervisedModeSetupDialog(peers: List<PeerEntity>, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var selectedPubkey by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enable Supervised Mode") },
        text = {
            Column {
                Text(
                    "Choose a peer who will act as supervisor. They must approve access to settings from their device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (peers.isEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text("No peers found. Add a peer first by scanning their invite QR code.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                } else {
                    Spacer(Modifier.height(12.dp))
                    peers.forEach { peer ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            RadioButton(selected = selectedPubkey == peer.publicKeyHex, onClick = { selectedPubkey = peer.publicKeyHex })
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(peer.displayName, style = MaterialTheme.typography.bodyMedium)
                                Text(peer.publicKeyHex.take(16) + "…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selectedPubkey) }, enabled = selectedPubkey.isNotEmpty()) { Text("Enable") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun BackupSectionItem(
    label: String,
    section: BackupSection,
    selected: Set<BackupSection>,
    available: Set<BackupSection>,
    onToggle: (Set<BackupSection>) -> Unit
) {
    val enabled = section in available
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) {
                onToggle(if (section in selected) selected - section else selected + section)
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = section in selected,
            onCheckedChange = { checked ->
                onToggle(if (checked) selected + section else selected - section)
            },
            enabled = enabled
        )
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
            if (!enabled) {
                Text(
                    "Not in this backup",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
