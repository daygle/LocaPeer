package com.locapeer.circles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    val contacts by vm.contacts.collectAsState()
    val existingCircle by remember(circleId) {
        if (circleId != null) vm.observeCircle(circleId) else kotlinx.coroutines.flow.flowOf(null)
    }.collectAsState(initial = null)
    val existingMembers by remember(circleId) {
        if (circleId != null) vm.observeMembers(circleId) else kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(initial = emptyList())

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
                title = { Text(stringResource(if (isEdit) R.string.circles_edit_title else R.string.circles_new_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    TextButton(
                        enabled = name.isNotBlank() && selected.isNotEmpty(),
                        onClick = {
                            if (isEdit && circleId != null) {
                                vm.renameCircle(circleId, name)
                                vm.setMembers(circleId, selected.toList())
                            } else {
                                vm.createCircle(name, selected.toList())
                            }
                            onNavigateBack()
                        }
                    ) { Text(stringResource(R.string.common_save)) }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
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
                        leadingContent = {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { on ->
                                    selected = if (on) selected + contact.deviceId else selected - contact.deviceId
                                }
                            )
                        },
                        modifier = Modifier.clickable {
                            selected = if (checked) selected - contact.deviceId else selected + contact.deviceId
                        }
                    )
                }
            }
        }
    }
}
