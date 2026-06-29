package com.locapeer.messaging

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.locapeer.data.entity.PeerEntity
import com.locapeer.ui.components.ConversationShimmerRow
import com.locapeer.ui.components.EmptyState
import com.locapeer.ui.components.RelayStatusChip
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
    val peers by vm.peers.collectAsState()
    val relayStatus by vm.relayStatus.collectAsState()
    val unreadCounts by vm.unreadCounts.collectAsState()
    var showContactPicker by remember { mutableStateOf(false) }

    // null = still loading (waiting for first emission), empty list = loaded but empty
    val loadState = when {
        conversations == null -> LoadState.LOADING
        conversations!!.isEmpty() -> LoadState.EMPTY
        else -> LoadState.CONTENT
    }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Messages", fontWeight = FontWeight.SemiBold)
                        RelayStatusChip(relayStatus = relayStatus)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showContactPicker = true }) {
                Icon(Icons.Default.Add, contentDescription = "New message")
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
                    EmptyState(
                        icon = Icons.Default.ChatBubbleOutline,
                        title = "No messages yet",
                        subtitle = "Open the map and tap a person\nto start a conversation"
                    )
                }
                LoadState.CONTENT -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(conversations!!, key = { it.peer.deviceId }) { conv ->
                            SwipeToDeleteConversation(
                                conv = conv,
                                unreadCount = unreadCounts[conv.peer.deviceId] ?: 0,
                                onClick = { onOpenChat(conv.peer.deviceId, conv.peer.displayName) },
                                onDelete = { vm.deleteConversation(conv.peer.deviceId) }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteConversation(
    conv: ConversationSummary,
    unreadCount: Int,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { it * 0.4f }
    )
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onDelete()
            dismissState.snapTo(SwipeToDismissBoxValue.Settled)
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("Delete conversation?") },
            text = { Text("All messages with ${conv.peer.displayName} will be removed from your device.") },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; onDelete() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            val alignment = Alignment.CenterEnd
            val color = MaterialTheme.colorScheme.errorContainer
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color)
                    .padding(horizontal = 20.dp),
                contentAlignment = alignment
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    ) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            ConversationRow(
                summary = conv,
                unreadCount = unreadCount,
                onClick = onClick,
                onLongClick = { showDeleteDialog = true }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    summary: ConversationSummary,
    unreadCount: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val hasUnread = unreadCount > 0
    val isBlocked = !summary.peer.messagingEnabled
    ListItem(
        leadingContent = {
            AvatarCircle(name = summary.peer.displayName, hasUnread = hasUnread)
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
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
