package com.locapeer.invite

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import com.locapeer.ui.theme.locaPeerTopAppBarColors
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.locapeer.R
import com.locapeer.data.entity.PeerEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncomingShareRequestScreen(
    senderPubkey: String,
    senderName: String,
    senderRelay: String,
    isRoleChange: Boolean = false,
    requestedRole: String? = null,
    onDone: () -> Unit,
    vm: IncomingShareRequestViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        if (state is IncomingRequestState.Done) onDone()
    }

    // Pre-populate toggles from the suggested role (if any)
    var shareMyLocation by remember(requestedRole) {
        mutableStateOf(requestedRole == PeerEntity.ROLE_SEND || requestedRole == PeerEntity.ROLE_SEND_RECEIVE)
    }
    var seeTheirLocation by remember(requestedRole) {
        mutableStateOf(requestedRole == PeerEntity.ROLE_RECEIVE || requestedRole == PeerEntity.ROLE_SEND_RECEIVE
            || requestedRole == null)  // default to true for new requests
    }
    var messagingEnabled by remember(requestedRole) { mutableStateOf(true) }

    val derivedRole = when {
        shareMyLocation && seeTheirLocation -> PeerEntity.ROLE_SEND_RECEIVE
        shareMyLocation -> PeerEntity.ROLE_SEND
        seeTheirLocation -> PeerEntity.ROLE_RECEIVE
        else -> PeerEntity.ROLE_NONE
    }

    val title = if (isRoleChange) stringResource(R.string.incoming_update_title) else stringResource(R.string.incoming_new_title)
    val subtitle = when {
        isRoleChange -> stringResource(R.string.incoming_update_subtitle, senderName)
        else -> stringResource(R.string.incoming_new_subtitle, senderName)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(title) }, colors = locaPeerTopAppBarColors()) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(subtitle, style = MaterialTheme.typography.bodyLarge)

            Text(
                stringResource(R.string.incoming_section_location),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.incoming_share_my_location, senderName)) },
                    supportingContent = { Text(stringResource(R.string.incoming_share_my_location_sub)) },
                    leadingContent = {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = if (shareMyLocation) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingContent = {
                        Switch(checked = shareMyLocation, onCheckedChange = { shareMyLocation = it })
                    },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                )
                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                ListItem(
                    headlineContent = { Text(stringResource(R.string.incoming_see_their_location, senderName)) },
                    supportingContent = { Text(stringResource(R.string.incoming_see_their_location_sub)) },
                    leadingContent = {
                        Icon(
                            Icons.Default.Visibility,
                            contentDescription = null,
                            tint = if (seeTheirLocation) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingContent = {
                        Switch(checked = seeTheirLocation, onCheckedChange = { seeTheirLocation = it })
                    },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                )
            }

            HorizontalDivider()

            Text(
                stringResource(R.string.incoming_section_messaging),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.incoming_allow_messages)) },
                    supportingContent = { Text(stringResource(R.string.incoming_allow_messages_sub, senderName)) },
                    leadingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.Chat,
                            contentDescription = null,
                            tint = if (messagingEnabled) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = messagingEnabled,
                            onCheckedChange = { messagingEnabled = it }
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                )
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = {
                    vm.accept(senderPubkey, senderName, senderRelay, derivedRole, messagingEnabled)
                },
                enabled = state !is IncomingRequestState.Loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state is IncomingRequestState.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(if (isRoleChange) stringResource(R.string.common_save_changes) else stringResource(R.string.invite_tab_add_contact))
                }
            }

            OutlinedButton(
                onClick = { vm.decline(senderPubkey, senderRelay, isRoleChange) },
                enabled = state !is IncomingRequestState.Loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.common_decline))
            }
        }
    }
}

