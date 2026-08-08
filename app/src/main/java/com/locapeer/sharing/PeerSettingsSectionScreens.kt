package com.locapeer.sharing

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.locapeer.R
import com.locapeer.data.entity.PeerEntity
import com.locapeer.data.entity.PrecisionMode
import com.locapeer.data.entity.scheduleRules
import com.locapeer.proximity.ProximityScheduleDialog
import com.locapeer.supervised.SupervisionGate
import com.locapeer.supervised.SupervisionGateViewModel
import com.locapeer.ui.components.CardDivider
import com.locapeer.ui.components.ChoiceOption
import com.locapeer.ui.components.RetentionRow
import com.locapeer.ui.components.SettingsCard
import com.locapeer.ui.components.SettingsRow
import com.locapeer.ui.components.SingleChoiceDialog
import com.locapeer.ui.components.SwitchRow
import com.locapeer.ui.theme.locaPeerTopAppBarColors
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeerSharingSubScreen(
    title: String,
    onNavigateBack: () -> Unit,
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val gateVm: SupervisionGateViewModel = hiltViewModel()
    val supervisedModeEnabled by gateVm.supervisedModeEnabled.collectAsStateWithLifecycle()
    val gateUnlockState by gateVm.unlockState.collectAsStateWithLifecycle()
    var sessionUnlocked by remember { mutableStateOf(value = false) }

    if (supervisedModeEnabled && !sessionUnlocked) {
        SupervisionGate(
            unlockState = gateUnlockState,
            onRequestAccess = gateVm::requestAccess,
            onReset = gateVm::reset,
            onNavigateBack = onNavigateBack,
        ) { sessionUnlocked = true }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                colors = locaPeerTopAppBarColors(),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
        snackbarHost = { snackbarHost() },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

/**
 * Prominent card for an in-flight temporary location share: end time, remaining
 * duration and a stop button. Shared by the peer landing screen and the Sharing
 * Controls sub-screen so the two stay visually identical.
 */
@Composable
fun ActiveTempShareCard(
    peerName: String,
    endsAtEpochSeconds: Long,
    nowEpochSeconds: Long,
    onStop: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Timelapse, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.settings_temporary_location_share), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.peer_temp_share_active_label, peerName, com.locapeer.util.DisplayFormat.timeFormat().format(java.util.Date(endsAtEpochSeconds * 1000L))),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Timer, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.peer_temp_share_active_time_left, peerName, com.locapeer.util.DisplayFormat.humanizeRemaining(endsAtEpochSeconds - nowEpochSeconds)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
            ) {
                Icon(Icons.Default.Stop, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.peer_temp_share_stop))
            }
        }
    }
}

// ─── Sharing Controls ────────────────────────────────────────────────────────

