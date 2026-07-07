package com.locapeer.messaging

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.locapeer.data.entity.PeerEntity
import com.locapeer.ui.components.ConversationShimmerRow
import com.locapeer.ui.components.EmptyState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class LoadState { LOADING, EMPTY, CONTENT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    onOpenChat: (peerId: String, peerName: String) -> Unit,
    vm: MessagingViewModel = hiltViewModel()
) {
    val conversations by vm.conversations.collectAsState()
    val archivedConversations by vm.archivedConversations.collectAsState()
    val peers by vm.peers.collectAsState()
    val unreadCounts by vm.unreadCounts.collectAsState()
    val searchQuery by vm.searchQuery.collectAsState()
    val sortOrder by vm.sortOrder.collectAsState()

    var showContactPicker by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var showSearch by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showBulkDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(selectedTab) { selectedIds = emptySet() }

    val activeList = if (selectedTab == 0) conversations else archivedConversations

    val allCurrentIds = remember(activeList) {
        (activeList ?: emptyList()).map { it.peer.deviceId }.toSet()
    }

    val displayList = activeList

    val loadState = when {
        displayList == null -> LoadState.LOADING
        displayList.isEmpty() -> LoadState.EMPTY
        else -> LoadState.CONTENT
    }

    val isSelectionMode = selectedIds.isNotEmpty()
    val allSelected = allCurrentIds.isNotEmpty() && selectedIds.containsAll(allCurrentIds)
    val archivingToArchive = selectedTab == 0

    if (showContactPicker) {
        ContactPickerDialog(
            peers = peers,
            onSelect = { peerId, peerName ->
                showContactPicker = false
                onOpenChat(peerId, peerName)
            },
            onDismiss = { showContactPicker = false }
        )
    }

    if (showBulkDeleteDialog) {
        val count = selectedIds.size
        AlertDialog(
            onDismissRequest = { showBulkDeleteDialog = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("Delete $count ${if (count == 1) "conversation" else "conversations"}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ListItem(
                        headlineContent = { Text("Delete locally") },
                        supportingContent = { Text("Removed from your device only.") },
                        modifier = Modifier.clickable {
                            selectedIds.forEach { vm.deleteConversation(it) }
                            showBulkDeleteDialog = false
                            selectedIds = emptySet()
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    ListItem(
                        headlineContent = { Text("Delete from both") },
                        supportingContent = { Text("Also requests each contact to delete your messages.") },
                        modifier = Modifier.clickable {
                            selectedIds.forEach {
                                vm.deleteConversation(it)
                                vm.deleteConversationFromRemote(it)
                            }
                            showBulkDeleteDialog = false
                            selectedIds = emptySet()
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showBulkDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        if (isSelectionMode) {
                            IconButton(onClick = { selectedIds = emptySet() }) {
                                Icon(Icons.Default.Close, contentDescription = "Exit selection")
                            }
                        }
                    },
                    title = {
                        if (isSelectionMode) {
                            Text("${selectedIds.size} selected", fontWeight = FontWeight.SemiBold)
                        } else {
                            Text("Messages", fontWeight = FontWeight.SemiBold)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    actions = {
                        if (isSelectionMode) {
                            IconButton(onClick = {
                                selectedIds = if (allSelected) emptySet() else allCurrentIds
                            }) {
                                Icon(
                                    if (allSelected) Icons.Default.CheckBox
                                    else Icons.Default.CheckBoxOutlineBlank,
                                    contentDescription = "Select All"
                                )
                            }
                        } else {
                            IconButton(onClick = {
                                showSearch = !showSearch
                                if (!showSearch) vm.setSearchQuery("")
                            }) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = "Search",
                                    tint = if (showSearch) MaterialTheme.colorScheme.primary
                                           else LocalContentColor.current
                                )
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
                                        text = { Text("Date") },
                                        onClick = { vm.setSortOrder(SortOrder.DATE); showSortMenu = false },
                                        leadingIcon = { if (sortOrder == SortOrder.DATE) Icon(Icons.Default.Check, null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Name") },
                                        onClick = { vm.setSortOrder(SortOrder.NAME); showSortMenu = false },
                                        leadingIcon = { if (sortOrder == SortOrder.NAME) Icon(Icons.Default.Check, null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Unread") },
                                        onClick = { vm.setSortOrder(SortOrder.UNREAD); showSortMenu = false },
                                        leadingIcon = { if (sortOrder == SortOrder.UNREAD) Icon(Icons.Default.Check, null) }
                                    )
                                }
                            }
                            IconButton(onClick = { selectedIds = allCurrentIds }) {
                                Icon(Icons.Default.CheckBoxOutlineBlank, contentDescription = "Select All")
                            }
                        }
                    }
                )
                AnimatedVisibility(
                    visible = showSearch && !isSelectionMode,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { vm.setSearchQuery(it) },
                        placeholder = { Text("Search conversations…") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { vm.setSearchQuery("") }) {
                                    Icon(Icons.Default.Close, null)
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                SecondaryTabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Chats") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Archived") }
                    )
                }
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
                            icon = { Icon(Icons.Default.DoneAll, contentDescription = null) },
                            label = "Mark Read",
                            onClick = {
                                vm.markReadMultiple(selectedIds.toList())
                                selectedIds = emptySet()
                            }
                        )
                        BulkActionButton(
                            icon = {
                                Icon(
                                    if (archivingToArchive) Icons.Default.Archive else Icons.Default.Unarchive,
                                    contentDescription = null
                                )
                            },
                            label = if (archivingToArchive) "Archive" else "Unarchive",
                            onClick = {
                                selectedIds.forEach { vm.archiveConversation(it, archivingToArchive) }
                                selectedIds = emptySet()
                            }
                        )
                        BulkActionButton(
                            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
                            label = "Delete",
                            onClick = { showBulkDeleteDialog = true }
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (!isSelectionMode) {
                FloatingActionButton(onClick = { showContactPicker = true }) {
                    Icon(Icons.Default.Add, contentDescription = "New message")
                }
            }
        }
    ) { padding ->
        AnimatedContent(
            targetState = loadState,
            transitionSpec = {
                fadeIn(tween(300)) togetherWith fadeOut(tween(200))
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            label = "conversations_state"
        ) { state ->
            when (state) {
                LoadState.LOADING -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(5) { ConversationShimmerRow() }
                    }
                }
                LoadState.EMPTY -> {
                    val emptyTitle = when {
                        searchQuery.isNotBlank() -> "No results for \"$searchQuery\""
                        selectedTab == 1 -> "No archived chats"
                        else -> "No messages yet"
                    }
                    val emptySubtitle = when {
                        searchQuery.isNotBlank() -> "Try a different search term"
                        selectedTab == 1 -> "Archive chats to keep your inbox clean"
                        else -> "Open the map and tap a person\nto start a conversation"
                    }
                    EmptyState(
                        icon = if (selectedTab == 0) Icons.Default.ChatBubbleOutline else Icons.Default.Archive,
                        title = emptyTitle,
                        subtitle = emptySubtitle
                    )
                }
                LoadState.CONTENT -> {
                    val safeList = displayList ?: emptyList()
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(safeList, key = { it.peer.deviceId }) { conv ->
                            val isSelected = conv.peer.deviceId in selectedIds
                            SwipeActionsConversation(
                                conv = conv,
                                unreadCount = unreadCounts[conv.peer.deviceId] ?: 0,
                                isSelected = isSelected,
                                isSelectionMode = isSelectionMode,
                                onClick = { onOpenChat(conv.peer.deviceId, conv.peer.displayName) },
                                onToggleSelect = {
                                    selectedIds = if (isSelected)
                                        selectedIds - conv.peer.deviceId
                                    else
                                        selectedIds + conv.peer.deviceId
                                },
                                onEnterSelectionMode = {
                                    selectedIds = setOf(conv.peer.deviceId)
                                },
                                onDeleteLocal = { vm.deleteConversation(conv.peer.deviceId) },
                                onDeleteRemote = { vm.deleteConversationFromRemote(conv.peer.deviceId) },
                                onArchive = { vm.archiveConversation(conv.peer.deviceId, !conv.peer.isArchived) }
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
    }
}

@Composable
private fun BulkActionButton(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        IconButton(onClick = onClick) { icon() }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeActionsConversation(
    conv: ConversationSummary,
    unreadCount: Int,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    onClick: () -> Unit,
    onToggleSelect: () -> Unit = {},
    onEnterSelectionMode: () -> Unit = {},
    onDeleteLocal: () -> Unit,
    onDeleteRemote: () -> Unit,
    onArchive: () -> Unit
) {
    if (isSelectionMode) {
        Surface(
            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            ConversationRow(
                summary = conv,
                unreadCount = unreadCount,
                isSelected = isSelected,
                onClick = onToggleSelect,
                onLongClick = onToggleSelect
            )
        }
        return
    }

    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { it * 0.4f }
    )
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(dismissState.currentValue) {
        when (dismissState.currentValue) {
            SwipeToDismissBoxValue.EndToStart -> {
                showDeleteDialog = true
                dismissState.snapTo(SwipeToDismissBoxValue.Settled)
            }
            SwipeToDismissBoxValue.StartToEnd -> {
                onArchive()
                dismissState.snapTo(SwipeToDismissBoxValue.Settled)
            }
            else -> {}
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("Delete conversation?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("How do you want to delete this conversation with ${conv.peer.displayName}?")
                    ListItem(
                        headlineContent = { Text("Delete locally") },
                        supportingContent = { Text("Removed from your device only.") },
                        modifier = Modifier.clickable { onDeleteLocal(); showDeleteDialog = false },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    ListItem(
                        headlineContent = { Text("Delete from both") },
                        supportingContent = { Text("Removes it locally and requests the peer to delete messages you sent.") },
                        modifier = Modifier.clickable { onDeleteLocal(); onDeleteRemote(); showDeleteDialog = false },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = true,
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val color = when (direction) {
                SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
                SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.secondaryContainer
                else -> Color.Transparent
            }
            val alignment = when (direction) {
                SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                else -> Alignment.Center
            }
            val icon = when (direction) {
                SwipeToDismissBoxValue.EndToStart -> Icons.Default.Delete
                SwipeToDismissBoxValue.StartToEnd ->
                    if (conv.peer.isArchived) Icons.Default.Unarchive else Icons.Default.Archive
                else -> Icons.Default.Delete
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color)
                    .padding(horizontal = 20.dp),
                contentAlignment = alignment
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (direction == SwipeToDismissBoxValue.EndToStart)
                        MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    ) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            ConversationRow(
                summary = conv,
                unreadCount = unreadCount,
                onClick = onClick,
                onLongClick = onEnterSelectionMode
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    summary: ConversationSummary,
    unreadCount: Int,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val hasUnread = unreadCount > 0
    val isBlocked = !summary.peer.messagingEnabled
    ListItem(
        leadingContent = {
            Box(modifier = Modifier.size(48.dp)) {
                AvatarCircle(name = summary.peer.displayName, hasUnread = hasUnread && !isSelected)
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    summary.peer.displayName,
                    fontWeight = if (hasUnread) FontWeight.Bold else FontWeight.Normal
                )
                if (isBlocked) {
                    Icon(
                        Icons.Default.Block,
                        contentDescription = "Messages blocked",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        supportingContent = {
            val preview = when {
                isBlocked -> "Messages blocked"
                summary.lastMessage.isMine -> "You: ${summary.lastMessage.content}"
                else -> summary.lastMessage.content
            }
            Text(
                preview,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isBlocked) MaterialTheme.colorScheme.error
                        else if (hasUnread) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (hasUnread) FontWeight.Medium else FontWeight.Normal,
                style = if (isBlocked) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium
            )
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    formatTime(summary.lastMessage.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hasUnread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (hasUnread) {
                    Badge(containerColor = MaterialTheme.colorScheme.primary) { Text("$unreadCount") }
                }
            }
        },
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    )
}

@Composable
private fun AvatarCircle(name: String, hasUnread: Boolean) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(
                if (hasUnread) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.primaryContainer
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (hasUnread) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun ContactPickerDialog(
    peers: List<PeerEntity>,
    onSelect: (peerId: String, peerName: String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("New Message") },
        text = {
            if (peers.isEmpty()) {
                Text(
                    "No contacts yet. Scan a QR code to add a contact.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn {
                    items(peers, key = { it.deviceId }) { peer ->
                        ListItem(
                            leadingContent = {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        peer.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            },
                            headlineContent = { Text(peer.displayName) },
                            modifier = Modifier.clickable { onSelect(peer.deviceId, peer.displayName) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun formatTime(millis: Long): String =
    com.locapeer.util.DisplayFormat.timeFormat().format(Date(millis))
