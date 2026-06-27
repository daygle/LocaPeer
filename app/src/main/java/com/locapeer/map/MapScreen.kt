package com.locapeer.map

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.locapeer.data.entity.GeofenceEntity
import com.locapeer.ui.components.RelayStatusChip
import com.locapeer.ui.theme.*
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

private val CARTO_LIGHT = object : OnlineTileSourceBase(
    "CartoDB_Positron", 0, 19, 256, ".png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/light_all/",
        "https://b.basemaps.cartocdn.com/light_all/",
        "https://c.basemaps.cartocdn.com/light_all/"
    ), "© CartoDB © OpenStreetMap contributors"
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        "$baseUrl${MapTileIndex.getZoom(pMapTileIndex)}/" +
                "${MapTileIndex.getX(pMapTileIndex)}/" +
                "${MapTileIndex.getY(pMapTileIndex)}$mImageFilenameEnding"
}

private val CARTO_DARK = object : OnlineTileSourceBase(
    "CartoDB_DarkMatter", 0, 19, 256, ".png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/dark_all/",
        "https://b.basemaps.cartocdn.com/dark_all/",
        "https://c.basemaps.cartocdn.com/dark_all/"
    ), "© CartoDB © OpenStreetMap contributors"
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        "$baseUrl${MapTileIndex.getZoom(pMapTileIndex)}/" +
                "${MapTileIndex.getX(pMapTileIndex)}/" +
                "${MapTileIndex.getY(pMapTileIndex)}$mImageFilenameEnding"
}

@Composable
fun MapScreen(
    onNavigateToChat: (peerId: String, peerName: String) -> Unit,
    vm: MapViewModel = hiltViewModel()
) {
    val uiState by vm.uiState.collectAsState()
    var selectedPin by remember { mutableStateOf<PinData?>(null) }
    var showFriendList by remember { mutableStateOf(value = false) }
    val isSosActive by vm.isSosActive.collectAsState()
    val userLocation by vm.userLocation.collectAsState()
    val relayStatus by vm.relayStatus.collectAsState()
    val context = LocalContext.current
    var centerOnUser by remember { mutableStateOf(value = false) }
    val isDark = isSystemInDarkTheme()

    LaunchedEffect(Unit) { vm.fetchUserLocation() }

    Box(modifier = Modifier.fillMaxSize()) {
        OsmdroidMapView(
            pins = uiState.pins,
            geofences = uiState.geofences,
            userLocation = userLocation,
            centerOnUser = centerOnUser,
            isDark = isDark,
            onCenteredOnUser = { centerOnUser = false },
            onPinTapped = { selectedPin = it },
            context = context,
            modifier = Modifier.fillMaxSize()
        )

        // SOS Button — top-left
        SosButton(
            isActive = isSosActive,
            onClick = { vm.toggleSos() },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        )

        // Locate Me Button — bottom-right
        FloatingActionButton(
            onClick = {
                vm.fetchUserLocation()
                centerOnUser = true
            },
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .shadow(4.dp, CircleShape)
        ) {
            Icon(Icons.Default.MyLocation, contentDescription = "My Location")
        }

        // Friends Button — top-right
        FloatingActionButton(
            onClick = { showFriendList = true },
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .shadow(4.dp, CircleShape)
        ) {
            Icon(Icons.Default.People, contentDescription = "Friends")
        }

        // Relay status chip — bottom-left
        RelayStatusChip(
            relayStatus = relayStatus,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 16.dp)
        )

        // Friend List Sidebar / Panel
        AnimatedVisibility(
            visible = showFriendList,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            FriendListPanel(
                pins = uiState.pins,
                onDismiss = { showFriendList = false },
                onSelectFriend = { pin ->
                    showFriendList = false
                    selectedPin = pin
                    // Logic to center map could go here via VM or callback
                },
                onMessageFriend = { peerId, peerName ->
                    showFriendList = false
                    onNavigateToChat(peerId, peerName)
                },
                formatTimestamp = vm::formatTimestamp
            )
        }

        // Animated pin info sheet
        AnimatedVisibility(
            visible = selectedPin != null,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(280)) +
                    fadeIn(tween(280)),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(220)) +
                    fadeOut(tween(220))
        ) {
            selectedPin?.let { pin ->
                PinInfoSheet(
                    pin = pin,
                    formatTimestamp = vm::formatTimestamp,
                    onDismiss = { selectedPin = null },
                    onMessage = {
                        selectedPin = null
                        onNavigateToChat(pin.peer.deviceId, pin.peer.displayName)
                    },
                    onViewHistory = {}
                )
            }
        }
    }
}

