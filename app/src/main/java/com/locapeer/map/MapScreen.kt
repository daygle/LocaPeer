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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.locapeer.R
import com.locapeer.ui.components.RelayStatusChip
import com.locapeer.util.DisplayFormat
import com.locapeer.util.GeoMath
import com.locapeer.util.Geocoding
import com.locapeer.ui.theme.*
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.ScaleBarOverlay
import org.osmdroid.views.overlay.compass.CompassOverlay
import org.osmdroid.views.overlay.compass.InternalCompassOrientationProvider

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
    onNavigateToHistory: (peerId: String) -> Unit = {},
    vm: MapViewModel = hiltViewModel()
) {
    val uiState by vm.uiState.collectAsState()
    var selectedPin by remember { mutableStateOf<PinData?>(null) }
    var showFriendList by remember { mutableStateOf(value = false) }
    var showSosConfirm by remember { mutableStateOf(false) }
    val isSosActive by vm.isSosActive.collectAsState()
    val hasSosContacts by vm.hasSosContacts.collectAsState()
    val userLocation by vm.userLocation.collectAsState()
    val lastMapCenter by vm.lastMapCenter.collectAsState()
    val relayStatus by vm.relayStatus.collectAsState()
    val centerOnArgs by vm.centerOnArgs.collectAsState()
    val myDisplayName by vm.myDisplayName.collectAsState()
    val myPinColor by vm.myPinColor.collectAsState()
    val mapStartZoom by vm.mapStartZoom.collectAsState()
    val mapStartingPoint by vm.mapStartingPoint.collectAsState()
    val mapFixedLat by vm.mapFixedLat.collectAsState()
    val mapFixedLng by vm.mapFixedLng.collectAsState()
    val showGeofences by vm.showGeofences.collectAsState()
    val reverseGeocodingEnabled by vm.reverseGeocodingEnabled.collectAsState()
    val context = LocalContext.current
    var isFollowingUser by remember { mutableStateOf(false) }
    var centerOnPin by remember { mutableStateOf<GeoPoint?>(null) }
    var selectedPinAddress by remember { mutableStateOf<String?>(null) }
    val systemDark = isSystemInDarkTheme()
    var mapStyle by remember { mutableStateOf(if (systemDark) "DARK" else "LIGHT") }
    val isDark = mapStyle == "DARK"

    LaunchedEffect(centerOnArgs) {
        centerOnArgs?.let {
            centerOnPin = it
            vm.consumeCenterArgs()
        }
    }

    LaunchedEffect(selectedPin, reverseGeocodingEnabled) {
        selectedPinAddress = null
        // Reverse geocoding is opt-in: the platform Geocoder sends the queried coordinates
        // off-device, so only look up an address when the user has enabled it.
        if (!reverseGeocodingEnabled) return@LaunchedEffect
        val hb = selectedPin?.heartbeat ?: return@LaunchedEffect
        selectedPinAddress = Geocoding.reverseGeocode(context, hb.lat, hb.lng)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        OsmdroidMapView(
            pins = uiState.pins,
            geofences = if (showGeofences) uiState.geofences else emptyList(),
            userLocation = userLocation,
            myPinColor = myPinColor,
            isSosActive = isSosActive,
            lastMapCenter = lastMapCenter,
            centerOnUser = isFollowingUser,
            centerOnPin = centerOnPin,
            isDark = isDark,
            mapStartZoom = mapStartZoom,
            mapStartingPoint = mapStartingPoint,
            mapFixedLat = mapFixedLat,
            mapFixedLng = mapFixedLng,
            onCenteredOnUser = { }, // No-op for continuous follow
            onCenteredOnPin = { centerOnPin = null; isFollowingUser = false },
            onPinTapped = { selectedPin = it; isFollowingUser = false },
            onSaveMapPosition = vm::saveMapPosition,
            context = context,
            modifier = Modifier.fillMaxSize()
        )

        // SOS Button - top-left (only shown when at least one SOS contact is configured)
        if (hasSosContacts) {
            SosButton(
                isActive = isSosActive,
                onClick = { showSosConfirm = true },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
            )
        }

        if (showSosConfirm) {
            AlertDialog(
                onDismissRequest = { showSosConfirm = false },
                icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = SosRed) },
                title = { Text(if (isSosActive) stringResource(R.string.map_sos_deactivate_title) else stringResource(R.string.map_sos_activate_title)) },
                text = {
                    Text(
                        if (isSosActive) stringResource(R.string.map_sos_deactivate_msg)
                        else stringResource(R.string.map_sos_activate_msg)
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            vm.toggleSos()
                            showSosConfirm = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SosRed)
                    ) {
                        Text(if (isSosActive) stringResource(R.string.map_deactivate) else stringResource(R.string.map_activate))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSosConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
                }
            )
        }

        // Control Column - bottom-right
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Map Style Toggle
            FloatingActionButton(
                onClick = { mapStyle = if (isDark) "LIGHT" else "DARK" },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.shadow(4.dp, CircleShape)
            ) {
                Icon(if (isDark) Icons.Default.LightMode else Icons.Default.DarkMode, contentDescription = stringResource(R.string.map_cd_style))
            }

            // Geofence Visibility Toggle
            FloatingActionButton(
                onClick = { vm.toggleGeofences() },
                containerColor = if (showGeofences) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                contentColor = if (showGeofences) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                modifier = Modifier.shadow(4.dp, CircleShape)
            ) {
                Icon(Icons.Default.Fence, contentDescription = if (showGeofences) stringResource(R.string.map_cd_hide_geofences) else stringResource(R.string.map_cd_show_geofences))
            }

            // Follow Mode / Locate Me
            FloatingActionButton(
                onClick = { isFollowingUser = !isFollowingUser },
                containerColor = if (isFollowingUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                contentColor = if (isFollowingUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                modifier = Modifier.shadow(4.dp, CircleShape)
            ) {
                Icon(if (isFollowingUser) Icons.Default.MyLocation else Icons.Default.LocationSearching, contentDescription = stringResource(R.string.map_cd_follow))
            }

            // Friends List Toggle
            FloatingActionButton(
                onClick = { showFriendList = true },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.shadow(4.dp, CircleShape)
            ) {
                Icon(Icons.Default.People, contentDescription = stringResource(R.string.map_cd_friends))
            }
        }

        // Relay status chip - bottom-left
        RelayStatusChip(
            relayStatus = relayStatus,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 16.dp)
        )

        // Friend List Sidebar / Panel
        AnimatedVisibility(
            visible = showFriendList,
            enter = slideInHorizontally(initialOffsetX = { it }),
            exit = slideOutHorizontally(targetOffsetX = { it }),
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            FriendListPanel(
                pins = uiState.pins,
                myDisplayName = myDisplayName,
                userLocation = userLocation,
                onDismiss = { showFriendList = false },
                onLocateMe = {
                    isFollowingUser = true
                    showFriendList = false
                },
                onSelectFriend = { pin ->
                    showFriendList = false
                    selectedPin = pin
                },
                onMessageFriend = { peerId, peerName ->
                    showFriendList = false
                    onNavigateToChat(peerId, peerName)
                },
                onLocateFriend = { pin ->
                    pin.heartbeat?.let { hb ->
                        centerOnPin = GeoPoint(hb.lat, hb.lng)
                        isFollowingUser = false
                        showFriendList = false
                    }
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
                    address = selectedPinAddress,
                    formatTimestamp = vm::formatTimestamp,
                    onDismiss = { selectedPin = null },
                    onMessage = {
                        selectedPin = null
                        onNavigateToChat(pin.peer.deviceId, pin.peer.displayName)
                    },
                    onViewHistory = {
                        selectedPin = null
                        onNavigateToHistory(pin.peer.deviceId)
                    }
                )
            }
        }
    }
}