@Composable
fun PeerSharingControlsScreen(
    peerId: String,
    peerName: String,
    onNavigateBack: () -> Unit,
    onNavigateToSchedule: () -> Unit,
    vm: PeerSharingViewModel = hiltViewModel(),
) {
    LaunchedEffect(peerId) { vm.init(peerId) }
    val state by vm.uiState.collectAsStateWithLifecycle()
    val cfg = state.config
    val sharingEnabled = cfg?.sharingEnabled ?: true
    val isPaused = !sharingEnabled
    val supervisorLocked = state.supervisorLocked
    val precisionMode = cfg?.precisionMode ?: PrecisionMode.EXACT.name
    val scheduleRules = cfg?.scheduleRules() ?: emptyList()
    val role = state.peer?.locationRole
    val tempShareEndsAt = cfg?.temporaryShareEndsAtEpochSeconds
    // Tick the clock only while a temp share is pending; ticking unconditionally would
    // recompose the whole screen every second even when there's nothing to count down.
    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000L) }
    LaunchedEffect(tempShareEndsAt) {
        val endsAt = tempShareEndsAt ?: return@LaunchedEffect
        while (true) {
            val wallSec = System.currentTimeMillis() / 1000L
            nowSec = wallSec
            if (wallSec >= endsAt) break
            kotlinx.coroutines.delay(1.seconds)
        }
    }
    val tempShareActive = tempShareEndsAt?.takeIf { it > nowSec }

    var showPrecisionDialog by remember { mutableStateOf(value = false) }

    // The "request sent" confirmation is transient state on this screen's own ViewModel
    // instance (each nav destination gets a fresh PeerSharingViewModel), so it is consumed
    // here rather than on the landing screen.
    val snackbarHostState = remember { SnackbarHostState() }
    val roleChangeResult by vm.roleChangeResult.collectAsStateWithLifecycle()
    LaunchedEffect(roleChangeResult) {
        val msg = roleChangeResult ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        vm.clearRoleChangeResult()
    }

    PeerSharingSubScreen(
        stringResource(R.string.peer_category_sharing_title),
        onNavigateBack,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) {
        SettingsCard(
            headerIcon = Icons.Default.LocationOn,
            headerTitle = stringResource(R.string.peer_section_roles)
        ) {
            val isSend = role == PeerEntity.ROLE_SEND || role == PeerEntity.ROLE_SEND_RECEIVE
            val isReceive = role == PeerEntity.ROLE_RECEIVE || role == PeerEntity.ROLE_SEND_RECEIVE

            SwitchRow(
                title = stringResource(R.string.settings_share_location),
                subtitle = if (isSend) stringResource(R.string.peer_send_on, peerName) else stringResource(R.string.peer_send_off, peerName),
                checked = isSend,
                onCheckedChange = { vm.setSendRole(it) },
                enabled = !supervisorLocked
            )
            CardDivider()
            SettingsRow(
                title = stringResource(R.string.peer_receive_title, peerName),
                subtitle = if (isReceive) stringResource(R.string.peer_receive_on, peerName)
                else stringResource(R.string.peer_receive_off, peerName),
                action = if (!isReceive) {
                    {
                        OutlinedButton(
                            onClick = { vm.requestLocationAccess() },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) { Text(stringResource(R.string.common_request), style = MaterialTheme.typography.labelMedium) }
                    }
                } else null
            )
        }

        SettingsCard(
            headerIcon = Icons.Default.Settings,
            headerTitle = stringResource(R.string.peer_section_privacy)
        ) {
            SwitchRow(
                title = stringResource(R.string.peer_pause_sharing),
                subtitle = stringResource(R.string.peer_pause_sub, peerName),
                checked = isPaused,
                onCheckedChange = { vm.setSharingEnabled(!it) },
                enabled = !supervisorLocked
            )
            if (supervisorLocked) {
                Text(
                    stringResource(R.string.peer_sharing_supervisor_locked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }
            CardDivider()
            SettingsRow(
                title = stringResource(R.string.peer_precision),
                value = if (precisionMode == PrecisionMode.EXACT.name) stringResource(R.string.peer_precision_exact) else stringResource(R.string.peer_precision_suburb),
                enabled = sharingEnabled,
                onClick = { showPrecisionDialog = true }
            )
            CardDivider()
            SettingsRow(
                title = stringResource(R.string.settings_sharing_schedule),
                value = if (scheduleRules.isEmpty()) stringResource(R.string.peer_always_sharing)
                else pluralStringResource(R.plurals.peer_active_rules, scheduleRules.size, scheduleRules.size),
                enabled = sharingEnabled,
                onClick = onNavigateToSchedule
            )
        }

        if (tempShareActive != null) {
            ActiveTempShareCard(
                peerName = peerName,
                endsAtEpochSeconds = tempShareActive,
                nowEpochSeconds = nowSec,
                onStop = { vm.clearTemporaryShare() },
            )
        } else if (sharingEnabled) {
            SettingsCard(
                headerIcon = Icons.Default.Timer,
                headerTitle = stringResource(R.string.settings_temporary_location_share)
            ) {
                Text(
                    stringResource(R.string.peer_temp_share_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
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

    if (showPrecisionDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.peer_precision),
            options = listOf(
                ChoiceOption(
                    value = PrecisionMode.EXACT,
                    label = stringResource(R.string.peer_precision_exact),
                    description = stringResource(R.string.peer_precision_exact_desc)
                ),
                ChoiceOption(
                    value = PrecisionMode.SUBURB,
                    label = stringResource(R.string.peer_precision_suburb),
                    description = stringResource(R.string.peer_precision_suburb_desc)
                ),
            ),
            isSelected = { precisionMode == it.name },
            onSelected = { vm.setPrecisionMode(it); showPrecisionDialog = false },
            onDismiss = { showPrecisionDialog = false }
        )
    }
}

// ─── Safety & Alerts ─────────────────────────────────────────────────────────

@Composable
fun PeerSafetyAlertsScreen(
    peerId: String,
    peerName: String,
    onNavigateBack: () -> Unit,
    vm: PeerSharingViewModel = hiltViewModel(),
) {
    LaunchedEffect(peerId) { vm.init(peerId) }
    val state by vm.uiState.collectAsStateWithLifecycle()
    val cfg = state.config
    val isSosContact = cfg?.isSosContact ?: false
    val isMySupervised = cfg?.isMySupervised ?: false
    val notifyOnMissedHeartbeat = cfg?.notifyOnMissedHeartbeat ?: false
    val role = state.peer?.locationRole
    val receivesLocation = role == PeerEntity.ROLE_RECEIVE || role == PeerEntity.ROLE_SEND_RECEIVE
    val proximityAlert = state.proximityAlert

    PeerSharingSubScreen(stringResource(R.string.peer_category_safety_title), onNavigateBack) {
        SettingsCard(
            headerIcon = Icons.Default.Warning,
            headerTitle = stringResource(R.string.peer_section_alerts)
        ) {
            SwitchRow(
                title = stringResource(R.string.peer_sos_contact),
                subtitle = stringResource(R.string.peer_sos_sub),
                checked = isSosContact,
                onCheckedChange = { vm.setSosContact(it) }
            )
            CardDivider()
            SwitchRow(
                title = stringResource(R.string.peer_missed_alert),
                subtitle = if (receivesLocation) stringResource(R.string.peer_missed_on, peerName) else stringResource(R.string.peer_requires_access, peerName),
                checked = notifyOnMissedHeartbeat,
                onCheckedChange = { vm.setNotifyOnMissedHeartbeat(it) },
                enabled = receivesLocation && !isMySupervised
            )
        }

        SettingsCard(
            headerIcon = Icons.Default.NearMe,
            headerTitle = stringResource(R.string.peer_proximity_alert)
        ) {
            // takeIf keeps the non-null entity in scope below without relying on a smart-cast
            // implied by a separate Boolean (which breaks the moment that Boolean becomes a var).
            val activeAlert = proximityAlert?.takeIf { it.active }
            var showProximityScheduleDialog by remember { mutableStateOf(value = false) }

            SwitchRow(
                title = stringResource(R.string.peer_proximity_alert),
                subtitle = stringResource(R.string.peer_proximity_sub, peerName),
                checked = activeAlert != null,
                onCheckedChange = { vm.setProximityAlertEnabled(it) },
                enabled = receivesLocation
            )

            if (activeAlert != null && receivesLocation) {
                CardDivider()
                val radius = activeAlert.radiusMetres
                val proxRules = remember(activeAlert.scheduleRules) {
                    activeAlert.scheduleRules.toScheduleRules()
                }
                val hasProxSchedule = proxRules.isNotEmpty()

                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.peer_alert_radius), style = MaterialTheme.typography.bodySmall)
                        Text(com.locapeer.util.DisplayFormat.distanceValue(radius.toDouble()), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    }
                    val options = listOf(100, 250, 500, 1000, 2000, 5000, 10000)
                    val currentIndex = options.indexOf(radius).coerceAtLeast(0)
                    var sliderIdx by remember(radius) { mutableFloatStateOf(currentIndex.toFloat()) }
                    Slider(
                        value = sliderIdx,
                        onValueChange = { sliderIdx = it },
                        onValueChangeFinished = { vm.setProximityAlertRadius(options[sliderIdx.roundToInt()]) },
                        valueRange = 0f..(options.size - 1).toFloat(),
                        steps = options.size - 2
                    )
                    Spacer(Modifier.height(8.dp))
                    SettingsRow(
                        title = stringResource(R.string.peer_alert_schedule),
                        value = if (hasProxSchedule) pluralStringResource(R.plurals.peer_proximity_rules, proxRules.size, proxRules.size) else stringResource(R.string.settings_always_on),
                        onClick = { showProximityScheduleDialog = true }
                    )
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
        }

        SettingsCard(
            headerIcon = Icons.Default.Security,
            headerTitle = stringResource(R.string.peer_supervise)
        ) {
            SwitchRow(
                title = stringResource(R.string.peer_supervise),
                subtitle = if (isMySupervised) stringResource(R.string.peer_supervise_on, peerName) else stringResource(R.string.peer_supervise_off, peerName),
                checked = isMySupervised,
                onCheckedChange = { vm.setIsMySupervised(it) }
            )
        }
    }
}

// ─── Zones & History ─────────────────────────────────────────────────────────

@Composable
fun PeerZonesHistoryScreen(
    peerId: String,
    peerName: String,
    onNavigateBack: () -> Unit,
    onNavigateToGeofences: (String) -> Unit,
    onNavigateToHistory: (String) -> Unit,
    vm: PeerSharingViewModel = hiltViewModel(),
) {
    LaunchedEffect(peerId) { vm.init(peerId) }
    val state by vm.uiState.collectAsStateWithLifecycle()
    val role = state.peer?.locationRole
    val receivesLocation = role == PeerEntity.ROLE_RECEIVE || role == PeerEntity.ROLE_SEND_RECEIVE

    PeerSharingSubScreen(stringResource(R.string.peer_category_history_title), onNavigateBack) {
        SettingsCard(
            headerIcon = Icons.Default.Fence,
            headerTitle = stringResource(R.string.settings_geofences)
        ) {
            SettingsRow(
                title = stringResource(R.string.settings_geofences),
                subtitle = stringResource(R.string.peer_geofences_sub, peerName),
                enabled = receivesLocation,
                onClick = { onNavigateToGeofences(peerId) }
            )
        }

        SettingsCard(
            headerIcon = Icons.Default.History,
            headerTitle = stringResource(R.string.peer_location_history)
        ) {
            SettingsRow(
                title = stringResource(R.string.peer_location_history),
                subtitle = stringResource(R.string.peer_location_history_sub, peerName),
                enabled = receivesLocation,
                onClick = { onNavigateToHistory(peerId) }
            )
        }
    }
}

// ─── Messaging ───────────────────────────────────────────────────────────────

@Composable
fun PeerMessagingScreen(
    peerId: String,
    peerName: String,
    onNavigateBack: () -> Unit,
    vm: PeerSharingViewModel = hiltViewModel(),
) {
    LaunchedEffect(peerId) { vm.init(peerId) }
    val state by vm.uiState.collectAsStateWithLifecycle()
    val messagingEnabled = state.peer?.messagingEnabled ?: true

    PeerSharingSubScreen(stringResource(R.string.peer_category_messaging_title), onNavigateBack) {
        SettingsCard(
            headerIcon = Icons.AutoMirrored.Filled.Chat,
            headerTitle = stringResource(R.string.incoming_section_messaging)
        ) {
            SwitchRow(
                title = stringResource(R.string.incoming_allow_messages),
                subtitle = stringResource(R.string.peer_messaging_sub, peerName),
                checked = messagingEnabled,
                onCheckedChange = { vm.setMessagingEnabled(it) }
            )
        }
    }
}

// ─── Data Retention ──────────────────────────────────────────────────────────

@Composable
fun PeerRetentionScreen(
    peerId: String,
    peerName: String,
    onNavigateBack: () -> Unit,
    vm: PeerSharingViewModel = hiltViewModel(),
) {
    LaunchedEffect(peerId) { vm.init(peerId) }
    val state by vm.uiState.collectAsStateWithLifecycle()
    val cfg = state.config
    val retentionDaysLocation = cfg?.retentionDaysLocation ?: 30
    val retentionDaysMessages = cfg?.retentionDaysMessages ?: 0
    val purgeResult by vm.lastPurgeResult.collectAsStateWithLifecycle()

    PeerSharingSubScreen(stringResource(R.string.peer_category_retention_title), onNavigateBack) {
        SettingsCard(
            headerIcon = Icons.Default.DeleteSweep,
            headerTitle = stringResource(R.string.peer_section_retention)
        ) {
            purgeResult?.let { result ->
                Text(
                    result.message,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (result.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
                CardDivider()
            }
            RetentionRow(
                title = stringResource(R.string.settings_retention_location),
                subtitle = stringResource(R.string.peer_retention_location_sub, peerName),
                selected = retentionDaysLocation,
                onSelected = { vm.setRetentionDaysLocation(it) },
                purgeLabel = stringResource(R.string.peer_purge_location, peerName),
                onPurge = { vm.sendLocationPurgeNow() }
            )
            CardDivider()
            RetentionRow(
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

private fun minutesUntilTomorrow8am(): Int {
    val now = java.time.ZonedDateTime.now()
    val tomorrow8am = now.toLocalDate().plusDays(1).atTime(8, 0).atZone(now.zone)
    return java.time.Duration.between(now, tomorrow8am).toMinutes().coerceAtLeast(1L).toInt()
}
