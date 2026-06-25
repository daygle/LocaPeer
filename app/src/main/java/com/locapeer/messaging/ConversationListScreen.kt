package com.locapeer.messaging

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    onOpenChat: (String) -> Unit,
    vm: MessagingViewModel = hiltViewModel()
) {
    val conversations by vm.conversations.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Messages") }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            items(conversations, key = { it.peer.deviceId }) { conv ->
                val unread by vm.getUnreadCount(conv.peer.deviceId).collectAsState(initial = 0)
                ConversationRow(
                    summary = conv,
                    unreadCount = unread,
                    onClick = { onOpenChat(conv.peer.deviceId) }
                )
                HorizontalDivider()
            }
            if (conversations.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillParentMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No conversations yet.\nOpen the map and tap a person to message them.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
    ListItem(
        headlineContent = { Text(summary.peer.displayName) },
        supportingContent = {
            Text(
                summary.lastMessage.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    formatTime(summary.lastMessage.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (unreadCount > 0) {
                    Badge { Text("$unreadCount") }
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