@Composable
private fun FriendListPanel(
    pins: List<PinData>,
    myDisplayName: String,
    userLocation: GeoPoint?,
    onDismiss: () -> Unit,
    onLocateMe: () -> Unit,
    onSelectFriend: (PinData) -> Unit,
    onMessageFriend: (peerId: String, peerName: String) -> Unit,
    onLocateFriend: (PinData) -> Unit,
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
                    stringResource(R.string.tab_contacts),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close))
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // "Me" row always at the top
            MeItem(displayName = myDisplayName, hasLocation = userLocation != null, onLocate = onLocateMe)

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            if (pins.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.map_no_friends), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(pins, key = { it.peer.deviceId }) { pin ->
                        FriendItem(
                            pin = pin,
                            onClick = { onSelectFriend(pin) },
                            onMessage = { onMessageFriend(pin.peer.deviceId, pin.peer.displayName) },
                            onLocate = { onLocateFriend(pin) },
                            formatTimestamp = formatTimestamp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MeItem(
    displayName: String,
    hasLocation: Boolean,
    onLocate: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = hasLocation, onClick = onLocate)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "M",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                stringResource(R.string.map_you),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (hasLocation) {
            IconButton(onClick = onLocate) {
                Icon(
                    Icons.Default.PinDrop,
                    contentDescription = stringResource(R.string.map_cd_go_my_location),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun FriendItem(
    pin: PinData,
    onClick: () -> Unit,
    onMessage: () -> Unit,
    onLocate: () -> Unit,
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
                    hb?.isSos == true -> stringResource(R.string.map_sos_active)
                    pin.isOverdue -> stringResource(R.string.map_away)
                    hb != null -> hb.motionState.lowercase().replaceFirstChar { it.uppercase() }
                    else -> stringResource(R.string.map_no_location_yet)
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (hb?.isSos == true) SosRed else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (hb != null) {
                Text(
                    stringResource(R.string.contacts_last_seen, formatTimestamp(hb.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        IconButton(onClick = onMessage) {
            Icon(
                Icons.AutoMirrored.Filled.Chat,
                contentDescription = stringResource(R.string.cd_message),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
        if (pin.heartbeat != null) {
            IconButton(onClick = onLocate) {
                Icon(
                    Icons.Default.PinDrop,
                    contentDescription = stringResource(R.string.map_cd_go_location),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
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
        icon = { Icon(Icons.Default.Warning, contentDescription = stringResource(R.string.map_sos)) },
        text = {
            Text(
                if (isActive) stringResource(R.string.map_sos_on) else stringResource(R.string.map_sos),
                fontWeight = FontWeight.ExtraBold
            )
        }
    )
}

@Composable
private fun OsmdroidMapView(
    pins: List<PinData>,
    geofences: List<GeofenceOnMap>,
    userLocation: GeoPoint?,
    myPinColor: String = "",
    isSosActive: Boolean = false,
    lastMapCenter: Triple<Double, Double, Double>?,
    centerOnUser: Boolean,
    centerOnPin: GeoPoint?,
    isDark: Boolean,
    mapStartZoom: Double = 16.0,
    mapStartingPoint: String = "OWN_PIN",
    mapFixedLat: Double = 0.0,
    mapFixedLng: Double = 0.0,
    onCenteredOnUser: () -> Unit,
    onCenteredOnPin: () -> Unit,
    onPinTapped: (PinData) -> Unit,
    onSaveMapPosition: (Double, Double, Double) -> Unit,
    context: android.content.Context,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var initialCenterDone by remember(mapStartingPoint, mapFixedLat, mapFixedLng, lastMapCenter) {
        mutableStateOf(
            (mapStartingPoint == "FIXED_LOCATION" && mapFixedLat != 0.0 && mapFixedLng != 0.0) ||
            (mapStartingPoint != "OWN_PIN" && mapStartingPoint != "FIT_ALL" && lastMapCenter != null)
        )
    }
    var fitAllDone by remember { mutableStateOf(false) }
    // Once an explicit "show on map" target has been applied, the automatic start-up
    // centering (own pin / fit-all) must not run and recentre the map away from it.
    // Kept as keyless remember so it survives the resets of initialCenterDone above.
    var explicitCenterDone by remember { mutableStateOf(false) }
    val isFitAll = mapStartingPoint == "FIT_ALL"

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapViewRef?.onResume()
                Lifecycle.Event.ON_PAUSE -> {
                    mapViewRef?.let { mv ->
                        val c = mv.mapCenter
                        onSaveMapPosition(c.latitude, c.longitude, mv.zoomLevelDouble)
                        mv.onPause()
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapViewRef?.apply {
                val c = mapCenter
                onSaveMapPosition(c.latitude, c.longitude, zoomLevelDouble)
                onPause()
                onDetach()
            }
            mapViewRef = null
        }
    }

    // Follow User Logic
    LaunchedEffect(userLocation, centerOnUser) {
        if (centerOnUser && userLocation != null) {
            mapViewRef?.controller?.animateTo(userLocation)
            onCenteredOnUser()
        }
    }

    // Initial positioning
    LaunchedEffect(userLocation) {
        if (userLocation != null && !initialCenterDone && !explicitCenterDone && centerOnPin == null) {
            mapViewRef?.controller?.setCenter(userLocation)
            initialCenterDone = true
        }
    }

    // Jump to a contact's pin
    LaunchedEffect(centerOnPin) {
        if (centerOnPin != null) {
            explicitCenterDone = true
            initialCenterDone = true
            fitAllDone = true
            mapViewRef?.controller?.animateTo(centerOnPin)
            onCenteredOnPin()
        }
    }

    // Fit all contacts into view
    LaunchedEffect(pins, isFitAll, mapViewRef) {
        val mv = mapViewRef ?: return@LaunchedEffect
        if (!isFitAll || fitAllDone || explicitCenterDone || centerOnPin != null) return@LaunchedEffect
        val points = pins.mapNotNull { it.heartbeat?.let { hb -> GeoPoint(hb.lat, hb.lng) } }
        if (points.isEmpty()) return@LaunchedEffect
        fitAllDone = true
        initialCenterDone = true
        if (points.size == 1) {
            mv.controller.animateTo(points.first())
        } else {
            val box = org.osmdroid.util.BoundingBox(
                points.maxOf { it.latitude },
                points.maxOf { it.longitude },
                points.minOf { it.latitude },
                points.minOf { it.longitude }
            )
            mv.post { mv.zoomToBoundingBox(box, true, 150) }
        }
    }

    AndroidView(
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(if (isDark) CARTO_DARK else CARTO_LIGHT)
                setMultiTouchControls(true)
                isVerticalMapRepetitionEnabled = false
                
                // Scale Bar (Range marker)
                val scaleBar = ScaleBarOverlay(this)
                val density = ctx.resources.displayMetrics.density
                scaleBar.setCentred(false)
                scaleBar.setAlignRight(true)
                // Move it slightly more from the edges (values are in pixels)
                scaleBar.setScaleBarOffset((20 * density).toInt(), (20 * density).toInt())
                overlays.add(scaleBar)

                // Compass
                val compass = CompassOverlay(ctx, InternalCompassOrientationProvider(ctx), this)
                compass.enableCompass()
                overlays.add(compass)

                when {
                    isFitAll -> controller.setZoom(mapStartZoom)
                    mapStartingPoint == "FIXED_LOCATION" && mapFixedLat != 0.0 && mapFixedLng != 0.0 -> {
                        controller.setZoom(mapStartZoom)
                        controller.setCenter(GeoPoint(mapFixedLat, mapFixedLng))
                    }
                    mapStartingPoint == "OWN_PIN" -> controller.setZoom(mapStartZoom)
                    lastMapCenter != null -> {
                        val (lat, lng, zoom) = lastMapCenter
                        controller.setZoom(zoom)
                        controller.setCenter(GeoPoint(lat, lng))
                    }
                    else -> controller.setZoom(mapStartZoom)
                }
            }.also { mapViewRef = it }
        },
        update = { mapView ->
            val targetTileSource = if (isDark) CARTO_DARK else CARTO_LIGHT
            if (mapView.tileProvider.tileSource != targetTileSource) {
                mapView.setTileSource(targetTileSource)
            }

            // Reposition compass to top-right to avoid SOS button on the left.
            // Scale bar is also moved to top-right in the factory block.
            mapView.overlays.filterIsInstance<CompassOverlay>().firstOrNull()?.let { compass ->
                val density = mapView.context.resources.displayMetrics.density
                compass.setCompassCenter(mapView.width - 45f * density, 85f * density)
            }

            // Remove previous markers/geofences but keep the permanent overlays (compass/scale)
            mapView.overlays.removeAll { it is Marker || it is Polygon }

            geofences.forEach { geofenceOnMap ->
                val fence = geofenceOnMap.fence
                // Areas are shared across contacts/triggers now, so use one accent colour.
                val strokeColor = GeofenceBoth
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

                // Label showing the fence name and the contacts it's assigned to. Anchor it to
                // the top edge of the circle (not the centre) so fences that share a centre -
                // e.g. a large suburb zone and a small home zone on the same point - separate by
                // radius instead of stacking their labels on top of each other.
                val label = Marker(mapView).apply {
                    position = GeoPoint(
                        GeoMath.offsetLatitude(fence.lat, fence.radiusMetres.toDouble()),
                        fence.lng
                    )
                    icon = MarkerIconFactory.createGeofenceLabel(
                        context = context,
                        title = fence.name,
                        subtitle = geofenceOnMap.assignedLabel,
                        color = strokeArgb
                    )
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    infoWindow = null
                    isDraggable = false
                    // Decorative label: consume clicks so osmdroid doesn't recenter or
                    // try to open a (null) info window.
                    setOnMarkerClickListener { _, _ -> true }
                }
                mapView.overlays.add(label)
            }

            pins.forEach { pinData ->
                val hb = pinData.heartbeat ?: return@forEach
                val icon = MarkerIconFactory.create(
                    context = context,
                    displayName = pinData.peer.displayName,
                    isOverdue = pinData.isOverdue,
                    isSos = hb.isSos,
                    pinColor = hb.pinColor
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
                    icon = MarkerIconFactory.createMyLocationIcon(context, myPinColor, isSosActive)
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
    address: String?,
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
                                stringResource(R.string.map_sos_active),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = SosRed
                            )
                            pin.isOverdue -> Text(
                                stringResource(R.string.map_overdue),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close))
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
                    StatChip(stringResource(R.string.map_stat_last_seen), formatTimestamp(hb.timestamp))
                    StatChip(stringResource(R.string.map_stat_battery), "${hb.battery}%")
                    StatChip(stringResource(R.string.map_stat_accuracy), "±${DisplayFormat.distanceValue(hb.accuracy.toDouble())}")
                    StatChip(stringResource(R.string.map_stat_motion),
                        hb.motionState.lowercase().replaceFirstChar { it.uppercase() })
                    if (!hb.motionState.equals("STATIONARY", ignoreCase = true) && hb.speed > 0f) {
                        StatChip(stringResource(R.string.map_stat_speed),
                            "${DisplayFormat.speedValue(hb.speed)} ${DisplayFormat.bearingToCardinal(hb.bearing)}")
                    }
                    if (hb.altitude != 0.0) {
                        StatChip(stringResource(R.string.map_stat_elevation), DisplayFormat.elevationValue(hb.altitude))
                    }
                }

                if (address != null) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.contacts_no_location_yet),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onViewHistory, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.History, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.tab_history))
                }
                Button(onClick = onMessage, modifier = Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Filled.Chat, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.cd_message))
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

