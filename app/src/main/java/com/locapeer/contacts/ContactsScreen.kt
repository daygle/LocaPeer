package com.locapeer.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun ContactsScreen(
    onNavigateToChat: (peerId: String, peerName: String) -> Unit,
    onNavigateToSharingSettings: (peerId: String, peerName: String) -> Unit = { _, _ -> },
    vm: ContactsViewModel = hiltViewModel()
) {
    val contacts by vm.contacts.collectAsState()
    var confirmDelete by remember { mutableStateOf<ContactItem?>(null) }
    var editingContact by remember { mutableStateOf<ContactItem?>(null) }
    var nameInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(title = { Text("Contacts") })
        }
    ) { padding ->
        if (contacts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.People, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("No contacts yet", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Scan a QR code to add someone", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(contacts, key = { it.peer.deviceId }) { item ->
                    ContactRow(
                        item = item,
                        formatLastSeen = vm::formatLastSeen,
                        onMessage = { onNavigateToChat(item.peer.deviceId, item.peer.displayName) },
                        onRename = { nameInput = item.peer.displayName; editingContact = item },
                        onDelete = { confirmDelete = item },
                        onToggleLocationSharing = { vm.setLocationSharing(item.peer.deviceId, it) },
                        onToggleMessaging = { vm.setMessaging(item.peer.deviceId, it) },
                        onSharingSettings = { onNavigateToSharingSettings(item.peer.deviceId, item.peer.displayName) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
                }
            }
        }
    }

    editingContact?.let { contact ->
        AlertDialog(
            onDismissRequest = { editingContact = null },
            title = { Text("Rename contact") },
            text = {
                OutlinedTextField(value = nameInput, onValueChange = { nameInput = it }, label = { Text("Name") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = { vm.renamePeer(contact.peer, nameInput); editingContact = null }, enabled = nameInput.isNotBlank()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingContact = null }) { Text("Cancel") } }
        )
    }

    confirmDelete?.let { contact ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Remove contact") },
            text = { Text("Remove ${contact.peer.displayName}? Their location history will also be deleted.") },
            confirmButton = {
                TextButton(onClick = { vm.removePeer(contact.peer.deviceId); confirmDelete = null }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ContactRow(
    item: ContactItem,
    formatLastSeen: (Long) -> String,
    onMessage: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onToggleLocationSharing: (Boolean) -> Unit,
    onToggleMessaging: (Boolean) -> Unit,
    onSharingSettings: () -> Unit
) {
    val hb = item.lastHeartbeat
    val isBroadcaster = item.peer.role == "BROADCASTER"
    val locationSharingOn = item.config?.sharingEnabled ?: true
    val messagingOn = item.config?.messagingEnabled ?: true

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        // Top row: avatar + name + action buttons
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    item.peer.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        item.peer.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = if (isBroadcaster) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
                        tonalElevation = 0.dp
                    ) {
                        Text(
                            if (isBroadcaster) "tracking" else "subscriber",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = if (isBroadcaster) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
                Text(
                    if (hb != null) "Last seen: ${formatLastSeen(hb.timestamp)}" else "No location data yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }

            if (isBroadcaster) {
                IconButton(onClick = onMessage) {
                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Message", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
            }
            IconButton(onClick = onRename) {
                Icon(Icons.Default.Edit, contentDescription = "Rename", modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            }
        }

        // Permission toggles row
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 62.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PermissionChip(
                icon = Icons.Default.LocationOn,
                label = if (locationSharingOn) "Location on" else "Location off",
                active = locationSharingOn,
                onClick = { onToggleLocationSharing(!locationSharingOn) }
            )
            PermissionChip(
                icon = Icons.AutoMirrored.Filled.Chat,
                label = if (messagingOn) "Messages on" else "Messages off",
                active = messagingOn,
                onClick = { onToggleMessaging(!messagingOn) }
            )
            if (isBroadcaster) {
                IconButton(onClick = onSharingSettings, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Tune, contentDescription = "Sharing settings", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PermissionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    val containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
    val contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer

    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = contentColor)
            Text(label, style = MaterialTheme.typography.labelSmall, color = contentColor)
        }
    }
}
