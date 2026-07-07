package com.locapeer.sharing

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.locapeer.supervised.SupervisionGate
import com.locapeer.supervised.SupervisionGateViewModel
import com.locapeer.ui.components.TimePickerDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    onNavigateBack: () -> Unit,
    vm: ScheduleViewModel = hiltViewModel()
) {
    val gateVm: SupervisionGateViewModel = hiltViewModel()
    val supervisedModeEnabled by gateVm.supervisedModeEnabled.collectAsState()
    val gateUnlockState by gateVm.unlockState.collectAsState()
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

    val rules by vm.rules.collectAsState()
    val title = if (vm.scope == "global") "Sharing Schedule"
                else "Schedule for ${vm.peerName}"

    var editingRule by remember { mutableStateOf<ScheduleRule?>(null) }
    var isNewRule by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editingRule = newScheduleRule()
                isNewRule = true
            }) {
                Icon(Icons.Default.Add, contentDescription = "Add rule")
            }
        }
    ) { padding ->
        if (rules.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.outlineVariant
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "No schedule rules",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (vm.scope == "global")
                        "Location is shared at all times. Add a rule to restrict when sharing is active."
                    else
                        "Location is always shared with ${vm.peerName}. Add a rule to restrict when.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Text(
                            "Active when any rule matches the current time. Empty = always on.",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                itemsIndexed(rules, key = { _, r -> r.id }) { index, rule ->
                    RuleCard(
                        rule = rule,
                        index = index,
                        onEdit = { editingRule = rule; isNewRule = false },
                        onDelete = { vm.saveRules(rules.filter { it.id != rule.id }) }
                    )
                }
                item { Spacer(Modifier.height(72.dp)) } // FAB clearance
            }
        }
    }

    editingRule?.let { rule ->
        RuleEditDialog(
            rule = rule,
            onRuleChanged = { editingRule = it },
            onConfirm = {
                val updated = if (isNewRule) rules + rule
                             else rules.map { if (it.id == rule.id) rule else it }
                vm.saveRules(updated)
                editingRule = null
            },
            onDismiss = { editingRule = null }
        )
    }
}

@Composable
private fun RuleCard(
    rule: ScheduleRule,
    index: Int,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val daysSummary = SharingSchedule.formatDays(rule.days)
    val overnight = rule.startMinute > rule.endMinute
    val timeSummary = if (rule.startMinute == 0 && rule.endMinute == 1439) "All day"
                      else "${SharingSchedule.formatTime(rule.startMinute)} - ${SharingSchedule.formatTime(rule.endMinute)}" +
                           if (overnight) " (+1 day)" else ""

    Card(
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    rule.label.ifBlank { "Rule ${index + 1}" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(daysSummary, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(timeSummary, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete rule",
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditDialog(
    rule: ScheduleRule,
    onRuleChanged: (ScheduleRule) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Rule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = rule.label,
                    onValueChange = { onRuleChanged(rule.copy(label = it)) },
                    label = { Text("Label (optional)") },
                    placeholder = { Text("e.g. Work hours, Weekends") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Active Days", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                DayPicker(
                    days = rule.days,
                    onDaysChanged = { onRuleChanged(rule.copy(days = it)) }
                )
                Text("Time Window", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { showStartPicker = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Start", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(SharingSchedule.formatTime(rule.startMinute),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold)
                        }
                    }
                    OutlinedButton(
                        onClick = { showEndPicker = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("End", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(SharingSchedule.formatTime(rule.endMinute),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                if (rule.startMinute > rule.endMinute) {
                    Text(
                        "Overnight: active from ${SharingSchedule.formatTime(rule.startMinute)} until ${SharingSchedule.formatTime(rule.endMinute)} next day",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = rule.days != 0) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )

    if (showStartPicker) {
        TimePickerDialog(
            initialMinute = rule.startMinute,
            title = "Start Time",
            onConfirm = { onRuleChanged(rule.copy(startMinute = it)); showStartPicker = false },
            onDismiss = { showStartPicker = false }
        )
    }
    if (showEndPicker) {
        TimePickerDialog(
            initialMinute = rule.endMinute,
            title = "End Time",
            onConfirm = { onRuleChanged(rule.copy(endMinute = it)); showEndPicker = false },
            onDismiss = { showEndPicker = false }
        )
    }
}

@Composable
fun DayPicker(days: Int, onDaysChanged: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        SharingSchedule.DAY_LABELS.forEachIndexed { index, label ->
            val isSelected = (days shr index) and 1 == 1
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = label.first().toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        val newDays = if (isSelected) days and (1 shl index).inv()
                                      else days or (1 shl index)
                        onDaysChanged(newDays)
                    },
                    label = { },
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape
                )
            }
        }
    }
}
