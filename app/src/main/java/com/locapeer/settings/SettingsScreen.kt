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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import com.locapeer.ui.theme.locaPeerTopAppBarColors
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.locapeer.R
import com.locapeer.data.entity.PeerEntity
import com.locapeer.supervised.SupervisionGate
import com.locapeer.ui.components.CardDivider
import com.locapeer.ui.components.MapLocationPicker
import com.locapeer.ui.components.RetentionRow
import com.locapeer.ui.components.SettingsCard
import com.locapeer.ui.components.SettingsRow
import com.locapeer.ui.components.SwitchRow
import java.util.Date
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToPeerSharing: (peerId: String, peerName: String) -> Unit = { _, _ -> },
    onNavigateToAbout: () -> Unit = {},
    onNavigateToCustomizeNav: () -> Unit = {},
    onNavigateToRelays: () -> Unit = {},
    onNavigateToGlobalSchedule: () -> Unit = {},
    onNavigateToGeofences: () -> Unit = {},
    onNavigateToMyHistory: (pubkeyHex: String) -> Unit = {},
    onNavigateToPermissions: () -> Unit = {},
    vm: SettingsViewModel = hiltViewModel(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val peers by vm.peers.collectAsStateWithLifecycle()
    val publicKeyHex by vm.publicKeyHex.collectAsStateWithLifecycle()
    val profileQr by vm.profileQr.collectAsStateWithLifecycle()
    val activeTempShares by vm.activeTempShares.collectAsStateWithLifecycle()

    val unlockState by vm.unlockState.collectAsStateWithLifecycle()
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
    var showLockTimeoutDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var exportSections by remember { mutableStateOf(setOf(BackupSection.PRIVATE_KEY, BackupSection.CONTACTS, BackupSection.GEOFENCES, BackupSection.SETTINGS)) }
    var exportPassword by remember { mutableStateOf("") }
    val exportLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { vm.exportBackup(it, exportSections, exportPassword.takeIf { p -> p.isNotBlank() }) }
        exportPassword = ""
    }
    val backupResult by vm.backupResult.collectAsStateWithLifecycle()
    val pendingRestore by vm.pendingRestore.collectAsStateWithLifecycle()
    val restorePasswordError by vm.restorePasswordError.collectAsStateWithLifecycle()
    val importLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.loadBackupForRestore(it) } }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }, colors = locaPeerTopAppBarColors()) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── 1. Profile ───────────────────────────────────────────────────
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val avatarColor = if (settings.pinColor.isNotEmpty())
                        Color(settings.pinColor.toColorInt())
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
                            color = if (settings.pinColor.isNotEmpty()) Color.White
                            else MaterialTheme.colorScheme.onPrimaryContainer
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

            // ── 1b. Active Temporary Shares ──────────────────────────────────
            if (activeTempShares.isNotEmpty()) {
                item {
                    SettingsCard(
                        headerIcon = Icons.Default.Timelapse,
                        headerTitle = stringResource(R.string.settings_temporary_location_share)
                    ) {
                        activeTempShares.forEachIndexed { index, (peer, config) ->
                            val endsAt = config.temporaryShareEndsAtEpochSeconds ?: 0L
                            val nowSec by produceState(initialValue = System.currentTimeMillis() / 1000L) {
                                while (true) {
                                    value = System.currentTimeMillis() / 1000L
                                    kotlinx.coroutines.delay(1000L)
                                }
                            }
                            if (index > 0) CardDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onNavigateToPeerSharing(peer.deviceId, peer.displayName) }
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        peer.displayName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                    Text(
                                        stringResource(
                                            R.string.peer_temp_share_active_time_left,
                                            peer.displayName,
                                            com.locapeer.util.DisplayFormat.humanizeRemaining(endsAt - nowSec)
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                IconButton(onClick = { vm.stopTemporaryShare(peer.deviceId) }) {
                                    Icon(
                                        Icons.Default.Stop,
                                        contentDescription = stringResource(R.string.peer_temp_share_stop),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── 2. Location & Privacy ──────────────────────────────────────
            item {
                SettingsCard(
                    headerIcon = Icons.Default.LocationOn,
                    headerTitle = stringResource(R.string.settings_section_location_privacy)
                ) {
                    SwitchRow(
                        title = stringResource(R.string.settings_share_location),
                        subtitle = if (settings.heartbeatEnabled) stringResource(R.string.settings_broadcasting) else stringResource(R.string.settings_not_broadcasting),
                        checked = settings.heartbeatEnabled,
                        onCheckedChange = { vm.setHeartbeatEnabled(it) }
                    )
                    CardDivider()
                    SettingsRow(
                        title = stringResource(R.string.settings_sharing_schedule),
                        value = if (settings.globalScheduleRules.isEmpty()) stringResource(R.string.settings_always_on)
                        else pluralStringResource(R.plurals.settings_schedule_rule_count, settings.globalScheduleRules.size, settings.globalScheduleRules.size),
                        onClick = onNavigateToGlobalSchedule
                    )
                    CardDivider()
                    SettingsRow(
                        title = stringResource(R.string.settings_geofences),
                        subtitle = stringResource(R.string.settings_geofences_subtitle),
                        onClick = onNavigateToGeofences
                    )
                    CardDivider()
                    SwitchRow(
                        title = stringResource(R.string.settings_notify_when_tracked),
                        subtitle = stringResource(R.string.settings_notify_when_tracked_subtitle),
                        checked = settings.notifyOnTrackingAlerts,
                        onCheckedChange = { vm.setNotifyOnTrackingAlerts(it) }
                    )
                    CardDivider()
                    SettingsRow(
                        title = stringResource(R.string.settings_my_location_history),
                        subtitle = stringResource(R.string.settings_my_location_history_subtitle),
                        onClick = { if (publicKeyHex.isNotBlank()) onNavigateToMyHistory(publicKeyHex) }
                    )
                    CardDivider()
                    SwitchRow(
                        title = stringResource(R.string.settings_lookup_addresses),
                        subtitle = stringResource(R.string.settings_lookup_addresses_subtitle),
                        checked = settings.reverseGeocodingEnabled,
                        onCheckedChange = { vm.setReverseGeocodingEnabled(it) }
                    )
                    CardDivider()
                    SwitchRow(
                        title = stringResource(R.string.settings_allow_live_boost),
                        subtitle = stringResource(R.string.settings_allow_live_boost_subtitle),
                        checked = settings.allowLiveBoost,
                        onCheckedChange = { vm.setAllowLiveBoost(it) }
                    )
                    CardDivider()
                    SwitchRow(
                        title = stringResource(R.string.settings_request_live_boost),
                        subtitle = stringResource(R.string.settings_request_live_boost_subtitle),
                        checked = settings.requestLiveBoost,
                        onCheckedChange = { vm.setRequestLiveBoost(it) }
                    )
                }
            }

            // ── 3. Security ──────────────────────────────────────────────────
            item {
                SettingsCard(
                    headerIcon = Icons.Default.Shield,
                    headerTitle = stringResource(R.string.settings_section_security)
                ) {
                    SwitchRow(
                        title = stringResource(R.string.settings_app_lock),
                        subtitle = stringResource(R.string.settings_app_lock_subtitle),
                        checked = settings.appLockEnabled,
                        onCheckedChange = { vm.setAppLockEnabled(it) }
                    )
                    if (settings.appLockEnabled) {
                        CardDivider()
                        SettingsRow(
                            title = stringResource(R.string.settings_app_lock_timeout),
                            value = when (settings.appLockTimeoutSeconds) {
                                0  -> stringResource(R.string.settings_app_lock_timeout_immediate)
                                30 -> stringResource(R.string.settings_app_lock_timeout_30s)
                                60 -> stringResource(R.string.settings_app_lock_timeout_1m)
                                else -> stringResource(R.string.settings_app_lock_timeout_5m)
                            },
                            onClick = { showLockTimeoutDialog = true }
                        )
                    }
                    CardDivider()
                    SettingsRow(
                        title = stringResource(R.string.settings_permissions),
                        subtitle = stringResource(R.string.settings_permissions_subtitle),
                        onClick = onNavigateToPermissions
                    )
                    CardDivider()
                    if (settings.supervisedModeEnabled) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.settings_supervision_active),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    stringResource(R.string.settings_supervision_active_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                        CardDivider()
                        SettingsRow(
                            title = stringResource(R.string.settings_disable_supervised),
                            destructive = true,
                            onClick = { showDisableSupervisedConfirm = true }
                        )
                    } else {
                        SwitchRow(
                            title = stringResource(R.string.settings_supervised_mode),
                            subtitle = stringResource(R.string.settings_supervised_mode_subtitle),
                            checked = false,
                            onCheckedChange = { if (it) showSupervisedSetup = true }
                        )
                    }
                }
            }

            // ── 4. Map ───────────────────────────────────────────────────────
            item {
                val startingPointLabel = when (settings.mapStartingPoint) {
                    "OWN_PIN" -> stringResource(R.string.settings_map_current_location)
                    "FIT_ALL" -> stringResource(R.string.settings_map_all_contacts)
                    "FIXED_LOCATION" -> stringResource(R.string.settings_map_fixed_location)
                    else -> stringResource(R.string.settings_map_last_position)
                }
                SettingsCard(
                    headerIcon = Icons.Default.Map,
                    headerTitle = stringResource(R.string.settings_section_map)
                ) {
                    SettingsRow(
                        title = stringResource(R.string.settings_starting_point),
                        value = startingPointLabel,
                        onClick = { showMapStartingPointDialog = true; fixedLocationCaptureMessage = "" }
                    )
                    if (settings.mapStartingPoint == "FIXED_LOCATION") {
                        val hasFixed = settings.mapFixedLat != 0.0 || settings.mapFixedLng != 0.0
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
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
                    CardDivider()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.settings_default_zoom_level), style = MaterialTheme.typography.bodyLarge)
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
                    CardDivider()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(stringResource(R.string.settings_map_pin_colour), style = MaterialTheme.typography.bodyLarge)
                        PIN_COLOR_OPTIONS.chunked(6).forEach { rowColors ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                rowColors.forEach { hex ->
                                    val color = Color(hex.toColorInt())
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
                }
            }

            // ── 5. Performance ───────────────────────────────────────────────
            item {
                SettingsCard(
                    headerIcon = Icons.Default.Timer,
                    headerTitle = stringResource(R.string.settings_section_battery_performance)
                ) {
                    SettingsRow(
                        title = stringResource(R.string.settings_update_cadence),
                        subtitle = stringResource(R.string.settings_update_cadence_subtitle),
                        onClick = { showIntervalsDialog = true }
                    )
                    CardDivider()
                    MetresFilterSliderRow(
                        title = stringResource(R.string.settings_min_distance_filtering),
                        subtitle = stringResource(R.string.settings_min_distance_filtering_subtitle),
                        valueMeters = settings.historyMinDistanceMeters,
                        onCommit = { vm.setHistoryMinDistanceMeters(it) }
                    )
                    CardDivider()
                    MetresFilterSliderRow(
                        title = stringResource(R.string.settings_discard_low_accuracy),
                        subtitle = stringResource(R.string.settings_discard_low_accuracy_subtitle),
                        valueMeters = settings.sendMaxAccuracyMeters,
                        onCommit = { vm.setSendMaxAccuracyMeters(it) }
                    )
                    CardDivider()
                    MetresFilterSliderRow(
                        title = stringResource(R.string.settings_hide_low_accuracy),
                        subtitle = stringResource(R.string.settings_hide_low_accuracy_subtitle),
                        valueMeters = settings.historyMaxAccuracyMeters,
                        onCommit = { vm.setHistoryMaxAccuracyMeters(it) }
                    )
                }
            }

            // ── 6. Units & Display ───────────────────────────────────────────
            item {
                SettingsCard(
                    headerIcon = Icons.Default.Straighten,
                    headerTitle = stringResource(R.string.settings_section_units_display)
                ) {
                    SettingsRow(
                        title = stringResource(R.string.settings_speed_units),
                        value = if (settings.useImperialSpeed) stringResource(R.string.settings_speed_imperial) else stringResource(R.string.settings_speed_metric),
                        onClick = { showSpeedUnitDialog = true }
                    )
                    CardDivider()
                    SettingsRow(
                        title = stringResource(R.string.settings_elevation_units),
                        value = if (settings.useImperialElevation) stringResource(R.string.settings_elevation_imperial) else stringResource(R.string.settings_elevation_metric),
                        onClick = { showElevationUnitDialog = true }
                    )
                    CardDivider()
                    SettingsRow(
                        title = stringResource(R.string.settings_distance_units),
                        value = if (settings.useImperialDistance) stringResource(R.string.settings_distance_imperial) else stringResource(R.string.settings_distance_metric),
                        onClick = { showDistanceUnitDialog = true }
                    )
                    CardDivider()
                    SettingsRow(
                        title = stringResource(R.string.settings_time_format),
                        value = if (settings.use24HourTime) stringResource(R.string.settings_time_24h) else stringResource(R.string.settings_time_12h),
                        onClick = { showTimeFormatDialog = true }
                    )
                }
            }

            // ── 7. Retention ─────────────────────────────────────────────────
            item {
                SettingsCard(
                    headerIcon = Icons.Default.History,
                    headerTitle = stringResource(R.string.settings_section_retention)
                ) {
                    RetentionRow(
                        title = stringResource(R.string.settings_retention_location),
                        subtitle = stringResource(R.string.settings_retention_location_subtitle),
                        selected = settings.localLocationRetentionDays,
                        onSelected = { vm.setLocalLocationRetentionDays(it) },
                        purgeLabel = stringResource(R.string.settings_clear_all_location),
                        onPurge = { showClearLocationConfirm = true }
                    )
                    CardDivider()
                    RetentionRow(
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
            item {
                val currentThemeLabel = when (settings.themeMode) {
                    "LIGHT" -> stringResource(R.string.settings_theme_light)
                    "DARK" -> stringResource(R.string.settings_theme_dark)
                    else -> stringResource(R.string.settings_theme_system)
                }
                val currentLanguage = AppLanguage.current()
                SettingsCard(
                    headerIcon = Icons.Default.Palette,
                    headerTitle = stringResource(R.string.settings_section_appearance)
                ) {
                    SettingsRow(
                        title = stringResource(R.string.settings_theme_mode),
                        value = currentThemeLabel,
                        onClick = { showThemeDialog = true }
                    )
                    CardDivider()
                    SwitchRow(
                        title = stringResource(R.string.settings_dynamic_color),
                        subtitle = stringResource(R.string.settings_dynamic_color_subtitle),
                        checked = settings.useDynamicColor,
                        onCheckedChange = { vm.setUseDynamicColor(it) }
                    )
                    CardDivider()
                    SettingsRow(
                        title = stringResource(R.string.settings_customize_nav),
                        subtitle = stringResource(R.string.settings_customize_nav_subtitle),
                        onClick = onNavigateToCustomizeNav
                    )
                    CardDivider()
                    SettingsRow(
                        title = stringResource(R.string.settings_relays),
                        subtitle = stringResource(R.string.settings_relays_subtitle),
                        onClick = onNavigateToRelays
                    )
                    CardDivider()
                    SettingsRow(
                        title = stringResource(R.string.settings_start_page),
                        value = tabLabel(settings.startRoute),
                        onClick = { showStartPageDialog = true }
                    )
                    CardDivider()
                    SettingsRow(
                        title = stringResource(R.string.settings_language),
                        value = currentLanguage.nativeName ?: stringResource(R.string.settings_language_system),
                        onClick = { showLanguageDialog = true }
                    )
                }
            }

            // ── 9. Keys & Backup ──────────────────────────────────────────────
            item {
                SettingsCard(
                    headerIcon = Icons.Default.VpnKey,
                    headerTitle = stringResource(R.string.settings_section_backup_keys)
                ) {
                    backupResult?.let { result ->
                        Text(
                            result.message,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (result.isError) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary
                        )
                        CardDivider()
                    }
                    SettingsRow(
                        title = stringResource(R.string.settings_export_backup),
                        subtitle = stringResource(R.string.settings_export_backup_subtitle),
                        onClick = { showExportDialog = true; vm.clearBackupResult() }
                    )
                    CardDivider()
                    SettingsRow(
                        title = stringResource(R.string.settings_import_backup),
                        subtitle = stringResource(R.string.settings_import_backup_subtitle),
                        onClick = { importLauncher.launch(arrayOf("application/json", "*/*")); vm.clearBackupResult() }
                    )
                    CardDivider()
                    SettingsRow(
                        title = stringResource(R.string.settings_view_private_key),
                        subtitle = stringResource(R.string.settings_view_private_key_subtitle),
                        onClick = { vm.exportPrivateKey { key -> exportedKey = key; showKeyDialog = true } }
                    )
                }
            }

            // ── 10. About ────────────────────────────────────────────────────
            item {
                SettingsCard(
                    headerIcon = Icons.Default.Info,
                    headerTitle = stringResource(R.string.settings_section_about)
                ) {
                    SettingsRow(
                        title = stringResource(R.string.settings_about_locapeer),
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
                    // Exporting the private key without a password would write it in plaintext;
                    // warn inline and disable the confirm button below until one is set.
                    if (BackupSection.PRIVATE_KEY in exportSections && exportPassword.isBlank()) {
                        Text(
                            stringResource(R.string.backup_key_needs_password),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
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
                    enabled = exportSections.isNotEmpty() &&
                        (BackupSection.PRIVATE_KEY !in exportSections || exportPassword.isNotBlank())
                ) { Text(stringResource(R.string.settings_choose_file_location)) }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false; exportPassword = "" }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (showStartPageDialog) {
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
                                tabLabel(route),
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

                    TextButton(
                        onClick = { vm.resetIntervals() },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) { Text(stringResource(R.string.settings_update_cadence_reset)) }
                }
            },
            confirmButton = { TextButton(onClick = { showIntervalsDialog = false }) { Text(stringResource(R.string.common_done)) } }
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

    if (showLockTimeoutDialog) {
        val timeoutOptions = listOf(
            0  to stringResource(R.string.settings_app_lock_timeout_immediate),
            30 to stringResource(R.string.settings_app_lock_timeout_30s),
            60 to stringResource(R.string.settings_app_lock_timeout_1m),
            300 to stringResource(R.string.settings_app_lock_timeout_5m),
        )
        AlertDialog(
            onDismissRequest = { showLockTimeoutDialog = false },
            title = { Text(stringResource(R.string.settings_app_lock_timeout)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    timeoutOptions.forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.setAppLockTimeoutSeconds(value)
                                    showLockTimeoutDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.appLockTimeoutSeconds == value,
                                onClick = {
                                    vm.setAppLockTimeoutSeconds(value)
                                    showLockTimeoutDialog = false
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
                TextButton(onClick = { showLockTimeoutDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (showThemeDialog) {
        val themeOptions = listOf(
            com.locapeer.settings.ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system),
            com.locapeer.settings.ThemeMode.LIGHT  to stringResource(R.string.settings_theme_light),
            com.locapeer.settings.ThemeMode.DARK   to stringResource(R.string.settings_theme_dark),
        )
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text(stringResource(R.string.settings_theme_mode)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    themeOptions.forEach { (mode, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.setThemeMode(mode)
                                    showThemeDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.themeMode == mode.name,
                                onClick = {
                                    vm.setThemeMode(mode)
                                    showThemeDialog = false
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
                TextButton(onClick = { showThemeDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

// ─── Shared composables ──────────────────────────────────────────────────────

/** Localized bottom-navigation label for a route id, matching the tabs shown in the app. */
@Composable
private fun tabLabel(route: String): String = when (route) {
    "map" -> stringResource(R.string.tab_map)
    "messages" -> stringResource(R.string.tab_messages)
    "history-tab" -> stringResource(R.string.tab_history)
    "contacts" -> stringResource(R.string.tab_contacts)
    "invite" -> stringResource(R.string.contacts_cd_qr_invite)
    "settings" -> stringResource(R.string.tab_settings)
    else -> route.replaceFirstChar { it.uppercaseChar() }
}

/**
 * A labelled 0-500 m slider used for the distance/accuracy filters. Shows "Off" at 0 and the
 * unit-aware distance otherwise, and only commits on release so the setting isn't rewritten on
 * every drag frame.
 */
@Composable
private fun MetresFilterSliderRow(
    title: String,
    subtitle: String,
    valueMeters: Int,
    onCommit: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        var value by remember(valueMeters) { mutableFloatStateOf(valueMeters.toFloat()) }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
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
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_enable_supervised_lockout_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
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
