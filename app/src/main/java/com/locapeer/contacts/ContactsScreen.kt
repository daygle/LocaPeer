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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun ContactsScreen(
    onNavigateToChat: (peerId: String, peerName: String) -> Unit,
    onNavigateToMap: (peerId: String) -> Unit = {},
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
                    Icon(
                        Icons.Default.People,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "No contacts yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Scan a QR code to add someone",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(contacts, key = { it.peer.deviceId }) { item ->
                    ContactRow(
                        item = item,
                        formatLastSeen = vm::formatLastSeen,
                        onMessage = { onNavigateToChat(item.peer.deviceId, item.peer.displayName) },
                        onRename = {
                            nameInput = item.peer.displayName
                            editingContact = item
                        },
                        onDelete = { confirmDelete = item }
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
                }
            }
        }
    }

    // Rename dialog
    editingContact?.let { contact ->
        AlertDialog(
            onDismissRequest = { editingContact = null },
            title = { Text("Rename contact") },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.renamePeer(contact.peer, nameInput)
                        editingContact = null
                    },
                    enabled = nameInput.isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingContact = null }) { Text("Cancel") }
            }
        )
    }

    // Delete confirmation dialog
    confirmDelete?.let { contact ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Remove contact") },
            text = { Text("Remove ${contact.peer.displayName}? Their location history will also be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.removePeer(contact.peer.deviceId)
                    confirmDelete = null
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ContactRow(
    item: ContactItem,
    formatLastSeen: (Long) -> String,
    onMessage: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val hb = item.lastHeartbeat
    val isBroadcaster = item.peer.role == "BROADCASTER"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
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
                    color = if (isBroadcaster) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.tertiaryContainer,
                    tonalElevation = 0.dp
                ) {
                    Text(
                        if (isBroadcaster) "tracking" else "subscriber",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = if (isBroadcaster) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
            Text(
                if (hb != null) "Last seen: ${formatLastSeen(hb.timestamp)}"
                else "No location data yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }

        // Action buttons
        if (isBroadcaster) {
            IconButton(onClick = onMessage) {
                Icon(
                    Icons.AutoMirrored.Filled.Chat,
                    contentDescription = "Message",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        IconButton(onClick = onRename) {
            Icon(Icons.Default.Edit, contentDescription = "Rename", modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Remove",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