@Composable
private fun FriendListPanel(
    pins: List<PinData>,
    onDismiss: () -> Unit,
    onSelectFriend: (PinData) -> Unit,
    onMessageFriend: (peerId: String, peerName: String) -> Unit,
    formatTimestamp: (Long) -> String = { "" }
) {
    Surface(
        modifier = Modifier
            .fillMaxHeight()
            .width(300.dp)
            .shadow(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Contacts",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (pins.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No friends tracked yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(pins) { pin ->
                        FriendItem(
                            pin = pin,
                            onClick = { onSelectFriend(pin) },
                            onMessage = { onMessageFriend(pin.peer.deviceId, pin.peer.displayName) },
                            formatTimestamp = formatTimestamp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FriendItem(
    pin: PinData,
    onClick: () -> Unit,
    onMessage: () -> Unit,
    formatTimestamp: (Long) -> String = { "" }
) {
    val hb = pin.heartbeat
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (hb?.isSos == true) SosRedContainer else MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                pin.peer.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (hb?.isSos == true) SosRed else MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                pin.peer.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                when {
                    hb?.isSos == true -> "⚠ SOS ACTIVE"
                    pin.isOverdue -> "Away"
                    hb != null -> hb.motionState.lowercase().replaceFirstChar { it.uppercase() }
                    else -> "No location yet"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (hb?.isSos == true) SosRed else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (hb != null) {
                Text(
                    "Last seen: ${formatTimestamp(hb.timestamp)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        IconButton(onClick = onMessage) {
            Icon(
                Icons.AutoMirrored.Filled.Chat,
                contentDescription = "Message",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun SosButton(isActive: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val containerColor = if (isActive) SosRed else SosRedContainer
    val contentColor = if (isActive) Color.White else SosRed
    ExtendedFloatingActionButton(
        onClick = onClick,
        containerColor = containerColor,
        contentColor = contentColor,
        modifier = modifier.shadow(if (isActive) 12.dp else 4.dp, RoundedCornerShape(16.dp)),
        icon = { Icon(Icons.Default.Warning, contentDescription = "SOS") },
        text = {
            Text(
                if (isActive) "SOS ON" else "SOS",
                fontWeight = FontWeight.ExtraBold
            )
        }
    )
}

@Composable
private fun OsmdroidMapView(
    pins: List<PinData>,
    geofences: List<GeofenceEntity>,
    userLocation: GeoPoint?,
    centerOnUser: Boolean,
    isDark: Boolean,
    onCenteredOnUser: () -> Unit,
    onPinTapped: (PinData) -> Unit,
    context: android.content.Context,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var initialCenterDone by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapViewRef?.onResume()
                Lifecycle.Event.ON_PAUSE -> mapViewRef?.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapViewRef?.apply {
                onPause()
                onDetach()
            }
            mapViewRef = null
        }
    }

    // Center on user's location once it's available for the first time
    LaunchedEffect(userLocation) {
        if ((userLocation != null) && !initialCenterDone) {
            mapViewRef?.controller?.animateTo(userLocation)
            initialCenterDone = true
        }
    }

    // Re-center when the locate-me button is pressed
    LaunchedEffect(centerOnUser) {
        if (centerOnUser && userLocation != null) {
            mapViewRef?.controller?.animateTo(userLocation)
            onCenteredOnUser()
        }
    }

    AndroidView(
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(if (isDark) CARTO_DARK else CARTO_LIGHT)
                setMultiTouchControls(true)
                controller.setZoom(16.0)
                isVerticalMapRepetitionEnabled = false
            }.also { mapViewRef = it }
        },
        update = { mapView ->
            mapView.setTileSource(if (isDark) CARTO_DARK else CARTO_LIGHT)
            mapView.overlays.clear()

            geofences.forEach { fence ->
                val strokeColor = when (fence.triggerOn) {
                    "ENTER" -> GeofenceEnter
                    "EXIT" -> GeofenceExit
                    else -> GeofenceBoth
                }
                val fillArgb = android.graphics.Color.argb(30,
                    (strokeColor.red * 255).toInt(),
                    (strokeColor.green * 255).toInt(),
                    (strokeColor.blue * 255).toInt())
                val strokeArgb = android.graphics.Color.argb(200,
                    (strokeColor.red * 255).toInt(),
                    (strokeColor.green * 255).toInt(),
                    (strokeColor.blue * 255).toInt())
                val circle = Polygon().apply {
                    points = Polygon.pointsAsCircle(GeoPoint(fence.lat, fence.lng), fence.radiusMetres.toDouble())
                    fillPaint.color = fillArgb
                    outlinePaint.color = strokeArgb
                    outlinePaint.strokeWidth = 4f
                    title = fence.name
                }
                mapView.overlays.add(circle)
            }

            pins.forEach { pinData ->
                val hb = pinData.heartbeat ?: return@forEach
                val icon = MarkerIconFactory.create(
                    context = context,
                    displayName = pinData.peer.displayName,
                    isOverdue = pinData.isOverdue,
                    isSos = hb.isSos
                )
                val marker = Marker(mapView).apply {
                    position = GeoPoint(hb.lat, hb.lng)
                    title = pinData.peer.displayName
                    setIcon(icon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    infoWindow = null
                    setOnMarkerClickListener { _, _ ->
                        onPinTapped(pinData)
                        true
                    }
                }
                mapView.overlays.add(marker)
            }

            userLocation?.let { loc ->
                val myMarker = Marker(mapView).apply {
                    position = loc
                    title = "You"
                    icon = MarkerIconFactory.createMyLocationIcon(context)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    infoWindow = null
                }
                mapView.overlays.add(myMarker)
            }

            mapView.invalidate()
        },
        modifier = modifier
    )
}

@Composable
private fun PinInfoSheet(
    pin: PinData,
    formatTimestamp: (Long) -> String,
    onDismiss: () -> Unit,
    onMessage: () -> Unit,
    onViewHistory: () -> Unit
) {
    val hb = pin.heartbeat
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .clip(RoundedCornerShape(20.dp)),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 16.dp
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            pin.peer.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            pin.peer.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        when {
                            hb?.isSos == true -> Text(
                                "⚠ SOS ACTIVE",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = SosRed
                            )
                            pin.isOverdue -> Text(
                                "Overdue — no recent update",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            if (hb != null) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatChip("Last seen", formatTimestamp(hb.timestamp))
                    StatChip("Battery", "${hb.battery}%")
                    StatChip("Motion",
                        hb.motionState.lowercase().replaceFirstChar { it.uppercase() })
                }
            } else {
                Spacer(Modifier.height(8.dp))
                Text("No location data yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onViewHistory, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.History, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("History")
                }
                Button(onClick = onMessage, modifier = Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Filled.Chat, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Message")
                }
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold)
        Text(label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
