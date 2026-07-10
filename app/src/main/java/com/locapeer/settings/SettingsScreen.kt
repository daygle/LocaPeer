package com.locapeer.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.locapeer.R
import com.locapeer.data.entity.PeerEntity
import com.locapeer.supervised.SupervisionGate
import com.locapeer.ui.components.MapLocationPicker
import com.locapeer.ui.components.RetentionRow
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun SettingsScreen(
    onNavigateToPeerSharing: (peerId: String, peerName: String) -> Unit = { _, _ -> },
    onNavigateToAbout: () -> Unit = {},
    onNavigateToCustomizeNav: () -> Unit = {},
    onNavigateToGlobalSchedule: () -> Unit = {},
    onNavigateToGeofences: () -> Unit = {},
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
    var showStartPageDialog by remember { mutableStateOf(false) }
    var showMapStartingPointDialog by remember { mutableStateOf(false) }
    var showIntervalsDialog by remember { mutableStateOf(false) }
    var showSpeedUnitDialog by remember { mutableStateOf(false) }
    var showElevationUnitDialog by remember { mutableStateOf(false) }
    var showDistanceUnitDialog by remember { mutableStateOf(false) }
    var showTimeFormatDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showFixedLocationPicker by remember { mutableStateOf(false) }
    var fixedLocationCaptureMessage by remember { mutableStateOf("") }
    var showExportDialog by remember { mutableStateOf(false) }
    // Activity Recognition is a runtime permission only on Android 10+; below that it is
    // install-time granted, so the row is hidden there. motionAsked lets us tell a fresh
    // "never asked" state (shouldShowRationale is also false then) from a permanent denial.
    val context = LocalContext.current
    val motionPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        rememberPermissionState(Manifest.permission.ACTIVITY_RECOGNITION) else null
    var motionAsked by remember { mutableStateOf(false) }
    var showMotionRationale by remember { mutableStateOf(false) }
    var exportSections by remember { mutableStateOf(setOf(BackupSection.PRIVATE_KEY, BackupSection.CONTACTS, BackupSection.GEOFENCES, BackupSection.SETTINGS)) }
    var exportPassword by remember { mutableStateOf("") }
    val exportLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { vm.exportBackup(it, exportSections, exportPassword.takeIf { p -> p.isNotBlank() }) }
        exportPassword = ""
    }
    val backupResult by vm.backupResult.collectAsState()
    val pendingRestore by vm.pendingRestore.collectAsState()
    val restorePasswordError by vm.restorePasswordError.collectAsState()
    val importLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.loadBackupForRestore(it) } }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {

            // ── 1. Profile ───────────────────────────────────────────────────
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
                            settings.displayName.ifBlank { stringResource(R.string.settings_no_name_set) },
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
                            Text(stringResource(R.string.settings_edit_name))
                        }
                        OutlinedButton(onClick = { showProfileQr = true }) {
                            Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.settings_my_qr))
                        }
                    }
                }
            }

            // ── 2. Location & Privacy ──────────────────────────────────────
            item { SectionLabel(stringResource(R.string.settings_section_location_privacy)) }
            item {
                SettingsCard {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_share_location)) },
                        supportingContent = { Text(if (settings.heartbeatEnabled) stringResource(R.string.settings_broadcasting) else stringResource(R.string.settings_not_broadcasting)) },
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
                    NavRow(
                        icon = Icons.Default.Schedule,
                        label = stringResource(R.string.settings_sharing_schedule),
                        subtitle = if (settings.globalScheduleRules.isEmpty()) stringResource(R.string.settings_always_on)
                        else pluralStringResource(R.plurals.settings_schedule_rule_count, settings.globalScheduleRules.size, settings.globalScheduleRules.size),
                        onClick = onNavigateToGlobalSchedule
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    NavRow(
                        icon = Icons.Default.Fence,
                        label = stringResource(R.string.settings_geofences),
                        subtitle = stringResource(R.string.settings_geofences_subtitle),
                        onClick = onNavigateToGeofences
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_notify_when_tracked)) },
                        supportingContent = {
                            Text(stringResource(R.string.settings_notify_when_tracked_subtitle))
                        },
                        leadingContent = { Icon(Icons.Default.Visibility, contentDescription = null) },
                        trailingContent = {
                            Switch(
                                checked = settings.notifyOnTrackingAlerts,
                                onCheckedChange = { vm.setNotifyOnTrackingAlerts(it) }
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    NavRow(
                        icon = Icons.Default.History,
                        label = stringResource(R.string.settings_my_location_history),
                        subtitle = stringResource(R.string.settings_my_location_history_subtitle),
                        onClick = { if (publicKeyHex.isNotBlank()) onNavigateToMyHistory(publicKeyHex) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_lookup_addresses)) },
                        supportingContent = {
                            Text(stringResource(R.string.settings_lookup_addresses_subtitle))
                        },
                        leadingContent = { Icon(Icons.Default.Place, contentDescription = null) },
                        trailingContent = {
                            Switch(
                                checked = settings.reverseGeocodingEnabled,
                                onCheckedChange = { vm.setReverseGeocodingEnabled(it) }
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }

            // ── 3. Security ──────────────────────────────────────────────────
            item { SectionLabel(stringResource(R.string.settings_section_security)) }
            item {
                SettingsCard {
                    if (settings.supervisedModeEnabled) {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.settings_supervision_active)) },
                            supportingContent = { Text(stringResource(R.string.settings_supervision_active_subtitle)) },
                            leadingContent = { Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.settings_disable_supervised), color = MaterialTheme.colorScheme.error) },
                            leadingContent = { Icon(Icons.Default.LockOpen, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            modifier = Modifier.clickable { showDisableSupervisedConfirm = true },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    } else {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.settings_supervised_mode)) },
                            supportingContent = { Text(stringResource(R.string.settings_supervised_mode_subtitle)) },
                            leadingContent = { Icon(Icons.Default.Lock, contentDescription = null) },
                            trailingContent = {
                                Switch(checked = false, onCheckedChange = { if (it) showSupervisedSetup = true })
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            }

            // ── 4. Map ───────────────────────────────────────────────────────
            item { SectionLabel(stringResource(R.string.settings_section_map)) }
            item {
                SettingsCard {
                    val startingPointLabel = when (settings.mapStartingPoint) {
                        "OWN_PIN" -> stringResource(R.string.settings_map_current_location)
                        "FIT_ALL" -> stringResource(R.string.settings_map_all_contacts)
                        "FIXED_LOCATION" -> stringResource(R.string.settings_map_fixed_location)
                        else -> stringResource(R.string.settings_map_last_position)
                    }
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_starting_point)) },
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
                                else stringResource(R.string.settings_no_location_set),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(
                                onClick = { showFixedLocationPicker = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.settings_pick_location_on_map))
                            }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
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
                        Column(modifier = Modifier.weight(1f)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(R.string.settings_default_zoom_level), style = MaterialTheme.typography.bodyMedium)
                                Text(settings.mapStartZoom.toInt().toString(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                            }
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
                    ListItem(
                        leadingContent = { Icon(Icons.Default.Palette, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.settings_map_pin_colour)) },
                        supportingContent = {
                            Column(
                                modifier = Modifier.padding(top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                PIN_COLOR_OPTIONS.chunked(6).forEach { rowColors ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        rowColors.forEach { hex ->
                                            val color = Color(android.graphics.Color.parseColor(hex))
                                            val isSelected = hex == settings.pinColor
                                            Box(
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .clip(CircleShape)
                                                    .background(color)
                                                    .clickable { vm.setPinColor(hex) },
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
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }

            // ── 5. Performance ───────────────────────────────────────────────
            item { SectionLabel(stringResource(R.string.settings_section_battery_performance)) }
            item {
                SettingsCard {
                    NavRow(
                        icon = Icons.Default.Timer,
                        label = stringResource(R.string.settings_update_cadence),
                        subtitle = stringResource(R.string.settings_update_cadence_subtitle),
                        onClick = { showIntervalsDialog = true }
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    MetresFilterSliderRow(
                        icon = Icons.Default.Straighten,
                        title = stringResource(R.string.settings_min_distance_filtering),
                        subtitle = stringResource(R.string.settings_min_distance_filtering_subtitle),
                        valueMeters = settings.historyMinDistanceMeters,
                        onCommit = { vm.setHistoryMinDistanceMeters(it) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    MetresFilterSliderRow(
                        icon = Icons.Default.GpsOff,
                        title = stringResource(R.string.settings_discard_low_accuracy),
                        subtitle = stringResource(R.string.settings_discard_low_accuracy_subtitle),
                        valueMeters = settings.sendMaxAccuracyMeters,
                        onCommit = { vm.setSendMaxAccuracyMeters(it) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    MetresFilterSliderRow(
                        icon = Icons.Default.FilterAlt,
                        title = stringResource(R.string.settings_hide_low_accuracy),
                        subtitle = stringResource(R.string.settings_hide_low_accuracy_subtitle),
                        valueMeters = settings.historyMaxAccuracyMeters,
                        onCommit = { vm.setHistoryMaxAccuracyMeters(it) }
                    )
                    if (motionPermission != null) {
                        HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                        MotionDetectionRow(
                            granted = motionPermission.status.isGranted,
                            onClick = {
                                when {
                                    motionPermission.status.isGranted -> {}
                                    // Asked before and the system will no longer prompt: route to settings.
                                    motionAsked && !motionPermission.status.shouldShowRationale ->
                                        showMotionRationale = true
                                    else -> {
                                        motionAsked = true
                                        motionPermission.launchPermissionRequest()
                                    }
                                }
                            }
                        )
                    }
                }
            }

            // ── 6. Units & Display ───────────────────────────────────────────
            item { SectionLabel(stringResource(R.string.settings_section_units_display)) }
            item {
                SettingsCard {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_speed_units)) },
                        supportingContent = { Text(if (settings.useImperialSpeed) stringResource(R.string.settings_speed_imperial) else stringResource(R.string.settings_speed_metric)) },
                        leadingContent = { Icon(Icons.Default.Speed, contentDescription = null) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        modifier = Modifier.clickable { showSpeedUnitDialog = true },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_elevation_units)) },
                        supportingContent = { Text(if (settings.useImperialElevation) stringResource(R.string.settings_elevation_imperial) else stringResource(R.string.settings_elevation_metric)) },
                        leadingContent = { Icon(Icons.Default.Terrain, contentDescription = null) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        modifier = Modifier.clickable { showElevationUnitDialog = true },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_distance_units)) },
                        supportingContent = { Text(if (settings.useImperialDistance) stringResource(R.string.settings_distance_imperial) else stringResource(R.string.settings_distance_metric)) },
                        leadingContent = { Icon(Icons.Default.Straighten, contentDescription = null) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        modifier = Modifier.clickable { showDistanceUnitDialog = true },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_time_format)) },
                        supportingContent = { Text(if (settings.use24HourTime) stringResource(R.string.settings_time_24h) else stringResource(R.string.settings_time_12h)) },
                        leadingContent = { Icon(Icons.Default.Schedule, contentDescription = null) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        modifier = Modifier.clickable { showTimeFormatDialog = true },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }

            // ── 7. Retention ─────────────────────────────────────────────────
            item { SectionLabel(stringResource(R.string.settings_section_retention)) }
            item {
                SettingsCard {
                    RetentionRow(
                        icon = Icons.Default.LocationOn,
                        title = stringResource(R.string.settings_retention_location),
                        subtitle = stringResource(R.string.settings_retention_location_subtitle),
                        selected = settings.localLocationRetentionDays,
                        onSelected = { vm.setLocalLocationRetentionDays(it) },
                        purgeLabel = stringResource(R.string.settings_clear_all_location),
                        onPurge = { showClearLocationConfirm = true }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    RetentionRow(
                        icon = Icons.AutoMirrored.Filled.Message,
                        title = stringResource(R.string.settings_retention_messages),
                        subtitle = stringResource(R.string.settings_retention_messages_subtitle),
                        selected = settings.localMessageRetentionDays,
                        onSelected = { vm.setLocalMessageRetentionDays(it) },
                        purgeLabel = stringResource(R.string.settings_clear_all_messages),
                        onPurge = { showClearMessageConfirm = true }
                    )
                }
            }

            // ── 8. Appearance ────────────────────────────────────────────────
            item { SectionLabel(stringResource(R.string.settings_section_appearance)) }
            item {
                SettingsCard {
                    NavRow(
                        icon = Icons.Default.GridView,
                        label = stringResource(R.string.settings_customize_nav),
                        subtitle = stringResource(R.string.settings_customize_nav_subtitle),
                        onClick = onNavigateToCustomizeNav
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    NavRow(
                        icon = Icons.Default.Home,
                        label = stringResource(R.string.settings_start_page),
                        subtitle = settings.navTabIds
                            .firstOrNull { it == settings.startRoute }
                            ?.replaceFirstChar { it.uppercaseChar() }
                            ?: stringResource(R.string.tab_map),
                        onClick = { showStartPageDialog = true }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    val currentLanguage = AppLanguage.current()
                    NavRow(
                        icon = Icons.Default.Language,
                        label = stringResource(R.string.settings_language),
                        subtitle = currentLanguage.nativeName ?: stringResource(R.string.settings_language_system),
                        onClick = { showLanguageDialog = true }
                    )
                }
            }

            // ── 9. Keys & Backup ──────────────────────────────────────────────
            item { SectionLabel(stringResource(R.string.settings_section_backup_keys)) }
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
                        headlineContent = { Text(stringResource(R.string.settings_export_backup)) },
                        supportingContent = { Text(stringResource(R.string.settings_export_backup_subtitle)) },
                        leadingContent = { Icon(Icons.Default.Upload, contentDescription = null) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                        modifier = Modifier.clickable { showExportDialog = true; vm.clearBackupResult() },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_import_backup)) },
                        supportingContent = { Text(stringResource(R.string.settings_import_backup_subtitle)) },
                        leadingContent = { Icon(Icons.Default.Download, contentDescription = null) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                        modifier = Modifier.clickable { importLauncher.launch(arrayOf("application/json", "*/*")); vm.clearBackupResult() },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_view_private_key)) },
                        supportingContent = { Text(stringResource(R.string.settings_view_private_key_subtitle)) },
                        leadingContent = { Icon(Icons.Default.VpnKey, contentDescription = null) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                        modifier = Modifier.clickable { vm.exportPrivateKey { key -> exportedKey = key; showKeyDialog = true } },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }

            // ── 10. About ────────────────────────────────────────────────────
            item { SectionLabel(stringResource(R.string.settings_section_about)) }
            item {
                SettingsCard {
                    NavRow(
                        icon = Icons.Default.Info,
                        label = stringResource(R.string.settings_about_locapeer),
                        subtitle = stringResource(R.string.settings_about_subtitle),
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
            title = { Text(stringResource(R.string.settings_edit_display_name)) },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text(stringResource(R.string.settings_display_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.updateDisplayName(nameInput); showNameDialog = false },
                    enabled = nameInput.isNotBlank()
                ) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = { TextButton(onClick = { showNameDialog = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    if (showProfileQr) {
        AlertDialog(
            onDismissRequest = { showProfileQr = false },
            title = { Text(stringResource(R.string.settings_my_invite_qr)) },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(R.string.settings_invite_qr_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    profileQr?.let { bmp ->
                        Image(bitmap = bmp.asImageBitmap(), contentDescription = stringResource(R.string.settings_invite_qr_cd), modifier = Modifier.size(220.dp))
                    } ?: CircularProgressIndicator()
                }
            },
            confirmButton = { TextButton(onClick = { showProfileQr = false }) { Text(stringResource(R.string.common_done)) } }
        )
    }

    if (showKeyDialog) {
        AlertDialog(
            onDismissRequest = { showKeyDialog = false },
            title = { Text(stringResource(R.string.settings_private_key_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.settings_private_key_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer { Text(exportedKey, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(onClick = { showKeyDialog = false }) { Text(stringResource(R.string.common_done)) } }
        )
    }

    if (showClearLocationConfirm) {
        AlertDialog(
            onDismissRequest = { showClearLocationConfirm = false },
            title = { Text(stringResource(R.string.settings_clear_location_title)) },
            text = { Text(stringResource(R.string.settings_clear_location_message)) },
            confirmButton = {
                TextButton(onClick = { vm.clearLocationHistory(); showClearLocationConfirm = false }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = { TextButton(onClick = { showClearLocationConfirm = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    if (showClearMessageConfirm) {
        AlertDialog(
            onDismissRequest = { showClearMessageConfirm = false },
            title = { Text(stringResource(R.string.settings_clear_messages_title)) },
            text = { Text(stringResource(R.string.settings_clear_messages_message)) },
            confirmButton = {
                TextButton(onClick = { vm.clearMessageHistory(); showClearMessageConfirm = false }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = { TextButton(onClick = { showClearMessageConfirm = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    pendingRestore?.let { restore ->
        if (restore.requiresPassword) {
            var passwordInput by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { vm.dismissPendingRestore() },
                title = { Text(stringResource(R.string.settings_encrypted_backup_title)) },
                text = {
                    Column {
                        Text(stringResource(R.string.settings_encrypted_backup_message), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = passwordInput,
                            onValueChange = { passwordInput = it },
                            label = { Text(stringResource(R.string.common_password)) },
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            singleLine = true,
                            isError = restorePasswordError != null,
                            supportingText = restorePasswordError?.let { { Text(it) } },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { vm.decryptBackupForRestore(passwordInput) },
                        enabled = passwordInput.isNotBlank()
                    ) { Text(stringResource(R.string.common_unlock)) }
                },
                dismissButton = {
                    TextButton(onClick = { vm.dismissPendingRestore() }) { Text(stringResource(R.string.common_cancel)) }
                }
            )
            return@let
        }

        var importSections by remember(restore) { mutableStateOf(restore.availableSections) }
        AlertDialog(
            onDismissRequest = { vm.dismissPendingRestore() },
            title = { Text(stringResource(R.string.settings_select_restore_title)) },
            text = {
                Column {
                    BackupSectionItem(stringResource(R.string.backup_section_private_key), BackupSection.PRIVATE_KEY, importSections, restore.availableSections) { importSections = it }
                    BackupSectionItem(stringResource(R.string.backup_section_contacts), BackupSection.CONTACTS, importSections, restore.availableSections) { importSections = it }
                    BackupSectionItem(stringResource(R.string.backup_section_geofences), BackupSection.GEOFENCES, importSections, restore.availableSections) { importSections = it }
                    BackupSectionItem(stringResource(R.string.backup_section_settings), BackupSection.SETTINGS, importSections, restore.availableSections) { importSections = it }
                }
            },
            confirmButton = {
                Button(onClick = { vm.applyRestore(importSections) }, enabled = importSections.isNotEmpty()) { Text(stringResource(R.string.common_restore)) }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissPendingRestore() }) { Text(stringResource(R.string.common_cancel)) }
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
            title = { Text(stringResource(R.string.settings_disable_supervised_title)) },
            text = { Text(stringResource(R.string.settings_disable_supervised_message)) },
            confirmButton = {
                TextButton(onClick = { vm.disableSupervisedMode(); showDisableSupervisedConfirm = false }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.common_disable)) }
            },
            dismissButton = { TextButton(onClick = { showDisableSupervisedConfirm = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false; exportPassword = "" },
            title = { Text(stringResource(R.string.settings_select_export_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.settings_export_password_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = exportPassword,
                        onValueChange = { exportPassword = it },
                        label = { Text(stringResource(R.string.settings_backup_password_optional)) },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(stringResource(R.string.settings_select_sections), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    BackupSectionItem(stringResource(R.string.backup_section_private_key), BackupSection.PRIVATE_KEY, exportSections, BackupSection.entries.toSet()) { exportSections = it }
                    BackupSectionItem(stringResource(R.string.backup_section_contacts), BackupSection.CONTACTS, exportSections, BackupSection.entries.toSet()) { exportSections = it }
                    BackupSectionItem(stringResource(R.string.backup_section_geofences), BackupSection.GEOFENCES, exportSections, BackupSection.entries.toSet()) { exportSections = it }
                    BackupSectionItem(stringResource(R.string.backup_section_settings), BackupSection.SETTINGS, exportSections, BackupSection.entries.toSet()) { exportSections = it }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExportDialog = false
                        exportLauncher.launch("locapeer-backup.json")
                    },
                    enabled = exportSections.isNotEmpty()
                ) { Text(stringResource(R.string.settings_choose_file_location)) }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false; exportPassword = "" }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (showStartPageDialog) {
        val tabLabels = mapOf(
            "map" to stringResource(R.string.tab_map),
            "messages" to stringResource(R.string.tab_messages),
            "history-tab" to stringResource(R.string.tab_history),
            "contacts" to stringResource(R.string.tab_contacts),
            "invite" to stringResource(R.string.tab_qr),
            "settings" to stringResource(R.string.tab_settings)
        )
        AlertDialog(
            onDismissRequest = { showStartPageDialog = false },
            title = { Text(stringResource(R.string.settings_start_page)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    settings.navTabIds.forEach { route ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.setStartRoute(route)
                                    showStartPageDialog = false
                                }
                                .padding(vertical = 8.dp),
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
                                modifier = Modifier.padding(start = 12.dp),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showStartPageDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (showLanguageDialog) {
        val current = AppLanguage.current()
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.settings_language_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    AppLanguage.entries.forEach { language ->
                        val label = language.nativeName ?: stringResource(R.string.settings_language_system)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    AppLanguage.apply(language)
                                    showLanguageDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = current == language,
                                onClick = {
                                    AppLanguage.apply(language)
                                    showLanguageDialog = false
                                }
                            )
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (showMapStartingPointDialog) {
        val options = listOf(
            "RESTORE_LAST"   to stringResource(R.string.settings_map_last_position),
            "OWN_PIN"        to stringResource(R.string.settings_map_current_location),
            "FIT_ALL"        to stringResource(R.string.settings_map_all_contacts),
            "FIXED_LOCATION" to stringResource(R.string.settings_map_fixed_location)
        )
        AlertDialog(
            onDismissRequest = { showMapStartingPointDialog = false },
            title = { Text(stringResource(R.string.settings_map_starting_point_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    options.forEach { (mode, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.setMapStartingPoint(mode)
                                    showMapStartingPointDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.mapStartingPoint == mode,
                                onClick = {
                                    vm.setMapStartingPoint(mode)
                                    showMapStartingPointDialog = false
                                }
                            )
                            Text(
                                text = label,
                                modifier = Modifier.padding(start = 12.dp),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMapStartingPointDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (showIntervalsDialog) {
        AlertDialog(
            onDismissRequest = { showIntervalsDialog = false },
            title = { Text(stringResource(R.string.settings_update_cadence)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(stringResource(R.string.settings_update_cadence_dialog_message),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    IntervalSlider(stringResource(R.string.motion_stationary), settings.stationaryIntervalMinutes, 5f..60f, 10) { vm.updateIntervals(stationary = it) }
                    IntervalSlider(stringResource(R.string.motion_walking),    settings.walkingIntervalMinutes,    1f..15f, 13) { vm.updateIntervals(walking = it) }
                    IntervalSlider(stringResource(R.string.motion_running),    settings.runningIntervalMinutes,    1f..10f,  8) { vm.updateIntervals(running = it) }
                    IntervalSlider(stringResource(R.string.motion_cycling),    settings.cyclingIntervalMinutes,    1f..10f,  8) { vm.updateIntervals(cycling = it) }
                    IntervalSlider(stringResource(R.string.motion_driving),    settings.drivingIntervalMinutes,    1f..10f,  8) { vm.updateIntervals(driving = it) }
                    IntervalSlider(stringResource(R.string.settings_low_battery), settings.lowBatteryIntervalMinutes, 15f..120f, 6) { vm.updateIntervals(lowBattery = it) }
                }
            },
            confirmButton = { TextButton(onClick = { showIntervalsDialog = false }) { Text(stringResource(R.string.common_done)) } },
            dismissButton = { TextButton(onClick = { vm.resetIntervals() }) { Text(stringResource(R.string.settings_update_cadence_reset)) } }
        )
    }

    if (showSpeedUnitDialog) {
        UnitSelectionDialog(
            title = stringResource(R.string.settings_speed_units),
            options = listOf(false to stringResource(R.string.settings_speed_metric), true to stringResource(R.string.settings_speed_imperial)),
            current = settings.useImperialSpeed,
            onSelected = { vm.setUseImperialSpeed(it); showSpeedUnitDialog = false },
            onDismiss = { showSpeedUnitDialog = false }
        )
    }

    if (showElevationUnitDialog) {
        UnitSelectionDialog(
            title = stringResource(R.string.settings_elevation_units),
            options = listOf(false to stringResource(R.string.settings_elevation_metric), true to stringResource(R.string.settings_elevation_imperial)),
            current = settings.useImperialElevation,
            onSelected = { vm.setUseImperialElevation(it); showElevationUnitDialog = false },
            onDismiss = { showElevationUnitDialog = false }
        )
    }

    if (showDistanceUnitDialog) {
        UnitSelectionDialog(
            title = stringResource(R.string.settings_distance_units),
            options = listOf(false to stringResource(R.string.settings_distance_metric), true to stringResource(R.string.settings_distance_imperial)),
            current = settings.useImperialDistance,
            onSelected = { vm.setUseImperialDistance(it); showDistanceUnitDialog = false },
            onDismiss = { showDistanceUnitDialog = false }
        )
    }

    if (showTimeFormatDialog) {
        UnitSelectionDialog(
            title = stringResource(R.string.settings_time_format),
            options = listOf(false to stringResource(R.string.settings_time_12h), true to stringResource(R.string.settings_time_24h)),
            current = settings.use24HourTime,
            onSelected = { vm.setUse24HourTime(it); showTimeFormatDialog = false },
            onDismiss = { showTimeFormatDialog = false }
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

    if (showMotionRationale) {
        AlertDialog(
            onDismissRequest = { showMotionRationale = false },
            icon = { Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = null) },
            title = { Text(stringResource(R.string.settings_enable_motion_title)) },
            text = {
                Text(stringResource(R.string.settings_enable_motion_message))
            },
            confirmButton = {
                TextButton(onClick = {
                    showMotionRationale = false
                    openAppSettings(context)
                }) { Text(stringResource(R.string.settings_open_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { showMotionRationale = false }) { Text(stringResource(R.string.common_not_now)) }
            }
        )
    }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
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

/**
 * Optional Activity Recognition permission toggle. When granted it shows an enabled state
 * and is inert; when not, it is tappable to request the permission (or, once permanently
 * denied, to open system settings - handled by the caller).
 */
@Composable
private fun MotionDetectionRow(granted: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_motion_detection)) },
        supportingContent = {
            Text(
                if (granted) stringResource(R.string.settings_motion_detection_on)
                else stringResource(R.string.settings_motion_detection_off)
            )
        },
        leadingContent = { Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = null) },
        trailingContent = {
            if (granted) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            } else {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        modifier = if (granted) Modifier else Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

/**
 * A labelled 0–500 m slider used for the distance/accuracy filters. Shows "Off" at 0 and the
 * unit-aware distance otherwise, and only commits on release so the setting isn't rewritten on
 * every drag frame.
 */
@Composable
private fun MetresFilterSliderRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    valueMeters: Int,
    onCommit: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.padding(top = 2.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            var value by remember(valueMeters) { mutableFloatStateOf(valueMeters.toFloat()) }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (value.roundToInt() == 0) stringResource(R.string.common_off)
                    else com.locapeer.util.DisplayFormat.distanceValue(value.roundToInt().toDouble()),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = value,
                onValueChange = { value = it },
                onValueChangeFinished = { onCommit(value.roundToInt()) },
                valueRange = 0f..500f,
                steps = 19,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun IntervalSlider(label: String, value: Int, range: ClosedFloatingPointRange<Float>, steps: Int, onChanged: (Int) -> Unit) {
    var sliderValue by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.settings_minutes_short, sliderValue.roundToInt()), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
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

@Composable
private fun UnitSelectionDialog(
    title: String,
    options: List<Pair<Boolean, String>>,
    current: Boolean,
    onSelected: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelected(value) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = current == value, onClick = { onSelected(value) })
                        Text(
                            text = label,
                            modifier = Modifier.padding(start = 12.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

// ─── Supervised Mode UI ───────────────────────────────────────────────────────


@Composable
private fun SupervisedModeSetupDialog(peers: List<PeerEntity>, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var selectedPubkey by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_enable_supervised_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    stringResource(R.string.settings_enable_supervised_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (peers.isEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.settings_no_peers_found), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                } else {
                    Spacer(Modifier.height(12.dp))
                    peers.forEach { peer ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedPubkey = peer.publicKeyHex }
                                .padding(vertical = 8.dp)
                        ) {
                            RadioButton(selected = selectedPubkey == peer.publicKeyHex, onClick = { selectedPubkey = peer.publicKeyHex })
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(peer.displayName, style = MaterialTheme.typography.bodyLarge)
                                Text(peer.publicKeyHex.take(16) + "…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selectedPubkey) }, enabled = selectedPubkey.isNotEmpty()) { Text(stringResource(R.string.common_enable)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
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
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = section in selected,
            onCheckedChange = { checked ->
                onToggle(if (checked) selected + section else selected - section)
            },
            enabled = enabled
        )
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
            if (!enabled) {
                Text(
                    stringResource(R.string.settings_not_in_backup),
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
