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
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.locapeer.R
import com.locapeer.ui.components.CardDivider
import com.locapeer.ui.components.SettingsCard
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
    val disabledRelays = settings?.disabledRelayUrls ?: emptySet()

    var newRelay by remember { mutableStateOf("") }
    var errorRes by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = locaPeerTopAppBarColors(),
                title = { Text(stringResource(R.string.settings_relays)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                stringResource(R.string.relays_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Public Relays Section
            RelaySection(
                title = stringResource(R.string.relays_public_header),
                icon = Icons.Default.Public
            ) {
                vm.allBuiltInRelays.forEachIndexed { index, url ->
                    val isEnabled = url !in disabledRelays
                    RelayRow(
                        url = url,
                        connected = status[url] == true,
                        isEnabled = isEnabled,
                        onToggle = { vm.setRelayEnabled(url, it) },
                        onRemove = null
                    )
                    if (index < vm.allBuiltInRelays.size - 1) {
                        CardDivider()
                    }
                }
            }

            // Custom Relays Section
            RelaySection(
                title = stringResource(R.string.relays_custom_header),
                icon = Icons.Default.Storage
            ) {
                if (customRelays.isEmpty()) {
                    Text(
                        stringResource(R.string.relays_custom_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                } else {
                    customRelays.forEachIndexed { index, url ->
                        val isEnabled = url !in disabledRelays
                        RelayRow(
                            url = url,
                            connected = status[url] == true,
                            isEnabled = isEnabled,
                            onToggle = { vm.setRelayEnabled(url, it) },
                            onRemove = { vm.removeCustomRelay(url, customRelays) }
                        )
                        if (index < customRelays.size - 1) {
                            CardDivider()
                        }
                    }
                }

                CardDivider()

                // Add Relay Field
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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
                            if (newRelay.isNotBlank()) {
                                errorRes = tryAdd(vm, newRelay, customRelays) { newRelay = "" }
                            }
                        }),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { errorRes = tryAdd(vm, newRelay, customRelays) { newRelay = "" } },
                        enabled = newRelay.isNotBlank(),
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.relays_add))
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RelaySection(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    SettingsCard(headerIcon = icon, headerTitle = title, content = content)
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
private fun RelayRow(
    url: String,
    connected: Boolean,
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onRemove: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    when {
                        !isEnabled -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        connected -> Color(0xFF4CAF50)
                        else -> MaterialTheme.colorScheme.error
                    }
                )
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                url,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isEnabled) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Text(
                text = when {
                    !isEnabled -> stringResource(R.string.relays_status_disabled)
                    connected -> stringResource(R.string.relays_status_connected)
                    else -> stringResource(R.string.relays_status_disconnected)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = isEnabled,
            onCheckedChange = onToggle,
            modifier = Modifier.scaleSwitch(0.8f)
        )
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.relays_remove),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

// Helper to scale the switch down slightly to fit better in ListItems
private fun Modifier.scaleSwitch(scale: Float): Modifier = this.scale(scale)
