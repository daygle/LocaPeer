package com.locapeer.history

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.locapeer.data.entity.HeartbeatEntity
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
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
    onNavigateBack: () -> Unit,
    vm: HistoryReportViewModel = hiltViewModel()
) {
    val broadcasters by vm.broadcasters.collectAsState()
    val selectedPeerId by vm.selectedPeerId.collectAsState()
    val selectedDayStart by vm.selectedDayStart.collectAsState()
    val heartbeats by vm.heartbeats.collectAsState()
    val addresses by vm.addresses.collectAsState()

    val selectedPeer = broadcasters.find { it.deviceId == selectedPeerId }

    var showDatePicker by remember { mutableStateOf(false) }
    var peerDropdownExpanded by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }

    val dateFormat = remember { SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Location History") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            if (broadcasters.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No tracked people found.\nScan an invite QR code to start tracking someone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp)
                    )
                }
            } else {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(12.dp))

                ExposedDropdownMenuBox(
                    expanded = peerDropdownExpanded,
                    onExpandedChange = { peerDropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedPeer?.displayName ?: "Select person",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Person") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(peerDropdownExpanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = peerDropdownExpanded,
                        onDismissRequest = { peerDropdownExpanded = false }
                    ) {
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

                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { vm.prevDay() }) {
                            Icon(Icons.Default.ChevronLeft, contentDescription = "Previous day")
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
                            Icon(Icons.Default.ChevronRight, contentDescription = "Next day")
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
            }

            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("List") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Map") }
                )
            }

            when (selectedTab) {
                0 -> HistoryListTab(
                    heartbeats = heartbeats,
                    addresses = addresses,
                    timeFormat = timeFormat,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                )
                1 -> HistoryMapTab(
                    heartbeats = heartbeats,
                    modifier = Modifier.fillMaxSize()
                )
            }
            } // end else
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
}

@Composable
private fun HistoryListTab(
    heartbeats: List<HeartbeatEntity>,
    addresses: Map<Long, String>,
    timeFormat: SimpleDateFormat,
    modifier: Modifier = Modifier
) {
    if (heartbeats.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                "No location data for this day",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        Column(modifier = modifier) {
            Spacer(Modifier.height(8.dp))
            Text(
                "${heartbeats.size} ping${if (heartbeats.size == 1) "" else "s"} recorded",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(heartbeats, key = { it.id }) { ping ->
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
    modifier: Modifier = Modifier
) {
    if (heartbeats.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                "No location data for this day",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

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

    AndroidView(
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setBuiltInZoomControls(false)
                setMultiTouchControls(true)
                isVerticalMapRepetitionEnabled = false
            }.also { mapViewRef = it }
        },
        update = { mapView ->
            mapView.overlays.clear()

            if (heartbeats.isNotEmpty()) {
                val points = heartbeats.map { GeoPoint(it.lat, it.lng) }

                val polyline = Polyline().apply {
                    setPoints(points)
                    outlinePaint.color = android.graphics.Color.argb(200, 66, 133, 244)
                    outlinePaint.strokeWidth = 6f
                    outlinePaint.isAntiAlias = true
                }
                mapView.overlays.add(polyline)

                heartbeats.firstOrNull()?.let { first ->
                    val marker = Marker(mapView).apply {
                        position = GeoPoint(first.lat, first.lng)
                        title = "Start"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        infoWindow = null
                    }
                    mapView.overlays.add(marker)
                }

                if (heartbeats.size > 1) {
                    heartbeats.lastOrNull()?.let { last ->
                        val marker = Marker(mapView).apply {
                            position = GeoPoint(last.lat, last.lng)
                            title = "Latest"
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            infoWindow = null
                        }
                        mapView.overlays.add(marker)
                    }
                }

                val centerLat = heartbeats.map { it.lat }.average()
                val centerLng = heartbeats.map { it.lng }.average()
                mapView.controller.setCenter(GeoPoint(centerLat, centerLng))
                mapView.controller.setZoom(16.0)
            }

            mapView.invalidate()
        },
        modifier = modifier
    )
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
                        "±${ping.accuracy.toInt()} m",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "🔋 ${ping.battery}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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

private fun initialUtcTodayMs(): Long {
    val today = Calendar.getInstance()
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        set(today.get(Calendar.YEAR), today.get(Calendar.MONTH), today.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
