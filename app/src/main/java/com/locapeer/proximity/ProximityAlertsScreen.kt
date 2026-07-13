package com.locapeer.proximity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import com.locapeer.ui.theme.locaPeerTopAppBarColors
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.locapeer.R
import com.locapeer.sharing.RuleEditDialog
import com.locapeer.sharing.ScheduleRule
import com.locapeer.sharing.SharingSchedule
import com.locapeer.sharing.newScheduleRule
import com.locapeer.sharing.toScheduleRules
import com.locapeer.ui.components.EmptyState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

private val RADIUS_OPTIONS = listOf(100, 250, 500, 1000, 2000, 5000, 10000)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProximityAlertsScreen(
    onNavigateBack: () -> Unit,
    vm: ProximityViewModel = hiltViewModel()
) {
    val peerStates by vm.peerStates.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                colors = locaPeerTopAppBarColors(),
                title = { Text(stringResource(R.string.prox_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                }
            )
        }
    ) { padding ->
        if (peerStates.isEmpty()) {
            EmptyState(
                icon = Icons.Default.NearMe,
                title = stringResource(R.string.prox_empty_title),
                subtitle = stringResource(R.string.prox_empty_sub),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        stringResource(R.string.prox_intro),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                }
                items(peerStates, key = { it.peer.deviceId }) { state ->
                    ProximityPeerCard(
                        state = state,
                        onToggle = { enabled ->
                            vm.setActive(
                                state.peer.deviceId,
                                enabled,
                                state.alert?.radiusMetres ?: 500,
                                state.alert?.scheduleRules ?: "[]"
                            )
                        },
                        onRadiusChanged = { vm.setRadius(state.peer.deviceId, it) },
                        onScheduleChanged = { vm.setScheduleRules(state.peer.deviceId, it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProximityPeerCard(
    state: PeerProximityState,
    onToggle: (Boolean) -> Unit,
    onRadiusChanged: (Int) -> Unit,
    onScheduleChanged: (String) -> Unit
) {
    val isEnabled = state.alert?.active == true
    val savedRadius = state.alert?.radiusMetres ?: 500
    val rules = remember(state.alert?.scheduleRules) {
        state.alert?.scheduleRules?.toScheduleRules() ?: emptyList()
    }
    val hasSchedule = rules.isNotEmpty()

    var showScheduleDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (isEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.primaryContainer
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        state.peer.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isEnabled) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        state.peer.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            if (isEnabled) stringResource(R.string.prox_alert_within, formatRadius(savedRadius))
                            else stringResource(R.string.prox_alerts_off),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (isEnabled && hasSchedule) {
                            Icon(Icons.Default.Schedule, null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                Switch(
                    checked = isEnabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.scale(0.8f)
                )
            }

            if (isEnabled) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.prox_alert_radius), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text(
                        formatRadius(savedRadius),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                
                // Limited options slider
                val initialIndex = RADIUS_OPTIONS.indexOf(savedRadius).coerceAtLeast(0)
                var sliderIndex by remember(savedRadius) { mutableFloatStateOf(initialIndex.toFloat()) }
                
                Slider(
                    value = sliderIndex,
                    onValueChange = { sliderIndex = it },
                    onValueChangeFinished = { 
                        onRadiusChanged(RADIUS_OPTIONS[sliderIndex.roundToInt()])
                    },
                    valueRange = 0f..(RADIUS_OPTIONS.size - 1).toFloat(),
                    steps = RADIUS_OPTIONS.size - 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("100m", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("10km", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.peer_alert_schedule), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Text(
                            if (hasSchedule) pluralStringResource(R.plurals.peer_proximity_rules, rules.size, rules.size) else stringResource(R.string.settings_always_on),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = { showScheduleDialog = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(stringResource(R.string.prox_edit_schedule), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }

    if (showScheduleDialog) {
        ProximityScheduleDialog(
            initialRules = rules,
            onDismiss = { showScheduleDialog = false },
            onSave = { 
                onScheduleChanged(Json.encodeToString(it))
                showScheduleDialog = false
            }
        )
    }
}

@Composable
fun ProximityScheduleDialog(
    initialRules: List<ScheduleRule>,
    onDismiss: () -> Unit,
    onSave: (List<ScheduleRule>) -> Unit
) {
    var scheduleRules by remember { mutableStateOf(initialRules) }
    var editingRule by remember { mutableStateOf<ScheduleRule?>(null) }
    var isNewRule by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.peer_alert_schedule)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.prox_rules), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = { editingRule = newScheduleRule(); isNewRule = true }) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.geo_add_rule))
                    }
                }

                if (scheduleRules.isEmpty()) {
                    Text(
                        stringResource(R.string.geo_alerts_all_times),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    scheduleRules.forEach { rule ->
                        Card(
                            onClick = { editingRule = rule; isNewRule = false },
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    if (rule.label.isNotBlank()) {
                                        Text(rule.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                    }
                                    Text(
                                        "${SharingSchedule.formatDays(rule.days)} • ${SharingSchedule.formatTime(rule.startMinute)} - ${SharingSchedule.formatTime(rule.endMinute)}",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                IconButton(onClick = { scheduleRules = scheduleRules.filter { it.id != rule.id } }) {
                                    Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(scheduleRules) }) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )

    editingRule?.let { rule ->
        RuleEditDialog(
            rule = rule,
            onRuleChanged = { editingRule = it },
            onConfirm = {
                scheduleRules = if (isNewRule) scheduleRules + rule
                               else scheduleRules.map { if (it.id == rule.id) rule else it }
                editingRule = null
            },
            onDismiss = { editingRule = null }
        )
    }
}

private fun formatRadius(metres: Int): String =
    com.locapeer.util.DisplayFormat.distanceValue(metres.toDouble())
