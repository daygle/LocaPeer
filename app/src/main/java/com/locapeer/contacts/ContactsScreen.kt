package com.locapeer.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.locapeer.data.entity.HeartbeatEntity
import com.locapeer.data.entity.PeerEntity

private enum class DataAction { DELETE_MESSAGES, DELETE_LOCATION, REMOVE_SELF, REMOVE_CONTACT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    onNavigateToChat: (peerId: String, peerName: String) -> Unit,
    onNavigateToSharingSettings: (peerId: String, peerName: String) -> Unit = { _, _ -> },
    onNavigateToMap: (lat: Double, lng: Double) -> Unit = { _, _ -> },
    vm: ContactsViewModel = hiltViewModel()
) {
    val contacts by vm.contacts.collectAsState()
    var confirmAction by remember { mutableStateOf<Pair<ContactItem, DataAction>?>(null) }
    var editingContact by remember { mutableStateOf<ContactItem?>(null) }
    var nameInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
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
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(contacts, key = { it.peer.deviceId }) { item ->
                    ContactRow(
                        item = item,
                        formatLastSeen = vm::formatLastSeen,
                        onMessage = { onNavigateToChat(item.peer.deviceId, item.peer.displayName) },
                        onShowOnMap = { hb -> onNavigateToMap(hb.lat, hb.lng) },
                        onRename = { nameInput = item.peer.displayName; editingContact = item },
                        onDeleteContact = { confirmAction = item to DataAction.REMOVE_CONTACT },
                        onRemoveSelf = { confirmAction = item to DataAction.REMOVE_SELF },
                        onDeleteMyMessages = { confirmAction = item to DataAction.DELETE_MESSAGES },
                        onDeleteMyLocation = { confirmAction = item to DataAction.DELETE_LOCATION },
                        onSharingSettings = { onNavigateToSharingSettings(item.peer.deviceId, item.peer.displayName) }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 72.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
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

    confirmAction?.let { (contact, action) ->
        val (title, body, confirmLabel) = when (action) {
            DataAction.REMOVE_CONTACT -> Triple(
                "Remove Contact",
                "Remove ${contact.peer.displayName} from your contacts? Their location history will also be deleted locally.",
                "Remove"
            )
            DataAction.REMOVE_SELF -> Triple(
                "Remove Myself",
                "This will ask ${contact.peer.displayName}'s device to delete all your messages and location data, then remove you from their contacts. You will also lose access to their location.",
                "Remove"
            )
            DataAction.DELETE_MESSAGES -> Triple(
                "Delete My Messages",
                "Ask ${contact.peer.displayName}'s device to delete all messages you sent them. This cannot be undone.",
                "Delete"
            )
            DataAction.DELETE_LOCATION -> Triple(
                "Delete My Location",
                "Ask ${contact.peer.displayName}'s device to delete all location data you have shared with them. This cannot be undone.",
                "Delete"
            )
        }
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (action) {
                            DataAction.REMOVE_CONTACT -> vm.removePeer(contact.peer.deviceId)
                            DataAction.REMOVE_SELF -> vm.removeSelfFromPeer(contact.peer.deviceId)
                            DataAction.DELETE_MESSAGES -> vm.deleteMyMessagesFromPeer(contact.peer.deviceId)
                            DataAction.DELETE_LOCATION -> vm.deleteMyLocationFromPeer(contact.peer.deviceId)
                        }
                        confirmAction = null
                    }
                ) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmAction = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ContactRow(
    item: ContactItem,
    formatLastSeen: (Long) -> String,
    onMessage: () -> Unit,
    onShowOnMap: (HeartbeatEntity) -> Unit,
    onRename: () -> Unit,
    onDeleteContact: () -> Unit,
    onRemoveSelf: () -> Unit,
    onDeleteMyMessages: () -> Unit,
    onDeleteMyLocation: () -> Unit,
    onSharingSettings: () -> Unit
) {
    val hb = item.lastHeartbeat
    val role = item.peer.locationRole
    val canMessage = item.peer.messagingEnabled
    var showOverflow by remember { mutableStateOf(false) }

    ListItem(
        modifier = Modifier.clickable { onSharingSettings() },
        leadingContent = {
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
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    item.peer.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = when (role) {
                        PeerEntity.ROLE_RECEIVE -> MaterialTheme.colorScheme.secondaryContainer
                        PeerEntity.ROLE_SEND_RECEIVE -> MaterialTheme.colorScheme.primaryContainer
                        PeerEntity.ROLE_NONE -> MaterialTheme.colorScheme.surfaceVariant
                        else -> MaterialTheme.colorScheme.tertiaryContainer
                    }
                ) {
                    Text(
                        when (role) {
                            PeerEntity.ROLE_RECEIVE -> "Receive Location"
                            PeerEntity.ROLE_SEND_RECEIVE -> "Send/Receive Location"
                            PeerEntity.ROLE_NONE -> "No Location"
                            else -> "Send Location"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = when (role) {
                            PeerEntity.ROLE_RECEIVE -> MaterialTheme.colorScheme.onSecondaryContainer
                            PeerEntity.ROLE_SEND_RECEIVE -> MaterialTheme.colorScheme.onPrimaryContainer
                            PeerEntity.ROLE_NONE -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.onTertiaryContainer
                        }
                    )
                }
            }
        },
        supportingContent = {
            Text(
                if (hb != null) "Last seen: ${formatLastSeen(hb.timestamp)}" else "No location data yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (canMessage) {
                    IconButton(onClick = onMessage) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Message", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    }
                }
                if (hb != null) {
                    IconButton(onClick = { onShowOnMap(hb) }) {
                        Icon(Icons.Default.LocationOn, contentDescription = "Show on map", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    }
                }
                Box {
                    IconButton(onClick = { showOverflow = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options", modifier = Modifier.size(22.dp))
                    }
                    DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                        DropdownMenuItem(
                            text = { Text("Rename Contact") },
                            leadingIcon = { Icon(Icons.Default.Edit, null, Modifier.size(18.dp)) },
                            onClick = { showOverflow = false; onRename() }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete My Messages") },
                            leadingIcon = { Icon(Icons.Default.DeleteSweep, null, Modifier.size(18.dp)) },
                            onClick = { showOverflow = false; onDeleteMyMessages() }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete My Location") },
                            leadingIcon = { Icon(Icons.Default.LocationOff, null, Modifier.size(18.dp)) },
                            onClick = { showOverflow = false; onDeleteMyLocation() }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Remove Myself", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.PersonRemove, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp)) },
                            onClick = { showOverflow = false; onRemoveSelf() }
                        )
                        DropdownMenuItem(
                            text = { Text("Remove Contact", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp)) },
                            onClick = { showOverflow = false; onDeleteContact() }
                        )
                    }
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}
