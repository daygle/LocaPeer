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
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.MarkChatUnread
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.locapeer.R
import com.locapeer.data.entity.PeerEntity
import com.locapeer.ui.components.ConversationShimmerRow
import com.locapeer.ui.components.EmptyState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class LoadState { LOADING, EMPTY, CONTENT }

/** Sub-tabs of the Messages screen. Order is canonical and matches [SecondaryTabRow] layout.
 *  Stored as `Int` via the auto-assigned `ordinal` so the order here is also the runtime order. */
private enum class MessagesTab {
    CHATS,
    CIRCLES,
    ARCHIVED;

    companion object {
        fun fromOrdinal(i: Int): MessagesTab =
            entries.getOrNull(i) ?: CHATS
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    onOpenChat: (peerId: String, peerName: String) -> Unit,
    onOpenGroup: (circleId: String) -> Unit = {},
    onCreateCircle: () -> Unit = {},
    vm: MessagingViewModel = hiltViewModel()
) {
    val conversations by vm.conversations.collectAsState()
    val archivedConversations by vm.archivedConversations.collectAsState()
    val groupConversations by vm.groupConversations.collectAsState()
    val archivedGroupConversations by vm.archivedGroupConversations.collectAsState()
    val peers by vm.peers.collectAsState()
    val unreadCounts by vm.unreadCounts.collectAsState()
    val chatsUnreadTotal by vm.chatsUnreadTotal.collectAsState()
    val circlesUnreadTotal by vm.circlesUnreadTotal.collectAsState()
    val searchQuery by vm.searchQuery.collectAsState()
    val sortOrder by vm.sortOrder.collectAsState()

    var showContactPicker by remember { mutableStateOf(false) }
    // rememberSaveable (not remember) so the selected sub-tab survives navigating into a chat or
    // circle and back: opening a circle chat disposes this screen's composition, and a plain
    // remember would reset the user to the Chats tab on return instead of the Circles tab.
    var selectedTab by rememberSaveable { mutableIntStateOf(MessagesTab.CHATS.ordinal) }
    var showSearch by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showBulkDeleteDialog by remember { mutableStateOf(false) }

    val currentTab = MessagesTab.fromOrdinal(selectedTab)

    // Capture delegated properties to local vals to allow smart casting and ensure
    // consistency within this recomposition frame.
    val currentGroups = groupConversations

    // Switching tabs is treated as a "fresh context": drop any in-progress search and
    // selection mode, otherwise users would see a stale search bar / selection state
    // on a list that no longer matches (circles don't search/sort).
    LaunchedEffect(selectedTab) {
        selectedIds = emptySet()
        showSearch = false
        vm.setSearchQuery("")
    }

    // Pre-compute content for the active non-Circles tab. The Circles tab is rendered
    // directly off the nullable `groupConversations` flow further below.
    val activeList: List<ConversationSummary>? = when (currentTab) {
        MessagesTab.CHATS -> conversations
        MessagesTab.ARCHIVED -> archivedConversations
        MessagesTab.CIRCLES -> null
    }
    val displayList = activeList

    val loadState = when (currentTab) {
        MessagesTab.CIRCLES -> when {
            currentGroups == null -> LoadState.LOADING
            currentGroups.isEmpty() -> LoadState.EMPTY
            else -> LoadState.CONTENT
        }
        MessagesTab.ARCHIVED -> when {
            displayList == null -> LoadState.LOADING
            displayList.isEmpty() && archivedGroupConversations.isEmpty() -> LoadState.EMPTY
            else -> LoadState.CONTENT
        }
        else -> when {
            displayList == null -> LoadState.LOADING
            displayList.isEmpty() -> LoadState.EMPTY
            else -> LoadState.CONTENT
        }
    }

    val safeList = displayList ?: emptyList()
    val safeGroups = currentGroups ?: emptyList()

    val isSelectionMode = selectedIds.isNotEmpty()
    // Selection can hold peer device ids (Chats / Archived rows) and circle ids (Circles tab and
    // the circle section of the Archived tab). Bulk actions dispatch per id kind through this set;
    // selection is cleared on tab switch so the two id spaces never mix on the single-type tabs.
    val circleIdSet = (safeGroups.map { it.circle.id } + archivedGroupConversations.map { it.circle.id }).toSet()
    val selectedCircleIds = selectedIds.filter { it in circleIdSet }
    val selectedPeerIds = selectedIds.filterNot { it in circleIdSet }
    val allCurrentIds = when (currentTab) {
        MessagesTab.CHATS -> safeList.map { it.peer.deviceId }.toSet()
        MessagesTab.CIRCLES -> safeGroups.map { it.circle.id }.toSet()
        MessagesTab.ARCHIVED ->
            safeList.map { it.peer.deviceId }.toSet() + archivedGroupConversations.map { it.circle.id }
    }
    val allSelected = allCurrentIds.isNotEmpty() && selectedIds.containsAll(allCurrentIds)
    val archivingToArchive = currentTab != MessagesTab.ARCHIVED
    // When every selected conversation is already read, the bulk mark-read action would be a no-op,
    // so the button flips to "Mark unread" (re-flag) instead. A mixed selection keeps "Mark read".
    // Circle unread counts live on the group summaries rather than the per-peer map.
    val groupUnreadById = (safeGroups + archivedGroupConversations).associate { it.circle.id to it.unread }
    val selectedAllRead = selectedIds.isNotEmpty() &&
        selectedIds.all { (unreadCounts[it] ?: groupUnreadById[it] ?: 0) == 0 }

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
            title = { Text(pluralStringResource(R.plurals.conv_delete_count, count, count)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.conv_delete_locally)) },
                        supportingContent = { Text(stringResource(R.string.conv_delete_locally_sub)) },
                        modifier = Modifier.clickable {
                            selectedPeerIds.forEach { vm.deleteConversation(it) }
                            selectedCircleIds.forEach { vm.deleteCircleAndConversation(it) }
                            showBulkDeleteDialog = false
                            selectedIds = emptySet()
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    // Remote purge only exists for 1:1 conversations - a circle is a client-side
                    // grouping with no remote conversation object - so hide "delete both" when the
                    // selection is circles only (the Circles tab always is).
                    if (selectedPeerIds.isNotEmpty()) {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.conv_delete_both)) },
                            supportingContent = { Text(stringResource(R.string.conv_bulk_delete_both_sub)) },
                            modifier = Modifier.clickable {
                                selectedPeerIds.forEach {
                                    vm.deleteConversation(it)
                                    vm.deleteConversationFromRemote(it)
                                }
                                selectedCircleIds.forEach { vm.deleteCircleAndConversation(it) }
                                showBulkDeleteDialog = false
                                selectedIds = emptySet()
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showBulkDeleteDialog = false }) { Text(stringResource(R.string.common_cancel)) }
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
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.conv_cd_exit_selection))
                            }
                        }
                    },
                    title = {
                        if (isSelectionMode) {
                            Text(pluralStringResource(R.plurals.contacts_selected_count, selectedIds.size, selectedIds.size), fontWeight = FontWeight.SemiBold)
                        } else {
                            Text(stringResource(R.string.tab_messages), fontWeight = FontWeight.SemiBold)
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
                                    contentDescription = stringResource(R.string.conv_cd_select_all)
                                )
                            }
                        } else {
                            // Search + Sort live on CHATS and CIRCLES — the two `conversations`
                            // flows that actually apply them. Hidden on ARCHIVED because that
                            // tab's VM flow doesn't run the search/sort pipeline, so a button
                            // there would toggle state and surface nothing (silent no-op).
                            val showSearchAndSort = currentTab == MessagesTab.CHATS ||
                                currentTab == MessagesTab.CIRCLES
                            if (showSearchAndSort) {
                                IconButton(onClick = {
                                    showSearch = !showSearch
                                    if (!showSearch) vm.setSearchQuery("")
                                }) {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = stringResource(R.string.common_search),
                                        tint = if (showSearch) MaterialTheme.colorScheme.primary
                                               else LocalContentColor.current
                                    )
                                }
                                Box {
                                    IconButton(onClick = { showSortMenu = true }) {
                                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.common_sort))
                                    }
                                    DropdownMenu(
                                        expanded = showSortMenu,
                                        onDismissRequest = { showSortMenu = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.conv_sort_date)) },
                                            onClick = { vm.setSortOrder(SortOrder.DATE); showSortMenu = false },
                                            leadingIcon = { if (sortOrder == SortOrder.DATE) Icon(Icons.Default.Check, null) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.common_name)) },
                                            onClick = { vm.setSortOrder(SortOrder.NAME); showSortMenu = false },
                                            leadingIcon = { if (sortOrder == SortOrder.NAME) Icon(Icons.Default.Check, null) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.conv_sort_unread)) },
                                            onClick = { vm.setSortOrder(SortOrder.UNREAD); showSortMenu = false },
                                            leadingIcon = { if (sortOrder == SortOrder.UNREAD) Icon(Icons.Default.Check, null) }
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = { selectedIds = allCurrentIds }) {
                                Icon(Icons.Default.CheckBoxOutlineBlank, contentDescription = stringResource(R.string.conv_cd_select_all))
                            }
                        }
                    }
                )
                AnimatedVisibility(
                    visible = showSearch && !isSelectionMode &&
                        (currentTab == MessagesTab.CHATS || currentTab == MessagesTab.CIRCLES),
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { vm.setSearchQuery(it) },
                        placeholder = { Text(stringResource(R.string.conv_search_placeholder)) },
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
                        selected = currentTab == MessagesTab.CHATS,
                        onClick = { selectedTab = MessagesTab.CHATS.ordinal },
                        text = { TabLabelWithBadge(stringResource(R.string.conv_tab_chats), chatsUnreadTotal) }
                    )
                    Tab(
                        selected = currentTab == MessagesTab.CIRCLES,
                        onClick = { selectedTab = MessagesTab.CIRCLES.ordinal },
                        text = { TabLabelWithBadge(stringResource(R.string.conv_tab_circles), circlesUnreadTotal) }
                    )
                    Tab(
                        selected = currentTab == MessagesTab.ARCHIVED,
                        onClick = { selectedTab = MessagesTab.ARCHIVED.ordinal },
                        text = { Text(stringResource(R.string.conv_tab_archived)) }
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
                        // Read/unread queries key on `peerId`, which for circle rows holds the
                        // circle id (the thread key), so mixed selections pass through unchanged.
                        if (selectedAllRead) {
                            BulkActionButton(
                                icon = { Icon(Icons.Default.MarkChatUnread, contentDescription = null) },
                                label = stringResource(R.string.conv_mark_unread),
                                onClick = {
                                    vm.markUnreadMultiple(selectedIds.toList())
                                    selectedIds = emptySet()
                                }
                            )
                        } else {
                            BulkActionButton(
                                icon = { Icon(Icons.Default.DoneAll, contentDescription = null) },
                                label = stringResource(R.string.conv_mark_read),
                                onClick = {
                                    vm.markReadMultiple(selectedIds.toList())
                                    selectedIds = emptySet()
                                }
                            )
                        }
                        BulkActionButton(
                            icon = {
                                Icon(
                                    if (archivingToArchive) Icons.Default.Archive else Icons.Default.Unarchive,
                                    contentDescription = null
                                )
                            },
                            label = if (archivingToArchive) stringResource(R.string.conv_archive) else stringResource(R.string.conv_unarchive),
                            onClick = {
                                selectedPeerIds.forEach { vm.archiveConversation(it, archivingToArchive) }
                                selectedCircleIds.forEach { vm.archiveCircle(it, archivingToArchive) }
                                selectedIds = emptySet()
                            }
                        )
                        BulkActionButton(
                            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
                            label = stringResource(R.string.common_delete),
                            onClick = { showBulkDeleteDialog = true }
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (!isSelectionMode) {
                when (currentTab) {
                    MessagesTab.CHATS -> FloatingActionButton(onClick = { showContactPicker = true }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.conv_cd_new_message))
                    }
                    MessagesTab.CIRCLES -> FloatingActionButton(onClick = onCreateCircle) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.circles_new))
                    }
                    MessagesTab.ARCHIVED -> Unit /* no FAB on the Archive sub-tab */
                }
            }
        }
    ) { padding ->
        AnimatedContent(
            targetState = currentTab to loadState,
            transitionSpec = {
                fadeIn(tween(300)) togetherWith fadeOut(tween(200))
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            label = "conversations_state"
        ) { (_, state) ->
            when (state) {
                LoadState.LOADING -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(5) { ConversationShimmerRow() }
                    }
                }
                LoadState.EMPTY -> {
                    when (currentTab) {
                        MessagesTab.CIRCLES -> {
                            EmptyState(
                                icon = Icons.Default.Group,
                                title = stringResource(R.string.circles_empty_title),
                                subtitle = stringResource(R.string.circles_empty_body)
                            )
                        }
                        else -> {
                            val emptyTitle = when {
                                searchQuery.isNotBlank() -> stringResource(R.string.conv_empty_no_results, searchQuery)
                                currentTab == MessagesTab.ARCHIVED -> stringResource(R.string.conv_empty_archived_title)
                                else -> stringResource(R.string.conv_empty_title)
                            }
                            val emptySubtitle = when {
                                searchQuery.isNotBlank() -> stringResource(R.string.conv_empty_no_results_sub)
                                currentTab == MessagesTab.ARCHIVED -> stringResource(R.string.conv_empty_archived_sub)
                                else -> stringResource(R.string.conv_empty_sub)
                            }
                            EmptyState(
                                icon = if (currentTab == MessagesTab.CHATS) Icons.Default.ChatBubbleOutline else Icons.Default.Archive,
                                title = emptyTitle,
                                subtitle = emptySubtitle
                            )
                        }
                    }
                }
                LoadState.CONTENT -> {
                    if (currentTab == MessagesTab.CIRCLES) {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(safeGroups, key = { it.circle.id }) { group ->
                                val isSelected = group.circle.id in selectedIds
                                CircleRow(
                                    group = group,
                                    isSelected = isSelected,
                                    isSelectionMode = isSelectionMode,
                                    onClick = { onOpenGroup(group.circle.id) },
                                    onToggleSelect = {
                                        selectedIds = if (isSelected)
                                            selectedIds - group.circle.id
                                        else
                                            selectedIds + group.circle.id
                                    },
                                    onEnterSelectionMode = {
                                        selectedIds = setOf(group.circle.id)
                                    }
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 72.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    } else {
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
                            // Archived circles share the Archived tab with archived 1:1 chats.
                            // Same row + selection affordances as the Circles tab; unarchive and
                            // delete run through the bulk-action bar like archived chats do.
                            if (currentTab == MessagesTab.ARCHIVED) {
                                items(archivedGroupConversations, key = { it.circle.id }) { group ->
                                    val isSelected = group.circle.id in selectedIds
                                    CircleRow(
                                        group = group,
                                        isSelected = isSelected,
                                        isSelectionMode = isSelectionMode,
                                        onClick = { onOpenGroup(group.circle.id) },
                                        onToggleSelect = {
                                            selectedIds = if (isSelected)
                                                selectedIds - group.circle.id
                                            else
                                                selectedIds + group.circle.id
                                        },
                                        onEnterSelectionMode = {
                                            selectedIds = setOf(group.circle.id)
                                        }
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
    }
}

/**
 * A sub-tab label with a trailing unread badge. Lets the Chats / Circles tabs surface unread that
 * lives on a tab the user isn't currently viewing, mirroring the per-row unread badges. The badge
 * is hidden at zero; counts above 99 collapse to "99+".
 */
@Composable
private fun TabLabelWithBadge(label: String, unread: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(label)
        if (unread > 0) {
            Badge(containerColor = MaterialTheme.colorScheme.primary) {
                Text(if (unread > 99) "99+" else "$unread")
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

/** Mirrors the row layout of `CircleListScreen` for the icon and unread badge, and the
 *  trailing "date-then-badge" idiom of `ConversationRow` (1:1 chats) so the Circles /
 *  Archived tabs surface the same last-message timestamp as the Chats tab. Long-press
 *  enters the same selection mode as chat rows (mark read / archive / delete via the
 *  bulk-action bar). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CircleRow(
    group: GroupConversationSummary,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    onClick: () -> Unit,
    onToggleSelect: () -> Unit = {},
    onEnterSelectionMode: () -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current
    Surface(
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(group.circle.name, fontWeight = FontWeight.Medium) },
            supportingContent = {
                val preview = group.lastMessage?.content
                Text(
                    preview ?: stringResource(R.string.circles_member_count, group.memberCount),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            leadingContent = {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.tertiaryContainer
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isSelected) Icons.Default.Check else Icons.Default.Group,
                        contentDescription = null,
                        tint = if (isSelected) MaterialTheme.colorScheme.onPrimary
                               else MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            },
            trailingContent = {
                // Mirrors `ConversationRow`'s "date on top, badge below" idiom so rows in
                // the Circles / Archived tabs read their timestamp exactly like rows in the
                // Chats tab. When the circle has no messages yet, the supportingContent
                // already falls back to "X members" - the column just stays empty so the
                // row is visually balanced with chat rows that always carry a date.
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val lastTs = group.lastMessage?.timestamp
                    if (lastTs != null) {
                        Text(
                            formatTime(lastTs),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (group.unread > 0) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (group.unread > 0) {
                        Badge(containerColor = MaterialTheme.colorScheme.primary) { Text("${group.unread}") }
                    }
                }
            },
            modifier = Modifier.combinedClickable(
                onClick = { if (isSelectionMode) onToggleSelect() else onClick() },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (isSelectionMode) onToggleSelect() else onEnterSelectionMode()
                }
            )
        )
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
            title = { Text(stringResource(R.string.conv_delete_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.conv_delete_message, conv.peer.displayName))
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.conv_delete_locally)) },
                        supportingContent = { Text(stringResource(R.string.conv_delete_locally_sub)) },
                        modifier = Modifier.clickable { onDeleteLocal(); showDeleteDialog = false },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.conv_delete_both)) },
                        supportingContent = { Text(stringResource(R.string.conv_delete_both_sub)) },
                        modifier = Modifier.clickable { onDeleteLocal(); onDeleteRemote(); showDeleteDialog = false },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.common_cancel)) }
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
                        contentDescription = stringResource(R.string.conv_messages_blocked),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        supportingContent = {
            val preview = when {
                isBlocked -> stringResource(R.string.conv_messages_blocked)
                summary.lastMessage.isMine -> stringResource(R.string.conv_preview_mine, summary.lastMessage.content)
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
        title = { Text(stringResource(R.string.conv_new_message_title)) },
        text = {
            if (peers.isEmpty()) {
                Text(
                    stringResource(R.string.conv_picker_empty),
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
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

private fun formatTime(millis: Long): String =
    com.locapeer.util.DisplayFormat.timeFormat().format(Date(millis))
