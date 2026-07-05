package com.locapeer.invite

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.locapeer.data.entity.PendingRequestEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingRequestsScreen(
    onNavigateBack: () -> Unit,
    onOpenRequest: (pubkey: String, name: String, relay: String, isRoleChange: Boolean, requestedRole: String?) -> Unit,
    vm: PendingRequestsViewModel = hiltViewModel()
) {
    val requests by vm.requests.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pending Requests") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (requests.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Inbox,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "No pending requests",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(requests, key = { it.senderPubkey }) { request ->
                    PendingRequestRow(
                        request = request,
                        onReview = {
                            onOpenRequest(
                                request.senderPubkey,
                                request.senderName,
                                request.senderRelayUrl,
                                request.isRoleChange,
                                request.requestedRole
                            )
                        },
                        onDecline = { vm.decline(request) }
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

@Composable
private fun PendingRequestRow(
    request: PendingRequestEntity,
    onReview: () -> Unit,
    onDecline: () -> Unit
) {
    val timeLabel = remember(request.receivedAt) {
        SimpleDateFormat("MMM d, ${com.locapeer.util.DisplayFormat.timePattern()}", Locale.getDefault()).format(Date(request.receivedAt))
    }
    val requestedRoleLabel = when (request.requestedRole) {
        "SEND_RECEIVE" -> "Send/Receive"
        "SEND" -> "Send"
        "RECEIVE" -> "Receive"
        "NONE" -> "No Sharing"
        else -> null
    }
    val subtitle = when {
        request.isRoleChange && requestedRoleLabel != null -> "Requesting: $requestedRoleLabel · $timeLabel"
        request.isRoleChange -> "Role change request · $timeLabel"
        else -> "New sharing request · $timeLabel"
    }

    ListItem(
        headlineContent = { Text(request.senderName) },
        supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodySmall) },
        leadingContent = {
            Icon(
                if (request.isRoleChange) Icons.Default.SwapHoriz else Icons.Default.PersonAdd,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onReview) { Text("Review") }
                IconButton(onClick = onDecline) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Decline",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    )
}
