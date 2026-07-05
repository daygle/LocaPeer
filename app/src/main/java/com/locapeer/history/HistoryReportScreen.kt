package com.locapeer.history

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.locapeer.data.entity.HeartbeatEntity
import com.locapeer.map.MarkerIconFactory
import com.locapeer.ui.components.EmptyState
import com.locapeer.ui.components.TimePickerDialog
import com.locapeer.util.DisplayFormat
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryReportScreen(
    peerId: String? = null,
    isOwnHistoryMode: Boolean = false,
    onNavigateBack: (() -> Unit)? = null,
    vm: HistoryReportViewModel = hiltViewModel()
) {
    val selfPubkeyHex by vm.selfPubkeyHex.collectAsState()

    LaunchedEffect(peerId) {
        if (peerId != null) vm.selectPeer(peerId)
    }

    LaunchedEffect(isOwnHistoryMode, selfPubkeyHex) {
        if (isOwnHistoryMode && selfPubkeyHex != null) vm.selectPeer(selfPubkeyHex!!)
    }

    val broadcasters by vm.receiveContacts.collectAsState()
    val selectedPeerId by vm.selectedPeerId.collectAsState()
    val selectedDayStart by vm.selectedDayStart.collectAsState()
    val startTimeOffset by vm.startTimeOffset.collectAsState()
    val endTimeOffset by vm.endTimeOffset.collectAsState()
    val heartbeats by vm.heartbeats.collectAsState()
    val addresses by vm.addresses.collectAsState()
    val selfDisplayName by vm.selfDisplayName.collectAsState()

    val selectedPeer = broadcasters.find { it.deviceId == selectedPeerId }

    var showDatePicker by remember { mutableStateOf(false) }
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }
    var peerDropdownExpanded by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Map, 1: List

    val dateFormat = remember { SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault()) }
    val timeFormat = remember(DisplayFormat.use24HourTime) { DisplayFormat.timeFormat(withSeconds = true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            selectedPeerId == selfPubkeyHex -> "My Location History"
                            selectedPeer != null -> "History: ${selectedPeer.displayName}"
                            else -> "Location History"
                        }
                    )
                },
                navigationIcon = {
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
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
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(12.dp))

                if (broadcasters.isNotEmpty() || selfPubkeyHex != null) {
                    ExposedDropdownMenuBox(
                        expanded = peerDropdownExpanded,
                        onExpandedChange = { peerDropdownExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = when {
                                selectedPeerId == selfPubkeyHex -> selfDisplayName
                                selectedPeer != null -> selectedPeer.displayName
                                else -> "Select Person"
                            },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Person") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(peerDropdownExpanded) },
                            modifier = Modifier
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = peerDropdownExpanded,
                            onDismissRequest = { peerDropdownExpanded = false }
                        ) {
                            selfPubkeyHex?.let { meId ->
                                DropdownMenuItem(
                                    text = { Text(selfDisplayName) },
                                    onClick = {
                                        vm.selectPeer(meId)
                                        peerDropdownExpanded = false
                                    }
                                )
                            }
                            broadcasters.forEach { peer ->
                                DropdownMenuItem(
                                    text = { Text(peer.displayName) },
                                    onClick = {
                                        vm.selectPeer(peer.deviceId)
                                        peerDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { vm.prevDay() }) {
                                Icon(Icons.Default.ChevronLeft, contentDescription = "Previous Day")
                            }
                            TextButton(
                                onClick = { showDatePicker = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    dateFormat.format(Date(selectedDayStart)),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            IconButton(
                                onClick = { vm.nextDay() },
                                enabled = !vm.isToday()
                            ) {
                                Icon(Icons.Default.ChevronRight, contentDescription = "Next Day")
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.AccessTime, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Time Filter:",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                TextButton(onClick = { showStartTimePicker = true }) {
                                    val hour = (startTimeOffset / 3_600_000).toInt()
                                    val minute = ((startTimeOffset % 3_600_000) / 60_000).toInt()
                                    Text(String.format("%02d:%02d", hour, minute), style = MaterialTheme.typography.labelLarge)
                                }
                                Text("—", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { showEndTimePicker = true }) {
                                    val hour = (endTimeOffset / 3_600_000).toInt()
                                    val minute = ((endTimeOffset % 3_600_000) / 60_000).toInt()
                                    Text(String.format("%02d:%02d", hour, minute), style = MaterialTheme.typography.labelLarge)
                                }
                            }

                            if (startTimeOffset > 0 || endTimeOffset < 24 * 60 * 60 * 1000L - 1000) {
                                IconButton(onClick = { vm.resetTimeRange() }, modifier = Modifier.size(32.dp)) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Reset Time",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
            }

            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Map") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("List") }
                )
            }

            when (selectedTab) {
                0 -> HistoryMapTab(
                    heartbeats = heartbeats,
                    addresses = addresses,
                    modifier = Modifier.weight(1f)
                )
                1 -> HistoryListTab(
                    heartbeats = heartbeats,
                    addresses = addresses,
                    timeFormat = timeFormat,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                )
            }
        }
    }

    if (showDatePicker) {
        HistoryDatePickerDialog(
            currentDayStart = selectedDayStart,
            onConfirm = { dayStartMs ->
                vm.selectDay(dayStartMs)
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false }
        )
    }

    if (showStartTimePicker) {
        val currentMinutes = (startTimeOffset / 60_000).toInt()
        TimePickerDialog(
            initialMinute = currentMinutes,
            title = "Start Time",
            onConfirm = { minutes ->
                vm.setTimeRange(minutes * 60_000L, endTimeOffset)
                showStartTimePicker = false
            },
            onDismiss = { showStartTimePicker = false }
        )
    }

    if (showEndTimePicker) {
        val currentMinutes = (endTimeOffset / 60_000).toInt()
        TimePickerDialog(
            initialMinute = currentMinutes,
            title = "End Time",
            onConfirm = { minutes ->
                vm.setTimeRange(startTimeOffset, minutes * 60_000L)
                showEndTimePicker = false
            },
            onDismiss = { showEndTimePicker = false }
        )
    }
}

@Composable
private fun HistoryListTab(
    heartbeats: List<HeartbeatEntity>,
    addresses: Map<Long, String>,
    timeFormat: SimpleDateFormat,
    modifier: Modifier = Modifier
) {
    if (heartbeats.isEmpty()) {
        EmptyState(
            icon = Icons.Default.History,
            title = "No location data",
            subtitle = "There are no recorded pings for this day or time range.",
            modifier = modifier
        )
    } else {
        Column(modifier = modifier) {
            Spacer(Modifier.height(8.dp))
            Text(
                "${heartbeats.size} ping${if (heartbeats.size == 1) "" else "s"} recorded",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            val newestFirst = heartbeats.asReversed()
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(newestFirst, key = { it.id }) { ping ->
                    HistoryPingCard(
                        ping = ping,
                        address = addresses[ping.id],
                        timeFormat = timeFormat
                    )
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun HistoryMapTab(
    heartbeats: List<HeartbeatEntity>,
    addresses: Map<Long, String>,
    modifier: Modifier = Modifier
) {
    if (heartbeats.isEmpty()) {
        EmptyState(
            icon = Icons.Default.History,
            title = "No location data",
            subtitle = "There are no recorded pings for this day or time range.",
            modifier = modifier
        )
        return
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var selectedPing by remember { mutableStateOf<HeartbeatEntity?>(null) }
    val timestampFormat = remember(DisplayFormat.use24HourTime) {
        SimpleDateFormat("d MMM yyyy · ${DisplayFormat.timePattern(withSeconds = true)}", Locale.getDefault())
    }

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
            mapViewRef?.onPause()
            mapViewRef?.onDetach()
            mapViewRef = null
        }
    }

    Box(modifier = modifier.clipToBounds()) {
        AndroidView(
            factory = { ctx ->
                @Suppress("DEPRECATION")
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setBuiltInZoomControls(false)
                    setMultiTouchControls(true)
                    isVerticalMapRepetitionEnabled = false
                    setOnTouchListener { v, event ->
                        when (event.action) {
                            android.view.MotionEvent.ACTION_DOWN,
                            android.view.MotionEvent.ACTION_MOVE ->
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                            android.view.MotionEvent.ACTION_UP,
                            android.view.MotionEvent.ACTION_CANCEL ->
                                v.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                        false
                    }
                }.also { mapViewRef = it }
            },
            update = { mapView ->
                mapView.overlays.clear()
                if (heartbeats.isEmpty()) return@AndroidView

                val pinColor = heartbeats.last().pinColor
                val lineArgb = if (pinColor.isNotEmpty())
                    android.graphics.Color.parseColor(pinColor).let {
                        android.graphics.Color.argb(200, android.graphics.Color.red(it), android.graphics.Color.green(it), android.graphics.Color.blue(it))
                    }
                else android.graphics.Color.argb(200, 66, 133, 244)

                val polyline = Polyline().apply {
                    setPoints(heartbeats.map { GeoPoint(it.lat, it.lng) })
                    outlinePaint.color = lineArgb
                    outlinePaint.strokeWidth = 6f
                    outlinePaint.isAntiAlias = true
                }
                mapView.overlays.add(polyline)

                heartbeats.forEachIndexed { index, ping ->
                    val isLatest = index == heartbeats.lastIndex
                    val marker = Marker(mapView).apply {
                        position = GeoPoint(ping.lat, ping.lng)
                        infoWindow = null
                        if (isLatest) {
                            val icon = MarkerIconFactory.create(
                                context = mapView.context,
                                displayName = ping.displayName,
                                isOverdue = false,
                                isSos = ping.isSos,
                                pinColor = ping.pinColor
                            )
                            setIcon(icon)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        } else {
                            val icon = MarkerIconFactory.createDotIcon(
                                context = mapView.context,
                                pinColor = ping.pinColor,
                                isSos = ping.isSos
                            )
                            setIcon(icon)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        }
                        setOnMarkerClickListener { _, _ ->
                            selectedPing = ping
                            true
                        }
                    }
                    mapView.overlays.add(marker)
                }

                val points = heartbeats.map { GeoPoint(it.lat, it.lng) }
                val bounds = BoundingBox.fromGeoPoints(points)
                if (points.size == 1 || (bounds.latitudeSpan < 1e-4 && bounds.longitudeSpanWithDateLine < 1e-4)) {
                    mapView.controller.setZoom(16.0)
                    mapView.controller.setCenter(GeoPoint(bounds.centerLatitude, bounds.centerLongitude))
                } else if (mapView.width > 0) {
                    mapView.zoomToBoundingBox(bounds, false, 64)
                } else {
                    mapView.addOnFirstLayoutListener { _, _, _, _, _ ->
                        mapView.zoomToBoundingBox(bounds, false, 64)
                    }
                }
                mapView.invalidate()
            },
            modifier = Modifier.fillMaxSize()
        )

        selectedPing?.let { ping ->
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (ping.isSos)
                        MaterialTheme.colorScheme.errorContainer
                    else
                        MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (ping.isSos) {
                                Icon(Icons.Default.Warning, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp))
                            }
                            Text(
                                timestampFormat.format(Date(ping.timestamp)),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        IconButton(onClick = { selectedPing = null }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                        }
                    }

                    addresses[ping.id]?.let { addr ->
                        Spacer(Modifier.height(4.dp))
                        Text(addr, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(
                        "%.5f°, %.5f°".format(ping.lat, ping.lng),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("±${DisplayFormat.distanceValue(ping.accuracy.toDouble())} accuracy",
                            style = MaterialTheme.typography.labelSmall)
                        Text("🔋 ${ping.battery}%",
                            style = MaterialTheme.typography.labelSmall)
                        Text(speedLabel(ping.motionState, ping.speed, ping.bearing),
                            style = MaterialTheme.typography.labelSmall)
                        if (ping.altitude != 0.0) {
                            Text("⛰ ${DisplayFormat.elevationValue(ping.altitude)}",
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    MotionChip(ping.motionState)
                }
            }
        }
    }
}

@Composable
private fun HistoryPingCard(
    ping: HeartbeatEntity,
    address: String?,
    timeFormat: SimpleDateFormat
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (ping.isSos)
                MaterialTheme.colorScheme.errorContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
                Text(
                    timeFormat.format(Date(ping.timestamp)),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (ping.isSos) MaterialTheme.colorScheme.onErrorContainer
                            else MaterialTheme.colorScheme.primary
                )
                if (ping.isSos) {
                    Spacer(Modifier.height(2.dp))
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = "SOS",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            VerticalDivider(modifier = Modifier.height(56.dp))

            Column(modifier = Modifier.weight(1f)) {
                if (!address.isNullOrBlank()) {
                    Text(
                        address,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "%.5f°, %.5f°".format(ping.lat, ping.lng),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "%.5f°, %.5f°".format(ping.lat, ping.lng),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "±${DisplayFormat.distanceValue(ping.accuracy.toDouble())}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "🔋 ${ping.battery}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        speedLabel(ping.motionState, ping.speed, ping.bearing),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (ping.altitude != 0.0) {
                        Text(
                            "⛰ ${DisplayFormat.elevationValue(ping.altitude)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                MotionChip(ping.motionState)
            }
        }
    }
}

@Composable
private fun MotionChip(motionState: String) {
    val label = when (motionState.uppercase()) {
        "WALKING" -> "Walking"
        "DRIVING" -> "Driving"
        else -> "Stationary"
    }
    val color = when (motionState.uppercase()) {
        "WALKING" -> MaterialTheme.colorScheme.tertiaryContainer
        "DRIVING" -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryDatePickerDialog(
    currentDayStart: Long,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val initialUtcMs = localDayStartToUtcMidnight(currentDayStart)
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialUtcMs,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= initialUtcTodayMs()
        }
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                datePickerState.selectedDateMillis?.let { utcMs ->
                    onConfirm(utcMidnightToLocalDayStart(utcMs))
                } ?: onDismiss()
            }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    ) {
        DatePicker(state = datePickerState)
    }
}

private fun localDayStartToUtcMidnight(localMs: Long): Long {
    val local = Calendar.getInstance().apply { timeInMillis = localMs }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

private fun utcMidnightToLocalDayStart(utcMs: Long): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMs }
    return Calendar.getInstance().apply {
        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

/**
 * Human-readable speed for a ping. Always returns a value so location history rows and
 * the pin popup have a speed field even when the person is still (shown as "0 km/h" /
 * "0 mph") rather than the field vanishing entirely. Units follow the user's setting.
 */
private fun speedLabel(motionState: String, speed: Float, bearing: Float): String {
    return if (!motionState.equals("STATIONARY", ignoreCase = true) && speed > 0f) {
        "${DisplayFormat.speedValue(speed)} · ${bearingToCardinal(bearing)}"
    } else {
        DisplayFormat.speedValue(0f)
    }
}

private fun bearingToCardinal(bearing: Float): String {
    val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val normalized = ((bearing % 360f) + 360f) % 360f
    return dirs[((normalized + 22.5f) / 45f).toInt() % 8]
}

private fun initialUtcTodayMs(): Long {
    val today = Calendar.getInstance()
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        set(today.get(Calendar.YEAR), today.get(Calendar.MONTH), today.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
