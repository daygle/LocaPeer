package com.locapeer.messaging

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.locapeer.ui.components.ConversationShimmerRow
import com.locapeer.ui.components.EmptyState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class LoadState { LOADING, EMPTY, CONTENT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    onOpenChat: (String) -> Unit,
    vm: MessagingViewModel = hiltViewModel()
) {
    val conversations by vm.conversations.collectAsState()
    // null = still loading (waiting for first emission), empty list = loaded but empty
    val loadState = when {
        conversations.isEmpty() -> LoadState.EMPTY
        else -> LoadState.CONTENT
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Messages", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
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
                        items(conversations, key = { it.peer.deviceId }) { conv ->
                            val unread by vm.getUnreadCount(conv.peer.deviceId)
                                .collectAsState(initial = 0)
                            ConversationRow(
                                summary = conv,
                                unreadCount = unread,
                                onClick = { onOpenChat(conv.peer.deviceId) }
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
private fun ConversationRow(
    summary: ConversationSummary,
    unreadCount: Int,
    onClick: () -> Unit
) {
    val hasUnread = unreadCount > 0
    ListItem(
        leadingContent = {
            AvatarCircle(
                name = summary.peer.displayName,
                hasUnread = hasUnread
            )
        },
        headlineContent = {
            Text(
                summary.peer.displayName,
                fontWeight = if (hasUnread) FontWeight.Bold else FontWeight.Normal
            )
        },
        supportingContent = {
            Text(
                summary.lastMessage.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (hasUnread) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (hasUnread) FontWeight.Medium else FontWeight.Normal
            )
        },
        trailingContent = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    formatTime(summary.lastMessage.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hasUnread) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (hasUnread) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.primary
                    ) {
                        Text("$unreadCount")
                    }
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
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

private fun formatTime(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
