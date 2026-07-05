package com.locapeer.contacts

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
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
import com.locapeer.ui.components.EmptyState

private enum class DataAction { DELETE_MESSAGES, DELETE_LOCATION, REMOVE_SELF, REMOVE_CONTACT }
private enum class SortOrder { LAST_SEEN, NAME }
private enum class BulkAction { REMOVE, DELETE_MESSAGES, DELETE_LOCATION }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    onNavigateToChat: (peerId: String, peerName: String) -> Unit,
    onNavigateToSharingSettings: (peerId: String, peerName: String) -> Unit = { _, _ -> },
    onNavigateToMap: (lat: Double, lng: Double) -> Unit = { _, _ -> },
    onNavigateToPendingRequests: () -> Unit = {},
    onNavigateToHistory: (peerId: String) -> Unit = {},
    onNavigateToInvite: () -> Unit = {},
    vm: ContactsViewModel = hiltViewModel()
) {
    val contacts by vm.contacts.collectAsState()
    val pendingCount by vm.pendingRequestCount.collectAsState()

    // Per-contact dialogs (unchanged)
    var confirmAction by remember { mutableStateOf<Pair<ContactItem, DataAction>?>(null) }
    var editingContact by remember { mutableStateOf<ContactItem?>(null) }
    var nameInput by remember { mutableStateOf("") }

    // Search / sort
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var sortOrder by remember { mutableStateOf(SortOrder.LAST_SEEN) }
    var showSortMenu by remember { mutableStateOf(false) }

    // Selection
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var pendingBulkAction by remember { mutableStateOf<BulkAction?>(null) }

    val isSelectionMode = selectedIds.isNotEmpty()
    val allIds = remember(contacts) { contacts.map { it.peer.deviceId }.toSet() }
    val allSelected = allIds.isNotEmpty() && selectedIds.containsAll(allIds)

    val displayList = remember(contacts, searchQuery, sortOrder) {
        val filtered = if (searchQuery.isBlank()) contacts
        else contacts.filter { it.peer.displayName.contains(searchQuery, ignoreCase = true) }
        when (sortOrder) {
            SortOrder.NAME -> filtered.sortedBy { it.peer.displayName.lowercase() }
            SortOrder.LAST_SEEN -> filtered.sortedByDescending { it.lastHeartbeat?.timestamp ?: 0L }
        }
    }

    Scaffold(
        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { selectedIds = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                        }
                    },
                    title = { Text("${selectedIds.size} selected") },
                    actions = {
                        IconButton(onClick = {
                            selectedIds = if (allSelected) emptySet() else allIds
                        }) {
                            Icon(
                                if (allSelected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                contentDescription = if (allSelected) "Deselect all" else "Select all"
                            )
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text("Contacts") },
                    actions = {
                        IconButton(onClick = {
                            showSearch = !showSearch
                            if (!showSearch) searchQuery = ""
                        }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Last Seen") },
                                    leadingIcon = { Icon(Icons.Default.AccessTime, null, Modifier.size(18.dp)) },
                                    trailingIcon = if (sortOrder == SortOrder.LAST_SEEN) {
                                        { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                                    } else null,
                                    onClick = { sortOrder = SortOrder.LAST_SEEN; showSortMenu = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("Name") },
                                    leadingIcon = { Icon(Icons.Default.SortByAlpha, null, Modifier.size(18.dp)) },
                                    trailingIcon = if (sortOrder == SortOrder.NAME) {
                                        { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                                    } else null,
                                    onClick = { sortOrder = SortOrder.NAME; showSortMenu = false }
                                )
                            }
                        }
                        IconButton(onClick = { selectedIds = allIds }) {
                            Icon(Icons.Default.CheckBoxOutlineBlank, contentDescription = "Select all")
                        }
                        IconButton(onClick = onNavigateToInvite) {
                            Icon(Icons.Default.QrCode, contentDescription = "QR / Invite")
                        }
                        BadgedBox(
                            badge = { if (pendingCount > 0) Badge { Text("$pendingCount") } }
                        ) {
                            IconButton(onClick = onNavigateToPendingRequests) {
                                Icon(Icons.Default.Inbox, contentDescription = "Pending Requests")
                            }
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (isSelectionMode) {
                BottomAppBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BulkActionButton(
                            icon = { Icon(Icons.Default.DeleteSweep, null) },
                            label = "Delete Messages",
                            onClick = { pendingBulkAction = BulkAction.DELETE_MESSAGES }
                        )
                        BulkActionButton(
                            icon = { Icon(Icons.Default.LocationOff, null) },
                            label = "Delete Locations",
                            onClick = { pendingBulkAction = BulkAction.DELETE_LOCATION }
                        )
                        BulkActionButton(
                            icon = { Icon(Icons.Default.Delete, null) },
                            label = "Remove",
                            onClick = { pendingBulkAction = BulkAction.REMOVE }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            AnimatedVisibility(
                visible = showSearch && !isSelectionMode,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search contacts…") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, null)
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (contacts.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.People,
                    title = "No contacts yet",
                    subtitle = "Scan a QR code to connect with someone and start sharing locations.",
                ) {
                    FilledTonalButton(onClick = onNavigateToInvite) {
                        Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Scan QR Code")
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(displayList, key = { it.peer.deviceId }) { item ->
                        val isSelected = item.peer.deviceId in selectedIds
                        ContactRow(
                            item = item,
                            isSelected = isSelected,
                            isSelectionMode = isSelectionMode,
                            formatLastSeen = vm::formatLastSeen,
                            onMessage = { onNavigateToChat(item.peer.deviceId, item.peer.displayName) },
                            onShowOnMap = { hb -> onNavigateToMap(hb.lat, hb.lng) },
                            onShowHistory = { onNavigateToHistory(item.peer.deviceId) },
                            onRename = { nameInput = item.peer.displayName; editingContact = item },
                            onDeleteContact = { confirmAction = item to DataAction.REMOVE_CONTACT },
                            onRemoveSelf = { confirmAction = item to DataAction.REMOVE_SELF },
                            onDeleteMyMessages = { confirmAction = item to DataAction.DELETE_MESSAGES },
                            onDeleteMyLocation = { confirmAction = item to DataAction.DELETE_LOCATION },
                            onSharingSettings = {
                                onNavigateToSharingSettings(item.peer.deviceId, item.peer.displayName)
                            },
                            onToggleSelect = {
                                selectedIds = if (isSelected) selectedIds - item.peer.deviceId
                                else selectedIds + item.peer.deviceId
                            },
                            onEnterSelectionMode = { selectedIds = setOf(item.peer.deviceId) }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 72.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }

    // Bulk action confirmation dialogs
    pendingBulkAction?.let { action ->
        val count = selectedIds.size
        val plural = count > 1
        val (title, body, confirmLabel) = when (action) {
            BulkAction.REMOVE -> Triple(
                "Remove ${if (plural) "$count Contacts" else "Contact"}",
                "Remove ${if (plural) "$count contacts" else "this contact"}? Their location history will also be deleted locally.",
                "Remove"
            )
            BulkAction.DELETE_MESSAGES -> Triple(
                "Delete My Messages",
                "Remote delete all messages you sent from ${if (plural) "$count contacts'" else "this contact's"} device? This cannot be undone.",
                "Delete"
            )
            BulkAction.DELETE_LOCATION -> Triple(
                "Delete Shared Locations",
                "Remote delete all location history you shared from ${if (plural) "$count contacts'" else "this contact's"} device? This cannot be undone.",
                "Delete"
            )
        }
        AlertDialog(
            onDismissRequest = { pendingBulkAction = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val ids = selectedIds.toList()
                        when (action) {
                            BulkAction.REMOVE -> ids.forEach { vm.removePeer(it) }
                            BulkAction.DELETE_MESSAGES -> ids.forEach { vm.purgeMyMessagesOnPeer(it) }
                            BulkAction.DELETE_LOCATION -> ids.forEach { vm.purgeMyLocationOnPeer(it) }
                        }
                        selectedIds = emptySet()
                        pendingBulkAction = null
                    }
                ) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingBulkAction = null }) { Text("Cancel") } }
        )
    }

    // Per-contact rename dialog
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
                    onClick = { vm.renamePeer(contact.peer, nameInput); editingContact = null },
                    enabled = nameInput.isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingContact = null }) { Text("Cancel") } }
        )
    }

    // Per-contact destructive action dialog
    confirmAction?.let { (contact, action) ->
        val (title, body, confirmLabel) = when (action) {
            DataAction.REMOVE_CONTACT -> Triple(
                "Remove Contact",
                "Remove ${contact.peer.displayName} from your contacts? Their location history will also be deleted locally.",
                "Remove"
            )
            DataAction.REMOVE_SELF -> Triple(
                "Leave Contact List",
                "This will remove you from ${contact.peer.displayName}'s contacts and delete all your messages and location data from their device. You will also lose access to their location.",
                "Leave"
            )
            DataAction.DELETE_MESSAGES -> Triple(
                "Delete My Messages",
                "This will delete all messages you sent from ${contact.peer.displayName}'s device. This cannot be undone.",
                "Delete"
            )
            DataAction.DELETE_LOCATION -> Triple(
                "Delete Shared Locations",
                "This will delete all location data you have shared from ${contact.peer.displayName}'s device. This cannot be undone.",
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
                            DataAction.DELETE_MESSAGES -> vm.purgeMyMessagesOnPeer(contact.peer.deviceId)
                            DataAction.DELETE_LOCATION -> vm.purgeMyLocationOnPeer(contact.peer.deviceId)
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
private fun BulkActionButton(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) { icon() }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactRow(
    item: ContactItem,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    formatLastSeen: (Long) -> String,
    onMessage: () -> Unit,
    onShowOnMap: (HeartbeatEntity) -> Unit,
    onShowHistory: () -> Unit,
    onRename: () -> Unit,
    onDeleteContact: () -> Unit,
    onRemoveSelf: () -> Unit,
    onDeleteMyMessages: () -> Unit,
    onDeleteMyLocation: () -> Unit,
    onSharingSettings: () -> Unit,
    onToggleSelect: () -> Unit,
    onEnterSelectionMode: () -> Unit
) {
    val hb = item.lastHeartbeat
    val canMessage = item.peer.messagingEnabled
    var showOverflow by remember { mutableStateOf(false) }

    ListItem(
        modifier = Modifier.combinedClickable(
            onClick = if (isSelectionMode) onToggleSelect else onSharingSettings,
            onLongClick = if (isSelectionMode) null else onEnterSelectionMode
        ),
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.primaryContainer
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(
                        item.peer.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        },
        headlineContent = {
            Text(
                item.peer.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                if (hb != null) "Last seen: ${formatLastSeen(hb.timestamp)}" else "No location data yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = if (isSelectionMode) null else {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (canMessage) {
                        IconButton(onClick = onMessage) {
                            Icon(
                                Icons.AutoMirrored.Filled.Chat,
                                contentDescription = "Message",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    if (hb != null) {
                        IconButton(onClick = { onShowOnMap(hb) }) {
                            Icon(
                                Icons.Default.LocationOn,
                                contentDescription = "Show on map",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { showOverflow = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options", modifier = Modifier.size(22.dp))
                        }
                        DropdownMenu(
                            expanded = showOverflow,
                            onDismissRequest = { showOverflow = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("View Sharing Settings") },
                                leadingIcon = { Icon(Icons.Default.Settings, null, Modifier.size(18.dp)) },
                                onClick = { showOverflow = false; onSharingSettings() }
                            )
                            DropdownMenuItem(
                                text = { Text("View Location History") },
                                leadingIcon = { Icon(Icons.Default.History, null, Modifier.size(18.dp)) },
                                onClick = { showOverflow = false; onShowHistory() }
                            )
                            DropdownMenuItem(
                                text = { Text("Rename Contact") },
                                leadingIcon = { Icon(Icons.Default.Edit, null, Modifier.size(18.dp)) },
                                onClick = { showOverflow = false; onRename() }
                            )
                            HorizontalDivider()
                            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                                Text(
                                    "On Contact's Device",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Delete My Messages") },
                                leadingIcon = { Icon(Icons.Default.DeleteSweep, null, Modifier.size(18.dp)) },
                                onClick = { showOverflow = false; onDeleteMyMessages() }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete Shared Locations") },
                                leadingIcon = { Icon(Icons.Default.LocationOff, null, Modifier.size(18.dp)) },
                                onClick = { showOverflow = false; onDeleteMyLocation() }
                            )
                            DropdownMenuItem(
                                text = { Text("Leave Contact List", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.PersonRemove,
                                        null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = { showOverflow = false; onRemoveSelf() }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Remove Contact", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Delete,
                                        null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = { showOverflow = false; onDeleteContact() }
                            )
                        }
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    )
}
