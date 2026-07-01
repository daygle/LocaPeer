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
import androidx.compose.material.icons.automirrored.filled.Message
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.locapeer.data.entity.PeerEntity
import com.locapeer.supervised.SupervisionGate
import com.locapeer.ui.components.MapLocationPicker
import com.locapeer.ui.components.RetentionRow
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToPeerSharing: (peerId: String, peerName: String) -> Unit = { _, _ -> },
    onNavigateToAbout: () -> Unit = {},
    onNavigateToCustomizeNav: () -> Unit = {},
    onNavigateToGlobalSchedule: () -> Unit = {},
    onNavigateToMyHistory: (pubkeyHex: String) -> Unit = {},
    vm: SettingsViewModel = hiltViewModel(),
) {
    val settings by vm.settings.collectAsState()
    val peers by vm.peers.collectAsState()
    val publicKeyHex by vm.publicKeyHex.collectAsState()
    val profileQr by vm.profileQr.collectAsState()

    val unlockState by vm.unlockState.collectAsState()
    var sessionUnlocked by remember { mutableStateOf(false) }
    if (settings.supervisedModeEnabled && !sessionUnlocked) {
        SupervisionGate(
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
    var showMapStartingPointDialog by remember { mutableStateOf(false) }
    var showFixedLocationPicker by remember { mutableStateOf(false) }
    var fixedLocationCaptureMessage by remember { mutableStateOf("") }
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
                    val avatarColor = if (settings.pinColor.isNotEmpty())
                        Color(android.graphics.Color.parseColor(settings.pinColor))
                    else MaterialTheme.colorScheme.primaryContainer
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(avatarColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            settings.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
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
                    PinColorPicker(
                        selectedColor = settings.pinColor,
                        onColorSelected = vm::setPinColor
                    )
                }
            }

            item { SectionLabel("Location Sharing") }

            // Share my location toggle
            item {
                SettingsCard {
                    ListItem(
                        headlineContent = { Text("Share My Location") },
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
                    // Update intervals - expandable
                    ListItem(
                        headlineContent = { Text("Update Intervals") },
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
                        label = "Sharing Schedule",
                        subtitle = if (settings.globalScheduleRules.isEmpty()) "Always on"
                                   else "${settings.globalScheduleRules.size} rule${if (settings.globalScheduleRules.size == 1) "" else "s"}",
                        onClick = onNavigateToGlobalSchedule
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    NavRow(
                        icon = Icons.Default.History,
                        label = "My Location History",
                        subtitle = "Browse your own location timeline",
                        onClick = { if (publicKeyHex.isNotBlank()) onNavigateToMyHistory(publicKeyHex) }
                    )
                }
            }

            item { SectionLabel("Map") }

            item {
                SettingsCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            Icons.Default.ZoomIn,
                            contentDescription = null,
                            modifier = Modifier.padding(top = 2.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Default Zoom Level", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    settings.mapStartZoom.toInt().toString(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                "Used for Current Location, All Contacts and Fixed Location modes",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Slider(
                                value = settings.mapStartZoom.toFloat(),
                                onValueChange = { vm.setMapStartZoom(it.toDouble()) },
                                valueRange = 3f..18f,
                                steps = 14,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    val startingPointLabel = when (settings.mapStartingPoint) {
                        "OWN_PIN" -> "Current Location"
                        "FIT_ALL" -> "All Contacts"
                        "FIXED_LOCATION" -> "Fixed Location"
                        else -> "Last Position"
                    }
                    ListItem(
                        headlineContent = { Text("Starting Point") },
                        supportingContent = { Text(startingPointLabel) },
                        leadingContent = { Icon(Icons.Default.TravelExplore, contentDescription = null) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        modifier = Modifier.clickable { showMapStartingPointDialog = true; fixedLocationCaptureMessage = "" },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    if (settings.mapStartingPoint == "FIXED_LOCATION") {
                        val hasFixed = settings.mapFixedLat != 0.0 || settings.mapFixedLng != 0.0
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 56.dp, end = 16.dp, bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                if (hasFixed) "%.5f, %.5f".format(settings.mapFixedLat, settings.mapFixedLng)
                                else "No location set",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(
                                onClick = {
                                    showFixedLocationPicker = true
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Pick Location on Map")
                            }
                        }
                    }
                }
            }

            item { SectionLabel("Privacy & Data") }

            // Data on this device (local retention + manual clear)
            item {
                SettingsCard {
                    RetentionRow(
                        icon = Icons.Default.LocationOn,
                        title = "Location On This Device",
                        subtitle = "How long to keep contacts' location data locally",
                        selected = settings.localLocationRetentionDays,
                        onSelected = { vm.setLocalLocationRetentionDays(it) }
                    )
                    Text(
                        "Per-contact retention on each peer's device is configured on the contact's Sharing page.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    RetentionRow(
                        icon = Icons.AutoMirrored.Filled.Message,
                        title = "Messages On This Device",
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
                        ) { Text("Clear Locations", style = MaterialTheme.typography.labelMedium) }
                        OutlinedButton(
                            onClick = { showClearMessageConfirm = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) { Text("Clear Messages", style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }

            item { SectionLabel("Keys & Backup") }

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
                        headlineContent = { Text("Export Backup") },
                        supportingContent = { Text("Save key, contacts, geofences & settings") },
                        leadingContent = { Icon(Icons.Default.Upload, contentDescription = null) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                        modifier = Modifier.clickable { showExportDialog = true; vm.clearBackupResult() },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    ListItem(
                        headlineContent = { Text("Import Backup") },
                        supportingContent = { Text("Restore from a previously exported file") },
                        leadingContent = { Icon(Icons.Default.Download, contentDescription = null) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                        modifier = Modifier.clickable { importLauncher.launch(arrayOf("application/json", "*/*")); vm.clearBackupResult() },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    ListItem(
                        headlineContent = { Text("View Private Key") },
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
                            headlineContent = { Text("Supervision Active") },
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
            title = { Text("Select Data to Restore") },
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
            title = { Text("Select Data to Export") },
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
            "history-tab" to "History",
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

    if (showMapStartingPointDialog) {
        val options = listOf(
            "RESTORE_LAST"   to "Last Position",
            "OWN_PIN"        to "Current Location",
            "FIT_ALL"        to "All Contacts",
            "FIXED_LOCATION" to "Fixed Location"
        )
        AlertDialog(
            onDismissRequest = { showMapStartingPointDialog = false },
            title = { Text("Map Starting Point") },
            text = {
                Column {
                    options.forEach { (mode, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.setMapStartingPoint(mode)
                                    showMapStartingPointDialog = false
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.mapStartingPoint == mode,
                                onClick = {
                                    vm.setMapStartingPoint(mode)
                                    showMapStartingPointDialog = false
                                }
                            )
                            Text(label, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMapStartingPointDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showFixedLocationPicker) {
        MapLocationPicker(
            initialLat = settings.mapFixedLat,
            initialLng = settings.mapFixedLng,
            onLocationSelected = { lat, lng ->
                vm.setMapFixedLocation(lat, lng)
                showFixedLocationPicker = false
            },
            onDismiss = { showFixedLocationPicker = false }
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

private val PIN_COLOR_OPTIONS = listOf(
    "#E53935", "#F57C00", "#F9A825", "#388E3C",
    "#00897B", "#0097A7", "#1976D2", "#303F9F",
    "#7B1FA2", "#C2185B", "#5D4037", "#455A64"
)

@Composable
private fun PinColorPicker(selectedColor: String, onColorSelected: (String) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Map Pin Colour", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        
        // Chunk options into 2 rows of 6
        PIN_COLOR_OPTIONS.chunked(6).forEach { rowColors ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowColors.forEach { hex ->
                    val color = Color(android.graphics.Color.parseColor(hex))
                    val isSelected = hex == selectedColor
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(color)
                            .clickable { onColorSelected(hex) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
