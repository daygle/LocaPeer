package com.locapeer.circles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import com.locapeer.ui.theme.locaPeerTopAppBarColors
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.locapeer.R

/**
 * Create a new circle or edit an existing one's name and membership. [circleId] null = create.
 * Membership is chosen from existing contacts only (circles are a client-side grouping of people
 * you already share with / message).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CircleEditScreen(
    circleId: String?,
    onNavigateBack: () -> Unit,
    vm: CirclesViewModel = hiltViewModel()
) {
    val isEdit = circleId != null
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val myPub by vm.myPubkeyHex.collectAsStateWithLifecycle()
    val existingCircle by remember(circleId) {
        if (circleId != null) vm.observeCircle(circleId) else kotlinx.coroutines.flow.flowOf(null)
    }.collectAsStateWithLifecycle(initialValue = null)
    val existingMembers by remember(circleId) {
        if (circleId != null) vm.observeMembers(circleId) else kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    // Only the circle's owner may rename it or change membership. Creating a new circle (isEdit =
    // false) is always allowed. In edit mode we stay read-only until the circle row has loaded so a
    // non-owner never briefly sees editable controls. See CirclesViewModel.canEditCircle.
    val canEdit = !isEdit || (existingCircle != null && vm.canEditCircle(existingCircle, myPub))

    // Who owns this circle (edit/view mode only), shown so members can see who controls its name
    // and membership. "You" when this device is the owner, otherwise the contact's name (or Unknown
    // for a creator who isn't in contacts). A blank creator is a legacy circle with no recorded
    // owner, so nothing is shown. Held back until myPub loads so the owner never flashes as Unknown.
    val ownerPubkey = existingCircle?.creatorPubkey?.takeIf { it.isNotBlank() }
    val ownerName: String? = when {
        !isEdit || ownerPubkey == null || myPub.isBlank() -> null
        ownerPubkey == myPub -> stringResource(R.string.circles_owner_you)
        else -> contacts.find { it.deviceId == ownerPubkey }?.displayName?.ifBlank { null }
            ?: stringResource(R.string.fallback_unknown)
    }

    var name by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var seeded by remember { mutableStateOf(false) }

    // Seed the fields once the existing circle/members load (edit mode only).
    LaunchedEffect(existingCircle, existingMembers) {
        if (isEdit && !seeded && existingCircle != null) {
            name = existingCircle!!.name
            selected = existingMembers.toSet()
            seeded = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = locaPeerTopAppBarColors(),
                title = {
                    Text(
                        stringResource(
                            when {
                                !canEdit -> R.string.circles_view_title
                                isEdit -> R.string.circles_edit_title
                                else -> R.string.circles_new_title
                            }
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    // Save is shown only to a user who may edit this circle. Non-owners get a
                    // read-only view (see the banner below), so there is nothing to save.
                    if (canEdit) {
                        TextButton(
                            enabled = name.isNotBlank() && selected.isNotEmpty(),
                            onClick = {
                                if (circleId != null) {
                                    vm.renameCircle(circleId, name)
                                    vm.setMembers(circleId, selected.toList())
                                } else {
                                    vm.createCircle(name, selected.toList())
                                }
                                onNavigateBack()
                            }
                        ) { Text(stringResource(R.string.common_save)) }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Read-only banner for a non-owner: explains why the fields below are locked.
            if (!canEdit) {
                Text(
                    stringResource(R.string.circles_owner_only),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                readOnly = !canEdit,
                label = { Text(stringResource(R.string.circles_name_label)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Text(
                stringResource(R.string.circles_members_header, selected.size),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            // Surface the circle's owner (who alone can rename it / change members).
            ownerName?.let { owner ->
                Text(
                    stringResource(R.string.circles_owner_label, owner),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)
                )
            }
            if (contacts.isEmpty()) {
                Text(
                    stringResource(R.string.circles_no_contacts),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(contacts, key = { it.deviceId }) { contact ->
                    val checked = selected.contains(contact.deviceId)
                    ListItem(
                        headlineContent = {
                            Text(contact.displayName.ifBlank { stringResource(R.string.fallback_unknown) })
                        },
                        // Tag the owner's row so it's clear who controls the circle (the owner is
                        // also a member). Only appears when the creator is one of your contacts.
                        trailingContent = if (contact.deviceId == ownerPubkey) {
                            {
                                Text(
                                    stringResource(R.string.circles_owner_tag),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else null,
                        leadingContent = {
                            Checkbox(
                                checked = checked,
                                enabled = canEdit,
                                onCheckedChange = { on ->
                                    selected = if (on) selected + contact.deviceId else selected - contact.deviceId
                                }
                            )
                        },
                        // Row tap toggles membership only when the user may edit; a non-owner's
                        // view is read-only.
                        modifier = if (canEdit) Modifier.clickable {
                            selected = if (checked) selected - contact.deviceId else selected + contact.deviceId
                        } else Modifier
                    )
                }
            }
        }
    }
}
