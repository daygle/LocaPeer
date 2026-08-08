package com.locapeer.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import com.locapeer.ui.theme.locaPeerTopAppBarColors
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.locapeer.R
import com.locapeer.supervised.SupervisionGate
import com.locapeer.ui.components.CardDivider
import kotlin.time.Duration.Companion.seconds

/**
 * Settings landing page. Rather than one long scroll of every control, this shows the profile
 * header, any active temporary shares, and a list of category rows that each open a focused
 * sub-screen (see SettingsSectionScreens.kt). This keeps the top level short and scannable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToPeerSharing: (peerId: String, peerName: String) -> Unit = { _, _ -> },
    onNavigateToAbout: () -> Unit = {},
    onNavigateToLocationPrivacy: () -> Unit = {},
    onNavigateToSecurity: () -> Unit = {},
    onNavigateToMap: () -> Unit = {},
    onNavigateToPerformance: () -> Unit = {},
    onNavigateToUnits: () -> Unit = {},
    onNavigateToRetention: () -> Unit = {},
    onNavigateToAppearance: () -> Unit = {},
    onNavigateToBackup: () -> Unit = {},
    vm: SettingsViewModel = hiltViewModel(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val publicKeyHex by vm.publicKeyHex.collectAsStateWithLifecycle()
    val profileQr by vm.profileQr.collectAsStateWithLifecycle()
    val activeTempShares by vm.activeTempShares.collectAsStateWithLifecycle()

    val unlockState by vm.unlockState.collectAsStateWithLifecycle()
    var sessionUnlocked by remember { mutableStateOf(false) }
    if (settings.supervisedModeEnabled && !sessionUnlocked) {
        SupervisionGate(
            unlockState = unlockState,
            onRequestAccess = { vm.requestSettingsUnlock() },
            onReset = { vm.resetUnlockState() },
        ) { sessionUnlocked = true }
        return
    }

    var showNameDialog by remember { mutableStateOf(false) }
    var nameInput by remember(settings.displayName) { mutableStateOf(settings.displayName) }
    var showProfileQr by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }, colors = locaPeerTopAppBarColors()) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── Profile ───────────────────────────────────────────────────────
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val avatarColor = if (settings.pinColor.isNotEmpty())
                        Color(settings.pinColor.toColorInt())
                    else MaterialTheme.colorScheme.primaryContainer
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(avatarColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            settings.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (settings.pinColor.isNotEmpty()) Color.White
                            else MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            settings.displayName.ifBlank { stringResource(R.string.settings_no_name_set) },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (publicKeyHex.isNotEmpty()) {
                            Text(
                                publicKeyHex.take(16) + "…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { nameInput = settings.displayName; showNameDialog = true }) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.settings_edit_name))
                        }
                        OutlinedButton(onClick = { showProfileQr = true }) {
                            Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.settings_my_qr))
                        }
                    }
                }
            }

            // ── Active Temporary Shares ────────────────────────────────────────
            if (activeTempShares.isNotEmpty()) {
                item {
                    // One shared clock for the whole card instead of a per-share ticker,
                    // so N active shares don't spawn N coroutines all updating every second.
                    val nowSec by produceState(initialValue = System.currentTimeMillis() / 1000L) {
                        while (true) {
                            value = System.currentTimeMillis() / 1000L
                            kotlinx.coroutines.delay(1.seconds)
                        }
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    ) {
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Timelapse,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    stringResource(R.string.settings_temporary_location_share),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            activeTempShares.forEachIndexed { index, (peer, config) ->
                                val endsAt = config.temporaryShareEndsAtEpochSeconds ?: 0L
                                if (index > 0) CardDivider()
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onNavigateToPeerSharing(peer.deviceId, peer.displayName) }
                                        .padding(horizontal = 20.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            peer.displayName,
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                        Text(
                                            stringResource(
                                                R.string.peer_temp_share_active_time_left,
                                                peer.displayName,
                                                com.locapeer.util.DisplayFormat.humanizeRemaining(endsAt - nowSec)
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    IconButton(onClick = { vm.stopTemporaryShare(peer.deviceId) }) {
                                        Icon(
                                            Icons.Default.Stop,
                                            contentDescription = stringResource(R.string.peer_temp_share_stop),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Category list ──────────────────────────────────────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        CategoryRow(
                            icon = Icons.Default.LocationOn,
                            title = stringResource(R.string.settings_section_location_privacy),
                            subtitle = stringResource(R.string.settings_category_location_privacy_subtitle),
                            onClick = onNavigateToLocationPrivacy
                        )
                        CardDivider()
                        CategoryRow(
                            icon = Icons.Default.Shield,
                            title = stringResource(R.string.settings_section_security),
                            subtitle = stringResource(R.string.settings_category_security_subtitle),
                            onClick = onNavigateToSecurity
                        )
                        CardDivider()
                        CategoryRow(
                            icon = Icons.Default.Map,
                            title = stringResource(R.string.settings_section_map),
                            subtitle = stringResource(R.string.settings_category_map_subtitle),
                            onClick = onNavigateToMap
                        )
                        CardDivider()
                        CategoryRow(
                            icon = Icons.Default.Timer,
                            title = stringResource(R.string.settings_section_battery_performance),
                            subtitle = stringResource(R.string.settings_category_performance_subtitle),
                            onClick = onNavigateToPerformance
                        )
                        CardDivider()
                        CategoryRow(
                            icon = Icons.Default.Straighten,
                            title = stringResource(R.string.settings_section_units_display),
                            subtitle = stringResource(R.string.settings_category_units_subtitle),
                            onClick = onNavigateToUnits
                        )
                        CardDivider()
                        CategoryRow(
                            icon = Icons.Default.History,
                            title = stringResource(R.string.settings_section_retention),
                            subtitle = stringResource(R.string.settings_category_retention_subtitle),
                            onClick = onNavigateToRetention
                        )
                        CardDivider()
                        CategoryRow(
                            icon = Icons.Default.Palette,
                            title = stringResource(R.string.settings_section_appearance),
                            subtitle = stringResource(R.string.settings_category_appearance_subtitle),
                            onClick = onNavigateToAppearance
                        )
                        CardDivider()
                        CategoryRow(
                            icon = Icons.Default.VpnKey,
                            title = stringResource(R.string.settings_section_backup_keys),
                            subtitle = stringResource(R.string.settings_category_backup_subtitle),
                            onClick = onNavigateToBackup
                        )
                        CardDivider()
                        CategoryRow(
                            icon = Icons.Default.Info,
                            title = stringResource(R.string.settings_about_locapeer),
                            subtitle = stringResource(R.string.settings_about_subtitle),
                            onClick = onNavigateToAbout
                        )
                    }
                }
            }
        }
    }

    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text(stringResource(R.string.settings_edit_display_name)) },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text(stringResource(R.string.settings_display_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.updateDisplayName(nameInput); showNameDialog = false },
                    enabled = nameInput.isNotBlank()
                ) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = { TextButton(onClick = { showNameDialog = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    if (showProfileQr) {
        AlertDialog(
            onDismissRequest = { showProfileQr = false },
            title = { Text(stringResource(R.string.settings_my_invite_qr)) },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(R.string.settings_invite_qr_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    profileQr?.let { bmp ->
                        Image(bitmap = bmp.asImageBitmap(), contentDescription = stringResource(R.string.settings_invite_qr_cd), modifier = Modifier.size(220.dp))
                    } ?: CircularProgressIndicator()
                }
            },
            confirmButton = { TextButton(onClick = { showProfileQr = false }) { Text(stringResource(R.string.common_done)) } }
        )
    }
}

/** A category entry on the Settings landing page: leading icon, title, subtitle and a chevron. */
@Composable
private fun CategoryRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
