package com.locapeer.sharing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.locapeer.data.entity.PrecisionMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerSharingScreen(
    peerId: String,
    peerName: String,
    onNavigateBack: () -> Unit,
    vm: PeerSharingViewModel = hiltViewModel()
) {
    LaunchedEffect(peerId) { vm.init(peerId) }

    val state by vm.uiState.collectAsState()
    val cfg = state.config
    val sharingEnabled = cfg?.sharingEnabled ?: true
    val precisionMode = cfg?.precisionMode ?: PrecisionMode.EXACT.name
    val scheduleEnabled = cfg?.scheduleEnabled ?: false
    val scheduleDays = cfg?.scheduleDays ?: 0b1111111
    val scheduleStart = cfg?.scheduleStartMinute ?: 0
    val scheduleEnd = cfg?.scheduleEndMinute ?: 1439

    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sharing with $peerName") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // Peer header
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                peerName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Column {
                            Text(peerName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (sharingEnabled) "Sharing your location" else "Sharing paused",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (sharingEnabled) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // Enable / disable sharing with this person
            item {
                SharingCard(title = "Location Sharing") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Share with $peerName", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "When off, ${peerName.split(" ").first()} receives no location updates from you",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = sharingEnabled,
                            onCheckedChange = { vm.setSharingEnabled(it) }
                        )
                    }
                }
            }

            // Precision
            item {
                SharingCard(title = "Location Precision") {
                    Text(
                        "Choose how precisely your location is shared with this person.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    PrecisionOption(
                        icon = Icons.Default.GpsFixed,
                        title = "Exact GPS",
                        subtitle = "Precise coordinates - within a few metres",
                        selected = precisionMode == PrecisionMode.EXACT.name,
                        enabled = sharingEnabled,
                        onClick = { vm.setPrecisionMode(PrecisionMode.EXACT) }
                    )
                    Spacer(Modifier.height(8.dp))
                    PrecisionOption(
                        icon = Icons.Default.LocationCity,
                        title = "Suburb (~1 km)",
                        subtitle = "Neighbourhood-level only - hides your exact spot",
                        selected = precisionMode == PrecisionMode.SUBURB.name,
                        enabled = sharingEnabled,
                        onClick = { vm.setPrecisionMode(PrecisionMode.SUBURB) }
                    )
                }
            }

            // Per-peer schedule
            item {
                SharingCard(title = "Sharing Schedule") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Enable schedule", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Only share during selected times",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = scheduleEnabled,
                            onCheckedChange = { vm.setScheduleEnabled(it) },
                            enabled = sharingEnabled
                        )
                    }

                    if (scheduleEnabled && sharingEnabled) {
                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.height(16.dp))

                        Text(
                            "Active days",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(8.dp))
                        DayPicker(
                            days = scheduleDays,
                            onDaysChanged = { vm.setScheduleDays(it) }
                        )

                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Time window",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            TimeButton(
                                label = "Start",
                                time = SharingSchedule.formatTime(scheduleStart),
                                modifier = Modifier.weight(1f),
                                onClick = { showStartPicker = true }
                            )
                            TimeButton(
                                label = "End",
                                time = SharingSchedule.formatTime(scheduleEnd),
                                modifier = Modifier.weight(1f),
                                onClick = { showEndPicker = true }
                            )
                        }
                        if (scheduleStart > scheduleEnd) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Overnight window: ${SharingSchedule.formatTime(scheduleStart)} – ${SharingSchedule.formatTime(scheduleEnd)} (+1 day)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }

    if (showStartPicker) {
        TimePickerDialog(
            initialMinute = scheduleStart,
            title = "Start sharing at",
            onConfirm = { vm.setScheduleStart(it); showStartPicker = false },
            onDismiss = { showStartPicker = false }
        )
    }
    if (showEndPicker) {
        TimePickerDialog(
            initialMinute = scheduleEnd,
            title = "Stop sharing at",
            onConfirm = { vm.setScheduleEnd(it); showEndPicker = false },
            onDismiss = { showEndPicker = false }
        )
    }
}

@Composable
private fun PrecisionOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val containerColor = if (selected)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick,
                enabled = enabled
            )
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun DayPicker(days: Int, onDaysChanged: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SharingSchedule.DAY_LABELS.forEachIndexed { index, label ->
            val selected = (days shr index) and 1 == 1
            FilterChip(
                selected = selected,
                onClick = { onDaysChanged(days xor (1 shl index)) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun TimeButton(label: String, time: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(time, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(
    initialMinute: Int,
    title: String,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberTimePickerState(
        initialHour = initialMinute / 60,
        initialMinute = initialMinute % 60,
        is24Hour = true
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                TimePicker(state = state)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun SharingCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}
