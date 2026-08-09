package com.locapeer.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.locapeer.R
import com.locapeer.data.entity.PendingMessageEntity
import com.locapeer.ui.theme.locaPeerTopAppBarColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Drill-in view of the relay outbox: every event still waiting to reach a relay,
 * grouped by target relay (oldest first within each group). Each relay section
 * shows its queued count, and each message card shows the event kind, when it was
 * queued, and an expandable copy of the raw Nostr message.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueuedMessagesScreen(
    onNavigateBack: () -> Unit,
    vm: QueuedMessagesViewModel = hiltViewModel(),
) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val grouped = remember(messages) { messages.groupBy { it.relayUrl } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.queued_messages_title)) },
                colors = locaPeerTopAppBarColors(),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    if (messages.isNotEmpty()) {
                        TextButton(onClick = { vm.flushNow() }) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.queued_messages_flush))
                        }
                    }
                },
            )
        }
    ) { padding ->
        if (messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.settings_queued_none),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                grouped.forEach { (relayUrl, relayMessages) ->
                    item(key = "relay-$relayUrl") {
                        RelaySectionHeader(relayUrl, relayMessages.size)
                    }
                    items(relayMessages, key = { it.id }) { msg ->
                        QueuedMessageCard(msg)
                    }
                }
            }
        }
    }
}

@Composable
private fun RelaySectionHeader(relayUrl: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Inbox,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            relayUrl,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            pluralStringResource(R.plurals.settings_queued_message_count, count, count),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun QueuedMessageCard(msg: PendingMessageEntity) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                stringResource(R.string.queued_message_kind, eventKind(msg.content)),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                stringResource(R.string.queued_message_queued_at, queuedTime(msg.createdAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(
                    if (expanded) stringResource(R.string.queued_message_hide)
                    else stringResource(R.string.queued_message_view)
                )
            }
            if (expanded) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        msg.content,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/** Pull the Nostr event kind out of the queued wire message (e.g. `"kind":1040`). */
private fun eventKind(content: String): Int {
    val match = Regex("\"kind\"\\s*:\\s*(\\d+)").find(content)
    return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
}

/** Date + clock time honouring the user's 12/24-hour preference. */
private fun queuedTime(createdAt: Long): String {
    val format = SimpleDateFormat(
        "d MMM yyyy, " + com.locapeer.util.DisplayFormat.timePattern(),
        Locale.getDefault()
    )
    return format.format(Date(createdAt))
}
