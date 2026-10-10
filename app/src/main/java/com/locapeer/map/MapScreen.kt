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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.locapeer.R
import com.locapeer.onboarding.PermissionManager
import com.locapeer.ui.components.RelayStatusChip
import com.locapeer.util.DisplayFormat
import com.locapeer.util.GeoMath
import com.locapeer.util.Geocoding
import com.locapeer.ui.theme.*
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng



@Composable
fun MapScreen(
    onNavigateToChat: (peerId: String, peerName: String) -> Unit,
    onNavigateToHistory: (peerId: String) -> Unit = {},
    vm: MapViewModel = hiltViewModel()
) {
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    var selectedPin by remember { mutableStateOf<PinData?>(null) }
    var showFriendList by remember { mutableStateOf(false) }
    var showSosConfirm by remember { mutableStateOf(false) }
    val isSosActive by vm.isSosActive.collectAsStateWithLifecycle()
    val hasSosContacts by vm.hasSosContacts.collectAsStateWithLifecycle()
    val userLocation by vm.userLocation.collectAsStateWithLifecycle()
    val lastMapCenter by vm.lastMapCenter.collectAsStateWithLifecycle()
    val relayStatus by vm.relayStatus.collectAsStateWithLifecycle()
    val centerOnArgs by vm.centerOnArgs.collectAsStateWithLifecycle()
    val myDisplayName by vm.myDisplayName.collectAsStateWithLifecycle()
    val myPinColor by vm.myPinColor.collectAsStateWithLifecycle()
    val mapStartZoom by vm.mapStartZoom.collectAsStateWithLifecycle()
    val mapStartingPoint by vm.mapStartingPoint.collectAsStateWithLifecycle()
    val mapFixedLat by vm.mapFixedLat.collectAsStateWithLifecycle()
    val mapFixedLng by vm.mapFixedLng.collectAsStateWithLifecycle()
    val showGeofences by vm.showGeofences.collectAsStateWithLifecycle()
    val reverseGeocodingEnabled by vm.reverseGeocodingEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var isFollowingUser by remember { mutableStateOf(false) }
    var centerOnPin by remember { mutableStateOf<LatLng?>(null) }
    var selectedPinAddress by remember { mutableStateOf<String?>(null) }
    val systemDark = isSystemInDarkTheme()
    var mapStyle by remember { mutableStateOf(if (systemDark) "DARK" else "LIGHT") }
    val isDark = mapStyle == "DARK"

    // The device location master switch can be off even after every app permission is granted,
    // in which case no fixes are ever produced and the map would silently show nothing. Watch it
    // (re-reading on resume, since the user toggles it in system settings) so we can surface a
    // banner prompting them to turn it back on. Only relevant once location permission is granted;
    // the permission flow covers the not-granted case.
    var locationServicesOff by remember { mutableStateOf(false) }
    val locationCheckOwner = LocalLifecycleOwner.current
    // Key on context too: if LocalContext changes (e.g. Activity recreation) the effect restarts
    // with the fresh context rather than reading location state through a stale one.
    DisposableEffect(locationCheckOwner, context) {
        fun refresh() {
            locationServicesOff = PermissionManager.hasLocationPermission(context) &&
                !PermissionManager.isLocationServicesEnabled(context)
        }
        refresh()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        locationCheckOwner.lifecycle.addObserver(observer)
        onDispose { locationCheckOwner.lifecycle.removeObserver(observer) }
    }

    // While the map is on screen, ask the contacts we track to broadcast at a live
    // cadence (Google-Maps-style). Balanced across app background (ON_RESUME/ON_PAUSE) and
    // leaving the map (onDispose); the ViewModel makes the calls idempotent so overlapping
    // transitions can't unbalance the underlying reference count. The immediate start
    // covers re-entering the map tab while the Activity is already resumed, where no
    // ON_RESUME fires.
    val liveLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(liveLifecycleOwner) {
        if (liveLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            vm.startLiveView()
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> vm.startLiveView()
                Lifecycle.Event.ON_PAUSE -> vm.stopLiveView()
                else -> {}
            }
        }
        liveLifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            liveLifecycleOwner.lifecycle.removeObserver(observer)
            vm.stopLiveView()
        }
    }

    LaunchedEffect(centerOnArgs) {
        centerOnArgs?.let {
            centerOnPin = it
            vm.consumeCenterArgs()
        }
    }

    LaunchedEffect(selectedPin, reverseGeocodingEnabled) {
        selectedPinAddress = null
        if (!reverseGeocodingEnabled) return@LaunchedEffect
        val hb = selectedPin?.heartbeat ?: return@LaunchedEffect
        selectedPinAddress = Geocoding.reverseGeocode(context, hb.lat, hb.lng)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        MainMapView(
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
                onCenteredOnUser = { },
                onCenteredOnPin = { centerOnPin = null; isFollowingUser = false },
                onPinTapped = { selectedPin = it; isFollowingUser = false },
                onSaveMapPosition = vm::saveMapPosition,
                context = context,
                modifier = Modifier.fillMaxSize()
            )

            if (hasSosContacts) {
                SosButton(
                    isActive = isSosActive,
                    onClick = { showSosConfirm = true },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
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

            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 24.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                FloatingActionButton(
                    onClick = { mapStyle = if (isDark) "LIGHT" else "DARK" },
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.shadow(4.dp, CircleShape)
                ) {
                    Icon(if (isDark) Icons.Default.LightMode else Icons.Default.DarkMode, contentDescription = stringResource(R.string.map_cd_style))
                }

                FloatingActionButton(
                    onClick = { vm.toggleGeofences() },
                    containerColor = if (showGeofences) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                    contentColor = if (showGeofences) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.shadow(4.dp, CircleShape)
                ) {
                    Icon(Icons.Default.Fence, contentDescription = if (showGeofences) stringResource(R.string.map_cd_hide_geofences) else stringResource(R.string.map_cd_show_geofences))
                }

                FloatingActionButton(
                    onClick = { isFollowingUser = !isFollowingUser },
                    containerColor = if (isFollowingUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                    contentColor = if (isFollowingUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.shadow(4.dp, CircleShape)
                ) {
                    Icon(if (isFollowingUser) Icons.Default.MyLocation else Icons.Default.LocationSearching, contentDescription = stringResource(R.string.map_cd_follow))
                }

                FloatingActionButton(
                    onClick = { showFriendList = true },
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.shadow(4.dp, CircleShape)
                ) {
                    Icon(Icons.Default.People, contentDescription = stringResource(R.string.map_cd_friends))
                }
            }

            RelayStatusChip(
                relayStatus = relayStatus,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, bottom = 16.dp)
            )

            AnimatedVisibility(
                visible = showFriendList,
                enter = slideInHorizontally { it },
                exit = slideOutHorizontally { it },
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
                        // Pressing the contact name navigates to the contact on the map:
                        // close the panel and center the viewport on their pin. The info
                        // sheet stays reachable via the map pin tap.
                        showFriendList = false
                        pin.heartbeat?.let { hb ->
                            centerOnPin = LatLng(hb.lat, hb.lng)
                            isFollowingUser = false
                        }
                        selectedPin = pin
                    },
                    onMessageFriend = { peerId, peerName ->
                        showFriendList = false
                        onNavigateToChat(peerId, peerName)
                    },
                    onLocateFriend = { pin ->
                        pin.heartbeat?.let { hb ->
                            centerOnPin = LatLng(hb.lat, hb.lng)
                            isFollowingUser = false
                            showFriendList = false
                        }
                    },
                    formatTimestamp = vm::formatTimestamp
                )
            }

            AnimatedVisibility(
                visible = selectedPin != null,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(280)) + fadeIn(tween(280)),
                exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(220)) + fadeOut(tween(220))
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

            LocationOffBanner(
                visible = locationServicesOff,
                onEnable = { PermissionManager.openLocationSettings(context) },
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }

