package com.locapeer.sharing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.locapeer.R
import com.locapeer.data.entity.PeerEntity
import com.locapeer.data.entity.PrecisionMode
import com.locapeer.data.entity.scheduleRules
import com.locapeer.proximity.ProximityScheduleDialog
import com.locapeer.sharing.toScheduleRules
import com.locapeer.supervised.SupervisionGate
import com.locapeer.supervised.SupervisionGateViewModel
import com.locapeer.ui.components.RetentionRow
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerSharingScreen(
    peerId: String,
    peerName: String,
    onNavigateBack: () -> Unit,
    onNavigateToMap: (Double, Double) -> Unit = { _, _ -> },
    onNavigateToSchedule: () -> Unit = {},
    onNavigateToGeofences: (String) -> Unit = {},
    onNavigateToHistory: (String) -> Unit = {},
    vm: PeerSharingViewModel = hiltViewModel()
) {
    val gateVm: SupervisionGateViewModel = hiltViewModel()
    val supervisedModeEnabled by gateVm.supervisedModeEnabled.collectAsStateWithLifecycle()
    val gateUnlockState by gateVm.unlockState.collectAsStateWithLifecycle()
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

    LaunchedEffect(peerId) { vm.init(peerId) }

    val state by vm.uiState.collectAsStateWithLifecycle()
    val cfg = state.config
    val sharingEnabled = cfg?.sharingEnabled ?: true
    val isPaused = !sharingEnabled
    val messagingEnabled = state.peer?.messagingEnabled ?: true
    val precisionMode = cfg?.precisionMode ?: PrecisionMode.EXACT.name
    val isSosContact = cfg?.isSosContact ?: false
    val isMySupervised = cfg?.isMySupervised ?: false
    val notifyOnMissedHeartbeat = cfg?.notifyOnMissedHeartbeat ?: false
    val scheduleRules = cfg?.scheduleRules() ?: emptyList()
    val retentionDaysLocation = cfg?.retentionDaysLocation ?: 30
    val retentionDaysMessages = cfg?.retentionDaysMessages ?: 0
    val proximityAlert = state.proximityAlert
    val purgeResult by vm.lastPurgeResult.collectAsStateWithLifecycle()
    val roleChangeResult by vm.roleChangeResult.collectAsStateWithLifecycle()
    val role = state.peer?.locationRole
    val tempShareEndsAt = cfg?.temporaryShareEndsAtEpochSeconds
    val nowSec by produceState(initialValue = System.currentTimeMillis() / 1000L) {
        while (true) {
            value = System.currentTimeMillis() / 1000L
            kotlinx.coroutines.delay(1000L)
        }
    }
    val tempShareActive = tempShareEndsAt?.takeIf { it > nowSec }

    var showPrecisionDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(roleChangeResult) {
        val msg = roleChangeResult ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        vm.clearRoleChangeResult()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.peer_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    state.heartbeat?.let { hb ->
                        IconButton(onClick = { onNavigateToMap(hb.lat, hb.lng) }) {
                            Icon(Icons.Default.LocationOn, contentDescription = stringResource(R.string.cd_show_on_map))
                        }
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

            // ── 1. Sharing Roles ─────────────────────────────────────────────
            item { SectionLabel(stringResource(R.string.peer_section_roles)) }
            item {
                SettingsCard {
                    val isSend = role == PeerEntity.ROLE_SEND || role == PeerEntity.ROLE_SEND_RECEIVE
                    val isReceive = role == PeerEntity.ROLE_RECEIVE || role == PeerEntity.ROLE_SEND_RECEIVE
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_share_location)) },
                        supportingContent = { Text(if (isSend) stringResource(R.string.peer_send_on, peerName) else stringResource(R.string.peer_send_off, peerName)) },
                        leadingContent = {
                            Icon(
                                Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = if (isSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingContent = {
                            Switch(checked = isSend, onCheckedChange = { vm.setSendRole(it) })
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.peer_receive_title, peerName)) },
                        supportingContent = {
                            Text(if (isReceive) stringResource(R.string.peer_receive_on, peerName)
                            else stringResource(R.string.peer_receive_off, peerName))
                        },
                        leadingContent = {
                            Icon(
                                Icons.Default.Visibility,
                                contentDescription = null,
                                tint = if (isReceive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingContent = {
                            if (!isReceive) {
                                OutlinedButton(
                                    onClick = { vm.requestLocationAccess() },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                ) { Text(stringResource(R.string.common_request), style = MaterialTheme.typography.labelMedium) }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }

            // ── 2. Privacy & Controls ────────────────────────────────────────
            item { SectionLabel(stringResource(R.string.peer_section_privacy)) }
            item {
                SettingsCard {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.peer_pause_sharing)) },
                        supportingContent = { Text(stringResource(R.string.peer_pause_sub, peerName)) },
                        leadingContent = { Icon(Icons.Default.PauseCircle, contentDescription = null, tint = if (isPaused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) },
                        trailingContent = {
                            Switch(checked = isPaused, onCheckedChange = { vm.setSharingEnabled(!it) })
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.peer_precision)) },
                        supportingContent = { Text(if (precisionMode == PrecisionMode.EXACT.name) stringResource(R.string.peer_precision_exact_sub) else stringResource(R.string.peer_precision_suburb_sub)) },
                        leadingContent = { Icon(if (precisionMode == PrecisionMode.EXACT.name) Icons.Default.GpsFixed else Icons.Default.LocationCity, contentDescription = null) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                        modifier = Modifier.clickable(enabled = sharingEnabled) { showPrecisionDialog = true },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_sharing_schedule)) },
                        supportingContent = {
                            Text(if (scheduleRules.isEmpty()) stringResource(R.string.peer_always_sharing)
                            else pluralStringResource(R.plurals.peer_active_rules, scheduleRules.size, scheduleRules.size))
                        },
                        leadingContent = { Icon(Icons.Default.Schedule, contentDescription = null) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                        modifier = Modifier.clickable(enabled = sharingEnabled) { onNavigateToSchedule() },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    if (tempShareActive != null) {
                        val endsAt = tempShareActive
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        Icons.Default.Timelapse,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        stringResource(R.string.settings_temporary_location_share),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    stringResource(
                                        R.string.peer_temp_share_active_label,
                                        peerName,
                                        com.locapeer.util.DisplayFormat.timeFormat().format(java.util.Date(endsAt * 1000L))
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Timer,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        stringResource(
                                            R.string.peer_temp_share_active_time_left,
                                            peerName,
                                            com.locapeer.util.DisplayFormat.humanizeRemaining(endsAt - nowSec)
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(Modifier.height(16.dp))
                                Button(
                                    onClick = { vm.clearTemporaryShare() },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                                    ),
                                    contentPadding = PaddingValues(vertical = 8.dp)
                                ) {
                                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.peer_temp_share_stop), fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    } else if (sharingEnabled) {
                        Column(modifier = Modifier.padding(bottom = 16.dp)) {
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.settings_temporary_location_share)) },
                                supportingContent = { Text(stringResource(R.string.peer_temp_share_subtitle)) },
                                leadingContent = { Icon(Icons.Default.Timer, contentDescription = null) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val options = listOf(
                                    15 to stringResource(R.string.peer_temp_share_chip_15m),
                                    60 to stringResource(R.string.peer_temp_share_chip_1h),
                                    180 to stringResource(R.string.peer_temp_share_chip_3h),
                                    360 to stringResource(R.string.peer_temp_share_chip_6h),
                                    720 to stringResource(R.string.peer_temp_share_chip_12h),
                                    minutesUntilTomorrow8am() to stringResource(R.string.peer_temp_share_chip_until_tomorrow)
                                )
                                options.forEach { (mins, label) ->
                                    SuggestionChip(
                                        onClick = { vm.setTemporaryShare(mins) },
                                        label = { Text(label) },
                                        icon = { Icon(Icons.Default.Timer, null, modifier = Modifier.size(18.dp)) },
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── 3. Alerts & Activity ─────────────────────────────────────────
            item { SectionLabel(stringResource(R.string.peer_section_alerts)) }
            item {
                SettingsCard {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.peer_sos_contact)) },
                        supportingContent = { Text(stringResource(R.string.peer_sos_sub)) },
                        leadingContent = { Icon(Icons.Default.Warning, contentDescription = null, tint = if (isSosContact) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) },
                        trailingContent = {
                            Switch(checked = isSosContact, onCheckedChange = { vm.setSosContact(it) })
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    val receivesLocation = role == PeerEntity.ROLE_RECEIVE || role == PeerEntity.ROLE_SEND_RECEIVE
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.peer_missed_alert)) },
                        supportingContent = {
                            Text(
                                if (receivesLocation) stringResource(R.string.peer_missed_on, peerName)
                                else stringResource(R.string.peer_requires_access, peerName)
                            )
                        },
                        leadingContent = {
                            Icon(
                                Icons.Default.NotificationsActive,
                                contentDescription = null,
                                tint = if (notifyOnMissedHeartbeat && receivesLocation) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = notifyOnMissedHeartbeat,
                                onCheckedChange = { vm.setNotifyOnMissedHeartbeat(it) },
                                enabled = receivesLocation
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))

                    val alertActive = proximityAlert?.active ?: false
                    var showProximityScheduleDialog by remember { mutableStateOf(false) }

                    ListItem(
                        headlineContent = { Text(stringResource(R.string.peer_proximity_alert)) },
                        supportingContent = { Text(stringResource(R.string.peer_proximity_sub, peerName)) },
                        leadingContent = { Icon(Icons.Default.NearMe, contentDescription = null, tint = if (alertActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                        trailingContent = {
                            Switch(checked = alertActive, onCheckedChange = { vm.setProximityAlertEnabled(it) }, enabled = receivesLocation)
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )

                    if (alertActive && receivesLocation) {
                        val radius = proximityAlert?.radiusMetres ?: 500
                        val proxRules = remember(proximityAlert?.scheduleRules) {
                            proximityAlert?.scheduleRules?.toScheduleRules() ?: emptyList()
                        }
                        val hasProxSchedule = proxRules.isNotEmpty()

                        Column(modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 12.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.peer_alert_radius), style = MaterialTheme.typography.bodySmall)
                                Text(com.locapeer.util.DisplayFormat.distanceValue(radius.toDouble()), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                            }

                            // Limited options slider for better UX
                            val options = listOf(100, 250, 500, 1000, 2000, 5000, 10000)
                            val currentIndex = options.indexOf(radius).coerceAtLeast(0)
                            var sliderIdx by remember(radius) { mutableFloatStateOf(currentIndex.toFloat()) }

                            Slider(
                                value = sliderIdx,
                                onValueChange = { sliderIdx = it },
                                onValueChangeFinished = {
                                    vm.setProximityAlertRadius(options[sliderIdx.roundToInt()])
                                },
                                valueRange = 0f..(options.size - 1).toFloat(),
                                steps = options.size - 2
                            )

                            Spacer(Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.peer_alert_schedule), style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        if (hasProxSchedule) pluralStringResource(R.plurals.peer_proximity_rules, proxRules.size, proxRules.size) else stringResource(R.string.settings_always_on),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (hasProxSchedule) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(
                                    onClick = { showProximityScheduleDialog = true },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text(stringResource(R.string.common_edit), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }

                        if (showProximityScheduleDialog) {
                            ProximityScheduleDialog(
                                initialRules = proxRules,
                                onDismiss = { showProximityScheduleDialog = false },
                                onSave = { rules ->
                                    vm.setProximityAlertSchedule(rules)
                                    showProximityScheduleDialog = false
                                }
                            )
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_geofences)) },
                        supportingContent = { Text(stringResource(R.string.peer_geofences_sub, peerName)) },
                        leadingContent = { Icon(Icons.Default.Fence, contentDescription = null) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                        modifier = Modifier.clickable(enabled = receivesLocation) { onNavigateToGeofences(peerId) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.peer_supervise)) },
                        supportingContent = { Text(if (isMySupervised) stringResource(R.string.peer_supervise_on, peerName) else stringResource(R.string.peer_supervise_off, peerName)) },
                        leadingContent = {
                            Icon(
                                Icons.Default.Security,
                                contentDescription = null,
                                tint = if (isMySupervised) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingContent = {
                            Switch(checked = isMySupervised, onCheckedChange = { vm.setIsMySupervised(it) })
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }

            // ── 4. Messaging ─────────────────────────────────────────────────
            item { SectionLabel(stringResource(R.string.incoming_section_messaging)) }
            item {
                SettingsCard {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.incoming_allow_messages)) },
                        supportingContent = { Text(stringResource(R.string.peer_messaging_sub, peerName)) },
                        leadingContent = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, tint = if (messagingEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                        trailingContent = {
                            Switch(checked = messagingEnabled, onCheckedChange = { vm.setMessagingEnabled(it) })
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }

            // ── 5. History ───────────────────────────────────────────────────
            item { SectionLabel(stringResource(R.string.peer_section_history)) }
            item {
                SettingsCard {
                    val receivesLocation = role == PeerEntity.ROLE_RECEIVE || role == PeerEntity.ROLE_SEND_RECEIVE
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.peer_location_history)) },
                        supportingContent = { Text(stringResource(R.string.peer_location_history_sub, peerName)) },
                        leadingContent = { Icon(Icons.Default.History, contentDescription = null) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                        modifier = Modifier.clickable(enabled = receivesLocation) { onNavigateToHistory(peerId) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }

            // ── 6. Retention ─────────────────────────────────────────────────
            item { SectionLabel(stringResource(R.string.peer_section_retention)) }
            item {
                SettingsCard {
                    purgeResult?.let { result ->
                        Text(
                            result.message,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (result.isError) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.primary
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                    RetentionRow(
                        icon = Icons.Default.LocationOff,
                        title = stringResource(R.string.settings_retention_location),
                        subtitle = stringResource(R.string.peer_retention_location_sub, peerName),
                        selected = retentionDaysLocation,
                        onSelected = { vm.setRetentionDaysLocation(it) },
                        purgeLabel = stringResource(R.string.peer_purge_location, peerName),
                        onPurge = { vm.sendLocationPurgeNow() }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    RetentionRow(
                        icon = Icons.Default.DeleteSweep,
                        title = stringResource(R.string.settings_retention_messages),
                        subtitle = stringResource(R.string.peer_retention_messages_sub, peerName),
                        selected = retentionDaysMessages,
                        onSelected = { vm.setRetentionDaysMessages(it) },
                        purgeLabel = stringResource(R.string.peer_purge_messages, peerName),
                        onPurge = { vm.sendMessagePurgeNow() }
                    )
                }
            }
        }
    }

    if (showPrecisionDialog) {
        AlertDialog(
            onDismissRequest = { showPrecisionDialog = false },
            title = { Text(stringResource(R.string.peer_precision)) },
            text = {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { vm.setPrecisionMode(PrecisionMode.EXACT); showPrecisionDialog = false }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = precisionMode == PrecisionMode.EXACT.name, onClick = { vm.setPrecisionMode(PrecisionMode.EXACT); showPrecisionDialog = false })
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.peer_precision_exact), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.peer_precision_exact_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { vm.setPrecisionMode(PrecisionMode.SUBURB); showPrecisionDialog = false }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = precisionMode == PrecisionMode.SUBURB.name, onClick = { vm.setPrecisionMode(PrecisionMode.SUBURB); showPrecisionDialog = false })
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.peer_precision_suburb), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.peer_precision_suburb_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showPrecisionDialog = false }) { Text(stringResource(R.string.common_cancel)) } }
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

@Composable
private fun TempShareChip(label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

/** Minutes from "now" until 08:00 the next day. Used by the "Until tomorrow 8am" chip. */
private fun minutesUntilTomorrow8am(): Int {
    val cal = java.util.Calendar.getInstance()
    cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
    cal.set(java.util.Calendar.HOUR_OF_DAY, 8)
    cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    val ms = cal.timeInMillis - System.currentTimeMillis()
    return (ms / 60_000L).coerceAtLeast(1L).toInt()
}
