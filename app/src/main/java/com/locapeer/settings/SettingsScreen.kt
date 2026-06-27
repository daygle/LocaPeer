package com.locapeer.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.locapeer.data.entity.PeerEntity
import com.locapeer.sharing.DayPicker
import com.locapeer.sharing.SharingSchedule
import com.locapeer.sharing.TimePickerDialog
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
    vm: SettingsViewModel = hiltViewModel()
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
            onUnlocked = { sessionUnlocked = true }
        )
        return
    }

    var nameInput by remember(settings.displayName) { mutableStateOf(settings.displayName) }
    var showKeyDialog by remember { mutableStateOf(false) }
    var exportedKey by remember { mutableStateOf("") }
    var showProfileQr by remember { mutableStateOf(false) }
    var showClearLocationConfirm by remember { mutableStateOf(false) }
    var showClearMessageConfirm by remember { mutableStateOf(false) }
    var showGlobalScheduleStartPicker by remember { mutableStateOf(false) }
    var showGlobalScheduleEndPicker by remember { mutableStateOf(false) }
    var showSupervisedSetup by remember { mutableStateOf(false) }
    var showDisableSupervisedConfirm by remember { mutableStateOf(false) }

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
                        label = "Walking",
                        value = settings.walkingIntervalMinutes,
                        range = 1f..15f,
                        steps = 13,
                        onChanged = { vm.updateIntervals(walking = it) }
                    )
                    IntervalSlider(
                        label = "Running",
                        value = settings.runningIntervalMinutes,
                        range = 1f..10f,
                        steps = 8,
                        onChanged = { vm.updateIntervals(running = it) }
                    )
                    IntervalSlider(
                        label = "Cycling",
                        value = settings.cyclingIntervalMinutes,
                        range = 1f..10f,
                        steps = 8,
                        onChanged = { vm.updateIntervals(cycling = it) }
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

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(12.dp))

                    Text(
                        "Global Sharing Schedule",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Restrict all location sharing to selected times. Per-person schedules can further narrow this.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Only share on a schedule", style = MaterialTheme.typography.bodySmall)
                        Switch(
                            checked = settings.globalScheduleEnabled,
                            onCheckedChange = { vm.setGlobalScheduleEnabled(it) }
                        )
                    }
                    if (settings.globalScheduleEnabled) {
                        Spacer(Modifier.height(12.dp))
                        DayPicker(
                            days = settings.globalScheduleDays,
                            onDaysChanged = { vm.updateGlobalSchedule(days = it) }
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showGlobalScheduleStartPicker = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Start", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(SharingSchedule.formatTime(settings.globalScheduleStartMinute),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold)
                                }
                            }
                            OutlinedButton(
                                onClick = { showGlobalScheduleEndPicker = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("End", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(SharingSchedule.formatTime(settings.globalScheduleEndMinute),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }

            item {
                SettingsSection("Peers") {
                    val broadcasters = peers.filter { it.role == "BROADCASTER" }
                    val subscribers = peers.filter { it.role == "SUBSCRIBER" }

                    if (broadcasters.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Tracked people", style = MaterialTheme.typography.labelSmall)
                            TextButton(onClick = onNavigateToHistoryReport) {
                                Icon(
                                    Icons.Default.History,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("History", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        broadcasters.forEach { peer ->
                            ListItem(
                                headlineContent = { Text(peer.displayName) },
                                supportingContent = { Text(peer.publicKeyHex.take(16) + "…") },
                                trailingContent = {
                                    Row {
                                        IconButton(onClick = { vm.removePeer(peer.deviceId) }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Remove")
                                        }
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
                                    Row {
                                        IconButton(onClick = {
                                            onNavigateToPeerSharing(peer.deviceId, peer.displayName)
                                        }) {
                                            Icon(Icons.Default.Settings, contentDescription = "Sharing settings")
                                        }
                                        IconButton(onClick = { vm.removePeer(peer.deviceId) }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Revoke")
                                        }
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
                SettingsSection("Alerts") {
                    Button(onClick = onNavigateToGeofences, modifier = Modifier.fillMaxWidth()) {
                        Text("Manage Geofences")
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onNavigateToProximityAlerts, modifier = Modifier.fillMaxWidth()) {
                        Text("Proximity Alerts")
                    }
                }
            }

            item {
                SettingsSection("Supervised Mode") {
                    if (settings.supervisedModeEnabled) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Supervision is active",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "Settings require supervisor approval to access.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { showDisableSupervisedConfirm = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.LockOpen, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Disable Supervised Mode")
                        }
                    } else {
                        Text(
                            "Lock settings so a designated supervisor must approve access remotely. " +
                            "Messaging and SOS are always accessible.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Lock,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text("Enable Supervised Mode")
                            }
                            Switch(
                                checked = false,
                                onCheckedChange = { if (it) showSupervisedSetup = true }
                            )
                        }
                    }
                }
            }

            item {
                SettingsSection("Privacy") {
                    Text(
                        "Control how long your data is kept on others' devices.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Remote location history retention",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    RetentionSelector(
                        selected = settings.retentionDays,
                        onSelected = { vm.setRetentionDays(it) }
                    )
                    if (settings.retentionDays > 0) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { vm.sendPurgeNow() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Delete my location history from peers now")
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Sends an immediate deletion request to all current subscribers. " +
                            "Location history older than ${settings.retentionDays} day(s) will be removed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(12.dp))

                    Text(
                        "Remote message retention",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Control how long messages you send are stored on recipients' devices.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    RetentionSelector(
                        selected = settings.messageRetentionDays,
                        onSelected = { vm.setMessageRetentionDays(it) }
                    )
                    if (settings.messageRetentionDays > 0) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { vm.sendMessagePurgeNow() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Delete my messages from peers' devices now")
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Sends an immediate deletion request to all peers. " +
                            "Messages older than ${settings.messageRetentionDays} day(s) will be removed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                SettingsSection("About") {
                    ListItem(
                        headlineContent = { Text("About & Version Info") },
                        supportingContent = { Text("Version, relay status, open source") },
                        modifier = androidx.compose.ui.Modifier.clickable(onClick = onNavigateToAbout)
                    )
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

    if (showGlobalScheduleStartPicker) {
        TimePickerDialog(
            initialMinute = settings.globalScheduleStartMinute,
            title = "Start sharing at",
            onConfirm = { vm.updateGlobalSchedule(startMinute = it); showGlobalScheduleStartPicker = false },
            onDismiss = { showGlobalScheduleStartPicker = false }
        )
    }
    if (showGlobalScheduleEndPicker) {
        TimePickerDialog(
            initialMinute = settings.globalScheduleEndMinute,
            title = "Stop sharing at",
            onConfirm = { vm.updateGlobalSchedule(endMinute = it); showGlobalScheduleEndPicker = false },
            onDismiss = { showGlobalScheduleEndPicker = false }
        )
    }

    if (showSupervisedSetup) {
        SupervisedModeSetupDialog(
            peers = peers,
            onConfirm = { supervisorPubkey ->
                vm.enableSupervisedMode(supervisorPubkey)
                showSupervisedSetup = false
            },
            onDismiss = { showSupervisedSetup = false }
        )
    }

    if (showDisableSupervisedConfirm) {
        AlertDialog(
            onDismissRequest = { showDisableSupervisedConfirm = false },
            title = { Text("Disable Supervised Mode?") },
            text = {
                Text(
                    "Settings will be accessible without a PIN. " +
                    "The device user will be able to change all sharing settings."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.disableSupervisedMode()
                        showDisableSupervisedConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Disable") }
            },
            dismissButton = {
                TextButton(onClick = { showDisableSupervisedConfirm = false }) { Text("Cancel") }
            }
        )
    }
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
private fun RetentionSelector(selected: Int, onSelected: (Int) -> Unit) {
    val rows = RETENTION_OPTIONS.chunked(4)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
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

// ───────────────────────────────── Supervised Mode UI ─────────────────────────────────

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
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Device is Supervised",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Supervisor approval is required to access settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))

        when (unlockState) {
            is SupervisedModeManager.UnlockState.Idle -> {
                Button(onClick = onRequestAccess, modifier = Modifier.fillMaxWidth()) {
                    Text("Request Access")
                }
            }
            is SupervisedModeManager.UnlockState.Requesting -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(
                    "Waiting for supervisor approval…",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onReset) { Text("Cancel") }
            }
            is SupervisedModeManager.UnlockState.Denied -> {
                Text(
                    "Access denied by supervisor.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                    Text("Try Again")
                }
            }
            is SupervisedModeManager.UnlockState.TimedOut -> {
                Text(
                    "Request timed out. Supervisor did not respond.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                    Text("Try Again")
                }
            }
            is SupervisedModeManager.UnlockState.Approved -> {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun SupervisedModeSetupDialog(
    peers: List<PeerEntity>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
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
                    Text(
                        "No peers found. Add a peer first by scanning their invite QR code.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Spacer(Modifier.height(12.dp))
                    peers.forEach { peer ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = selectedPubkey == peer.publicKeyHex,
                                onClick = { selectedPubkey = peer.publicKeyHex }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(peer.displayName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    peer.publicKeyHex.take(16) + "…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedPubkey) },
                enabled = selectedPubkey.isNotEmpty()
            ) { Text("Enable") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
