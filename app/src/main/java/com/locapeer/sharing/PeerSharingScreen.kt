package com.locapeer.sharing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.locapeer.R
import com.locapeer.supervised.SupervisionGate
import com.locapeer.supervised.SupervisionGateViewModel
import com.locapeer.ui.components.CardDivider
import com.locapeer.ui.theme.locaPeerTopAppBarColors
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerSharingScreen(
    peerId: String,
    peerName: String,
    onNavigateBack: () -> Unit,
    onNavigateToMap: (Double, Double) -> Unit = { _, _ -> },
    onNavigateToControls: () -> Unit = {},
    onNavigateToPrivacy: () -> Unit = {},
    onNavigateToAlerts: () -> Unit = {},
    onNavigateToSecurity: () -> Unit = {},
    onNavigateToGeofences: () -> Unit = {},
    onNavigateToLocationHistory: () -> Unit = {},
    onNavigateToMessaging: () -> Unit = {},
    onNavigateToRetention: () -> Unit = {},
    vm: PeerSharingViewModel = hiltViewModel(),
) {
    val gateVm: SupervisionGateViewModel = hiltViewModel()
    val supervisedModeEnabled by gateVm.supervisedModeEnabled.collectAsStateWithLifecycle()
    val gateUnlockState by gateVm.unlockState.collectAsStateWithLifecycle()
    var sessionUnlocked by remember { mutableStateOf(false) }
    if (supervisedModeEnabled && !sessionUnlocked) {
        SupervisionGate(
            unlockState = gateUnlockState,
            onRequestAccess = gateVm::requestAccess,
            onReset = gateVm::reset,
            onNavigateBack = onNavigateBack,
        ) { sessionUnlocked = true }
        return
    }

    LaunchedEffect(peerId) { vm.init(peerId) }

    val state by vm.uiState.collectAsStateWithLifecycle()
    val cfg = state.config
    val tempShareEndsAt = cfg?.temporaryShareEndsAtEpochSeconds
    // Tick the clock only while a temp share is pending; ticking unconditionally would
    // recompose the whole screen every second even when there's nothing to count down.
    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000L) }
    LaunchedEffect(tempShareEndsAt) {
        val endsAt = tempShareEndsAt ?: return@LaunchedEffect
        while (true) {
            val wallSec = System.currentTimeMillis() / 1000L
            nowSec = wallSec
            if (wallSec >= endsAt) break
            kotlinx.coroutines.delay(1.seconds)
        }
    }
    val tempShareActive = tempShareEndsAt?.takeIf { it > nowSec }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = locaPeerTopAppBarColors(),
                title = { Text(stringResource(R.string.peer_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    state.heartbeat?.let { hb ->
                        IconButton(onClick = { onNavigateToMap(hb.lat, hb.lng) }) {
                            Icon(Icons.Default.LocationOn, contentDescription = stringResource(R.string.cd_show_on_map))
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Peer Header ──────────────────────────────────────────────────
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            peerName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Text(
                        peerName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // ── Active Temporary Share (Keep prominent on landing) ───────────
            if (tempShareActive != null) {
                item {
                    ActiveTempShareCard(
                        peerName = peerName,
                        endsAtEpochSeconds = tempShareActive,
                        nowEpochSeconds = nowSec,
                        onStop = { vm.clearTemporaryShare() },
                    )
                }
            }

            // ── Category List ────────────────────────────────────────────────
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
                            title = stringResource(R.string.peer_category_sharing_title),
                            subtitle = stringResource(R.string.peer_category_sharing_subtitle),
                            onClick = onNavigateToControls
                        )
                        CardDivider()
                        CategoryRow(
                            icon = Icons.Default.History,
                            title = stringResource(R.string.peer_location_history),
                            subtitle = stringResource(R.string.peer_location_history_sub, peerName),
                            onClick = onNavigateToLocationHistory
                        )
                        CardDivider()
                        CategoryRow(
                            icon = Icons.Default.Shield,
                            title = stringResource(R.string.peer_category_safety_title),
                            subtitle = stringResource(R.string.peer_category_safety_subtitle),
                            onClick = onNavigateToAlerts
                        )
                        CardDivider()
                        CategoryRow(
                            icon = Icons.Default.Fence,
                            title = stringResource(R.string.settings_geofences),
                            subtitle = stringResource(R.string.peer_geofences_sub, peerName),
                            onClick = onNavigateToGeofences
                        )
                        CardDivider()
                        CategoryRow(
                            icon = Icons.Default.PrivacyTip,
                            title = stringResource(R.string.peer_category_privacy_title),
                            subtitle = stringResource(R.string.peer_category_privacy_subtitle),
                            onClick = onNavigateToPrivacy
                        )
                        CardDivider()
                        CategoryRow(
                            icon = Icons.AutoMirrored.Filled.Chat,
                            title = stringResource(R.string.peer_category_messaging_title),
                            subtitle = stringResource(R.string.peer_category_messaging_subtitle),
                            onClick = onNavigateToMessaging
                        )
                        CardDivider()
                        CategoryRow(
                            icon = Icons.Default.DeleteSweep,
                            title = stringResource(R.string.peer_category_retention_title),
                            subtitle = stringResource(R.string.peer_category_retention_subtitle),
                            onClick = onNavigateToRetention
                        )
                        CardDivider()
                        CategoryRow(
                            icon = Icons.Default.Security,
                            title = stringResource(R.string.peer_category_security_title),
                            subtitle = stringResource(R.string.peer_category_security_subtitle),
                            onClick = onNavigateToSecurity
                        )
                    }
                }
            }
        }
    }
}

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
