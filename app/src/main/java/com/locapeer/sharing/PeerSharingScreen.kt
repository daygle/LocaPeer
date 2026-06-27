package com.locapeer.sharing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.locapeer.data.entity.PrecisionMode
import com.locapeer.data.entity.scheduleRules

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerSharingScreen(
    peerId: String,
    peerName: String,
    onNavigateBack: () -> Unit,
    onNavigateToSchedule: () -> Unit = {},
    vm: PeerSharingViewModel = hiltViewModel()
) {
    LaunchedEffect(peerId) { vm.init(peerId) }

    val state by vm.uiState.collectAsState()
    val cfg = state.config
    val sharingEnabled = cfg?.sharingEnabled ?: true
    val messagingEnabled = cfg?.messagingEnabled ?: true
    val precisionMode = cfg?.precisionMode ?: PrecisionMode.EXACT.name
    val isSosContact = cfg?.isSosContact ?: true
    val scheduleRules = cfg?.scheduleRules() ?: emptyList()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sharing with $peerName") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                                when {
                                    !sharingEnabled && !messagingEnabled -> "Location & messaging off"
                                    !sharingEnabled -> "Location sharing paused"
                                    !messagingEnabled -> "Messaging blocked"
                                    else -> "Sharing your location"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (sharingEnabled && messagingEnabled) MaterialTheme.colorScheme.primary
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

            // Messaging toggle
            item {
                SharingCard(title = "Messaging") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Allow messages from $peerName", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "When off, messages from ${peerName.split(" ").first()} are silently blocked",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = messagingEnabled,
                            onCheckedChange = { vm.setMessagingEnabled(it) }
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
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Schedule rules", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (scheduleRules.isEmpty()) "Always share with $peerName"
                                else "${scheduleRules.size} rule${if (scheduleRules.size == 1) "" else "s"} active",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = onNavigateToSchedule, enabled = sharingEnabled) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Edit schedule")
                        }
                    }
                }
            }

            // SOS Contact
            item {
                SharingCard(title = "Emergency Access") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Designate as SOS contact", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "This person will receive an immediate high-priority alert and your exact coordinates when you activate SOS.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = isSosContact,
                            onCheckedChange = { vm.setSosContact(it) }
                        )
                    }
                }
            }
        }
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