/**
 * A dismiss-free warning shown at the very top of the map when the device's location master
 * switch is off. Location fixes never arrive in that state, so the map would otherwise look
 * broken with no explanation; this tells the user what's wrong and routes them to fix it.
 */
@Composable
private fun LocationOffBanner(visible: Boolean, onEnable: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.LocationOff,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.map_location_off_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stringResource(R.string.map_location_off_message),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onEnable,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(stringResource(R.string.map_location_off_action))
                }
            }
        }
    }
}

@Composable
private fun FriendListPanel(
    pins: List<PinData>,
    myDisplayName: String,
    userLocation: LatLng?,
    onDismiss: () -> Unit,
    onLocateMe: () -> Unit,
    onSelectFriend: (PinData) -> Unit,
    onMessageFriend: (peerId: String, peerName: String) -> Unit,
    onLocateFriend: (PinData) -> Unit,
    formatTimestamp: (Long) -> String = { "" }
) {
    Surface(
        modifier = Modifier.fillMaxHeight().width(300.dp).shadow(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.tab_contacts), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
private fun MeItem(displayName: String, hasLocation: Boolean, onLocate: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = hasLocation, onClick = onLocate).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "M", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.map_you), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (hasLocation) {
            IconButton(onClick = onLocate) {
                Icon(Icons.Default.PinDrop, contentDescription = stringResource(R.string.map_cd_go_my_location), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun FriendItem(pin: PinData, onClick: () -> Unit, onMessage: () -> Unit, onLocate: () -> Unit, formatTimestamp: (Long) -> String = { "" }) {
    val hb = pin.heartbeat
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(if (hb?.isSos == true) SosRedContainer else MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(pin.peer.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = if (hb?.isSos == true) SosRed else MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(pin.peer.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    hb?.isSos == true -> stringResource(R.string.map_sos_active)
                    pin.isOverdue -> stringResource(R.string.map_away)
                    pin.isLive -> stringResource(R.string.map_live)
                    hb != null -> hb.motionState.lowercase().replaceFirstChar { it.uppercase() }
                    else -> stringResource(R.string.map_no_location_yet)
                },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (pin.isLive && hb?.isSos != true && !pin.isOverdue) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    hb?.isSos == true -> SosRed
                    pin.isLive && !pin.isOverdue -> LiveGreen
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            if (hb != null) {
                Text(stringResource(R.string.contacts_last_seen, formatTimestamp(hb.timestamp)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = onMessage) {
            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = stringResource(R.string.cd_message), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        if (pin.heartbeat != null) {
            IconButton(onClick = onLocate) {
                Icon(Icons.Default.PinDrop, contentDescription = stringResource(R.string.map_cd_go_location), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
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
        text = { Text(if (isActive) stringResource(R.string.map_sos_on) else stringResource(R.string.map_sos), fontWeight = FontWeight.ExtraBold) }
    )
}

@Composable
private fun MainMapView(
    pins: List<PinData>,
    geofences: List<GeofenceOnMap>,
    userLocation: LatLng?,
    lastMapCenter: Triple<Double, Double, Double>?,
    centerOnUser: Boolean,
    centerOnPin: LatLng?,
    isDark: Boolean,
    onCenteredOnUser: () -> Unit,
    onCenteredOnPin: () -> Unit,
    onPinTapped: (PinData) -> Unit,
    onSaveMapPosition: (Double, Double, Double) -> Unit,
    context: android.content.Context,
    modifier: Modifier = Modifier,
    myPinColor: String = "",
    isSosActive: Boolean = false,
    mapStartZoom: Double = 16.0,
    mapStartingPoint: String = "OWN_PIN",
    mapFixedLat: Double = 0.0,
    mapFixedLng: Double = 0.0
) {
    // Zoom values from settings and the saved position are in the legacy (osmdroid) scale;
    // MapGeometry converts them at this boundary.
    val lifecycleOwner = LocalLifecycleOwner.current
    var map by remember { mutableStateOf<LocaMapController?>(null) }
    var initialCenterDone by remember(mapStartingPoint, mapFixedLat, mapFixedLng, lastMapCenter) {
        mutableStateOf(
            (mapStartingPoint == "FIXED_LOCATION" && mapFixedLat != 0.0 && mapFixedLng != 0.0) ||
            (mapStartingPoint != "OWN_PIN" && mapStartingPoint != "FIT_ALL" && lastMapCenter != null)
        )
    }
    var fitAllDone by remember { mutableStateOf(false) }
    var explicitCenterDone by remember { mutableStateOf(false) }
    val isFitAll = mapStartingPoint == "FIT_ALL"
    // Latest settled camera, saved when the screen pauses or leaves (the MapView itself may
    // already be torn down by then, so it is not queried at that point).
    val lastCamera = remember { arrayOfNulls<Triple<Double, Double, Double>>(1) }
    val saveMapPosition by rememberUpdatedState(onSaveMapPosition)

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) lastCamera[0]?.let { (lat, lng, zoom) -> saveMapPosition(lat, lng, zoom) }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            lastCamera[0]?.let { (lat, lng, zoom) -> saveMapPosition(lat, lng, zoom) }
        }
    }

    LaunchedEffect(userLocation, centerOnUser, map) {
        val mv = map ?: return@LaunchedEffect
        if (centerOnUser && userLocation != null) {
            mv.animateTo(userLocation.latitude, userLocation.longitude)
            onCenteredOnUser()
        }
    }

    LaunchedEffect(userLocation, map) {
        val mv = map ?: return@LaunchedEffect
        if (userLocation != null && !initialCenterDone && !explicitCenterDone && centerOnPin == null) {
            mv.moveTo(userLocation.latitude, userLocation.longitude)
            initialCenterDone = true
        }
    }

    LaunchedEffect(centerOnPin, map) {
        val mv = map ?: return@LaunchedEffect
        if (centerOnPin != null) {
            explicitCenterDone = true
            initialCenterDone = true
            fitAllDone = true
            mv.animateTo(centerOnPin.latitude, centerOnPin.longitude)
            onCenteredOnPin()
        }
    }

    LaunchedEffect(pins, isFitAll, map) {
        val mv = map ?: return@LaunchedEffect
        if (!isFitAll || fitAllDone || explicitCenterDone || centerOnPin != null) return@LaunchedEffect
        val points = pins.mapNotNull { it.heartbeat?.let { hb -> LatLng(hb.lat, hb.lng) } }
        if (points.isEmpty()) return@LaunchedEffect
        fitAllDone = true
        initialCenterDone = true
        mv.fit(points, paddingPx = 150, animate = true, singlePointZoom = mv.zoom)
    }

    val geofenceStroke = GeofenceBoth
    val fenceFillArgb = android.graphics.Color.argb(30, (geofenceStroke.red * 255).toInt(), (geofenceStroke.green * 255).toInt(), (geofenceStroke.blue * 255).toInt())
    val fenceStrokeArgb = android.graphics.Color.argb(200, (geofenceStroke.red * 255).toInt(), (geofenceStroke.green * 255).toInt(), (geofenceStroke.blue * 255).toInt())
    val circles = remember(geofences) {
        geofences.map { MapCircle(it.fence.lat, it.fence.lng, it.fence.radiusMetres.toDouble(), fenceFillArgb, fenceStrokeArgb, strokeWidthDp = 1.5f) }
    }
    val pinMarkers = remember(pins, geofences) {
        val labels = geofences.mapIndexed { index, geofenceOnMap ->
            val fence = geofenceOnMap.fence
            MapMarker(
                id = "fence-$index",
                lat = GeoMath.offsetLatitude(fence.lat, fence.radiusMetres.toDouble()),
                lng = fence.lng,
                icon = MarkerIconFactory.createGeofenceLabel(context, fence.name, geofenceOnMap.assignedLabel, fenceStrokeArgb).bitmap,
                clickable = false
            )
        }
        val contacts = pins.mapNotNull { pinData ->
            pinData.heartbeat?.let { hb ->
                MapMarker(
                    id = "pin-${pinData.peer.deviceId}",
                    lat = hb.lat,
                    lng = hb.lng,
                    icon = MarkerIconFactory.create(
                        context, pinData.peer.displayName, pinData.isOverdue, hb.isSos, hb.pinColor,
                        bearingDegrees = hb.bearing,
                        // Show a heading arrow only for a contact that is genuinely
                        // moving (matches the speed-chip gate) and whose fix is current;
                        // a stale/overdue pin must not imply a live direction.
                        showDirection = !pinData.isOverdue && hb.speed > 0f &&
                            !hb.motionState.equals("STATIONARY", ignoreCase = true),
                        isLive = pinData.isLive
                    ).bitmap,
                    zIndex = 1f
                )
            }
        }
        labels + contacts
    }
    // My own position updates often (GPS jitter), so it is kept apart from the contact pins.
    val myMarker = userLocation?.let { loc ->
        MapMarker(
            id = "me",
            lat = loc.latitude,
            lng = loc.longitude,
            icon = MarkerIconFactory.createMyLocationIcon(context, myPinColor, isSosActive).bitmap,
            anchor = MarkerAnchor.CENTER,
            clickable = false,
            zIndex = 2f
        )
    }
    val currentPins by rememberUpdatedState(pins)

    LocaMap(
        modifier = modifier,
        isDark = isDark,
        markers = if (myMarker != null) pinMarkers + myMarker else pinMarkers,
        circles = circles,
        showScaleBar = true,
        showCompass = true,
        onMarkerClick = { id ->
            currentPins.firstOrNull { "pin-${it.peer.deviceId}" == id }?.let(onPinTapped)
        },
        onCameraIdle = { target, zoom ->
            lastCamera[0] = Triple(target.latitude, target.longitude, MapGeometry.toLegacyZoom(zoom))
        },
        onReady = { controller ->
            val startZoom = MapGeometry.fromLegacyZoom(mapStartZoom)
            when {
                isFitAll -> controller.map.moveCamera(CameraUpdateFactory.zoomTo(startZoom))
                mapStartingPoint == "FIXED_LOCATION" && mapFixedLat != 0.0 && mapFixedLng != 0.0 ->
                    controller.moveTo(mapFixedLat, mapFixedLng, startZoom)
                lastMapCenter != null -> {
                    val (lat, lng, zoom) = lastMapCenter
                    controller.moveTo(lat, lng, MapGeometry.fromLegacyZoom(zoom))
                }
                else -> controller.map.moveCamera(CameraUpdateFactory.zoomTo(startZoom))
            }
            map = controller
        }
    )
}

@Composable
private fun PinInfoSheet(pin: PinData, address: String?, formatTimestamp: (Long) -> String, onDismiss: () -> Unit, onMessage: () -> Unit, onViewHistory: () -> Unit) {
    val hb = pin.heartbeat
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(12.dp)
            .clip(RoundedCornerShape(20.dp)),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 16.dp
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Text(pin.peer.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(pin.peer.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (hb?.isSos == true) Text(stringResource(R.string.map_sos_active), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = SosRed)
                        else if (pin.isOverdue) Text(stringResource(R.string.map_overdue), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        else if (pin.isLive) Text(stringResource(R.string.map_live), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = LiveGreen)
                    }
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close)) }
            }
            if (hb != null) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatChip(stringResource(R.string.map_stat_last_seen), formatTimestamp(hb.timestamp))
                    StatChip(stringResource(R.string.map_stat_battery), "${hb.battery}%")
                    StatChip(stringResource(R.string.map_stat_accuracy), "±${DisplayFormat.distanceValue(hb.accuracy.toDouble())}")
                    StatChip(stringResource(R.string.map_stat_motion), hb.motionState.lowercase().replaceFirstChar { it.uppercase() })
                    if (!hb.motionState.equals("STATIONARY", ignoreCase = true) && hb.speed > 0f) StatChip(stringResource(R.string.map_stat_speed), "${DisplayFormat.speedValue(hb.speed)} ${DisplayFormat.bearingToCardinal(hb.bearing)}")
                    if (hb.altitude != 0.0) StatChip(stringResource(R.string.map_stat_elevation), DisplayFormat.elevationValue(hb.altitude))
                }
                address?.let {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.LocationOn, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                }
            } else {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.contacts_no_location_yet), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onViewHistory, modifier = Modifier.weight(1f)) { Icon(Icons.Default.History, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.tab_history)) }
                Button(onClick = onMessage, modifier = Modifier.weight(1f)) { Icon(Icons.AutoMirrored.Filled.Chat, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.cd_message)) }
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}
