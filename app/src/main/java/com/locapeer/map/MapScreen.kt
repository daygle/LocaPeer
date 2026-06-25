package com.locapeer.map

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.locapeer.data.entity.GeofenceEntity
import com.locapeer.data.entity.HeartbeatEntity
import com.locapeer.data.entity.PeerEntity
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

@Composable
fun MapScreen(
    onNavigateToChat: (String) -> Unit,
    vm: MapViewModel = hiltViewModel()
) {
    val uiState by vm.uiState.collectAsState()
    var selectedPin by remember { mutableStateOf<PinData?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        OsmdroidMapView(
            pins = uiState.pins,
            geofences = uiState.geofences,
            onPinTapped = { selectedPin = it },
            modifier = Modifier.fillMaxSize()
        )

        selectedPin?.let { pin ->
            PinInfoSheet(
                pin = pin,
                formatTimestamp = vm::formatTimestamp,
                onDismiss = { selectedPin = null },
                onMessage = {
                    selectedPin = null
                    onNavigateToChat(pin.peer.deviceId)
                },
                onViewHistory = { /* history could open a new screen */ }
            )
        }
    }
}

@Composable
private fun OsmdroidMapView(
    pins: List<PinData>,
    geofences: List<GeofenceEntity>,
    onPinTapped: (PinData) -> Unit,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { ctx ->
            Configuration.getInstance().userAgentValue = "LocaPeer/1.0"
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setBuiltInZoomControls(false)
                setMultiTouchControls(true)
                controller.setZoom(13.0)
                controller.setCenter(GeoPoint(51.5, -0.1))
            }
        },
        update = { mapView ->
            mapView.overlays.clear()
            pins.forEach { pinData ->
                val hb = pinData.heartbeat ?: return@forEach
                val marker = Marker(mapView).apply {
                    position = GeoPoint(hb.lat, hb.lng)
                    title = pinData.peer.displayName
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    setOnMarkerClickListener { _, _ ->
                        onPinTapped(pinData)
                        true
                    }
                }
                mapView.overlays.add(marker)
            }
            geofences.forEach { fence ->
                val color = when (fence.triggerOn) {
                    "ENTER" -> android.graphics.Color.argb(50, 33, 150, 243)
                    "EXIT" -> android.graphics.Color.argb(50, 255, 152, 0)
                    else -> android.graphics.Color.argb(50, 156, 39, 176)
                }
                val circle = Polygon().apply {
                    points = Polygon.pointsAsCircle(GeoPoint(fence.lat, fence.lng), fence.radiusMetres.toDouble())
                    fillPaint.color = color
                    outlinePaint.color = color
                    outlinePaint.strokeWidth = 2f
                }
                mapView.overlays.add(circle)
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
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            elevation = CardDefaults.cardElevation(8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(pin.peer.displayName, style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                if (hb != null) {
                    Text("Last seen: ${formatTimestamp(hb.timestamp)}", style = MaterialTheme.typography.bodyMedium)
                    Text("Battery: ${hb.battery}%", style = MaterialTheme.typography.bodyMedium)
                    Text("Motion: ${hb.motionState}", style = MaterialTheme.typography.bodyMedium)
                    if (pin.isOverdue) {
                        Text(
                            "Overdue — no recent update",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    Text("No location data yet", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onViewHistory) { Text("View History") }
                    Button(onClick = onMessage) { Text("Message") }
                }
            }
        }
    }
}
