package com.locapeer.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.locapeer.R
import com.locapeer.ui.theme.locaPeerTopAppBarColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelaySettingsScreen(
    onNavigateBack: () -> Unit,
    vm: RelaySettingsViewModel = hiltViewModel(),
) {
    val settings by vm.prefs.settings.collectAsStateWithLifecycle(initialValue = null)
    val status by vm.relayStatus.collectAsStateWithLifecycle()
    val customRelays = settings?.customRelays ?: emptyList()
    val usePublic = settings?.usePublicRelays ?: true

    var newRelay by remember { mutableStateOf("") }
    var errorRes by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = locaPeerTopAppBarColors(),
                title = { Text(stringResource(R.string.settings_relays)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Text(
                stringResource(R.string.relays_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp)
            )

            SectionHeader(stringResource(R.string.relays_primary_header))
            RelayRow(url = vm.primaryRelay, connected = status[vm.primaryRelay] == true, onRemove = null)

            Spacer(Modifier.height(16.dp))
            SectionHeader(stringResource(R.string.relays_public_header))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.relays_public_toggle), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.relays_public_toggle_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = usePublic, onCheckedChange = { vm.setUsePublicRelays(it) })
            }
            if (usePublic) {
                vm.builtInPublicRelays.forEach { url ->
                    RelayRow(url = url, connected = status[url] == true, onRemove = null)
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionHeader(stringResource(R.string.relays_custom_header))
            if (customRelays.isEmpty()) {
                Text(
                    stringResource(R.string.relays_custom_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            } else {
                customRelays.forEach { url ->
                    RelayRow(
                        url = url,
                        connected = status[url] == true,
                        onRemove = { vm.removeCustomRelay(url, customRelays) }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = newRelay,
                onValueChange = { newRelay = it; errorRes = null },
                singleLine = true,
                isError = errorRes != null,
                placeholder = { Text(stringResource(R.string.relays_add_hint)) },
                supportingText = errorRes?.let { resId ->
                    { Text(stringResource(resId), color = MaterialTheme.colorScheme.error) }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    errorRes = tryAdd(vm, newRelay, customRelays) { newRelay = "" }
                }),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { errorRes = tryAdd(vm, newRelay, customRelays) { newRelay = "" } },
                enabled = newRelay.isNotBlank(),
                modifier = Modifier.align(Alignment.End)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.relays_add))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Runs the add, returning a string-res id to show on failure or null on success. */
private fun tryAdd(
    vm: RelaySettingsViewModel,
    raw: String,
    current: List<String>,
    onSuccess: () -> Unit,
): Int? = when (vm.addCustomRelay(raw, current)) {
    RelaySettingsViewModel.RelayAddResult.OK -> { onSuccess(); null }
    RelaySettingsViewModel.RelayAddResult.INVALID -> R.string.relays_add_invalid
    RelaySettingsViewModel.RelayAddResult.DUPLICATE -> R.string.relays_add_duplicate
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun RelayRow(url: String, connected: Boolean, onRemove: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (connected) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                url,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                stringResource(
                    if (connected) R.string.relays_status_connected else R.string.relays_status_disconnected
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.relays_remove))
            }
        }
    }
}
