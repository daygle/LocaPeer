package com.locapeer.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.locapeer.map.MarkerIconFactory
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

@Composable
fun MapLocationPicker(
    initialLat: Double,
    initialLng: Double,
    onLocationSelected: (Double, Double) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedPoint = remember { GeoPoint(initialLat, initialLng) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick Fixed Location") },
        text = {
            Box(modifier = Modifier.fillMaxSize().padding(vertical = 8.dp)) {
                AndroidView(
                    factory = { context ->
                        MapView(context).apply {
                            setTileSource(TileSourceFactory.MAPNIK)
                            setMultiTouchControls(true)
                            controller.setZoom(12.0)
                            
                            val startPoint = if (initialLat == 0.0 && initialLng == 0.0) {
                                GeoPoint(0.0, 0.0)
                            } else {
                                GeoPoint(initialLat, initialLng)
                            }
                            controller.setCenter(startPoint)

                            val marker = Marker(this).apply {
                                position = startPoint
                                setIcon(MarkerIconFactory.create(context, "#1565C0", false, false, ""))
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                isDraggable = false
                            }
                            overlays.add(marker)

                            val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
                                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                                    selectedPoint = p
                                    marker.position = p
                                    invalidate()
                                    return true
                                }
                                override fun longPressHelper(p: GeoPoint): Boolean = false
                            })
                            overlays.add(eventsOverlay)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onLocationSelected(selectedPoint.latitude, selectedPoint.longitude) }) {
                Text("Select")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
