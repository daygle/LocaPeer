package com.locapeer.invite

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.locapeer.data.entity.PeerEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncomingShareRequestScreen(
    senderPubkey: String,
    senderName: String,
    senderRelay: String,
    onDone: () -> Unit,
    vm: IncomingShareRequestViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsState()

    LaunchedEffect(state) {
        if (state is IncomingRequestState.Done) onDone()
    }

    var selectedRole by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Location Sharing Request") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "$senderName wants to share locations with you.",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                "Choose how you'd like to connect:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(4.dp))

            RoleCard(
                title = "Send/Receive Location",
                description = "Share your location with $senderName and see theirs",
                icon = Icons.Default.SyncAlt,
                selected = selectedRole == PeerEntity.ROLE_SEND_RECEIVE,
                onClick = { selectedRole = PeerEntity.ROLE_SEND_RECEIVE }
            )
            RoleCard(
                title = "Send Location",
                description = "Share your location with $senderName, but don't see theirs",
                icon = Icons.Default.LocationOn,
                selected = selectedRole == PeerEntity.ROLE_SEND,
                onClick = { selectedRole = PeerEntity.ROLE_SEND }
            )
            RoleCard(
                title = "Receive Location",
                description = "See $senderName's location without sharing yours",
                icon = Icons.Default.LocationOff,
                selected = selectedRole == PeerEntity.ROLE_RECEIVE,
                onClick = { selectedRole = PeerEntity.ROLE_RECEIVE }
            )

            Spacer(Modifier.weight(1f))

            Button(
                onClick = {
                    val role = selectedRole ?: return@Button
                    vm.accept(senderPubkey, senderName, senderRelay, role)
                },
                enabled = selectedRole != null && state !is IncomingRequestState.Loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state is IncomingRequestState.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Confirm")
                }
            }

            OutlinedButton(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Decline")
            }
        }
    }
}

@Composable
private fun RoleCard(
    title: String,
    description: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outlineVariant
    val containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surface

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = MaterialTheme.shapes.medium
            )
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = containerColor
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
