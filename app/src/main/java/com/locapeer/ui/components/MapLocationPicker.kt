package com.locapeer.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.locapeer.R
import com.locapeer.map.LocaMap
import com.locapeer.map.MapGeometry
import com.locapeer.map.MapMarker
import com.locapeer.map.MarkerIconFactory

@Composable
fun MapLocationPicker(
    initialLat: Double,
    initialLng: Double,
    onLocationSelected: (Double, Double) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val selectedPoint = remember { mutableStateOf(initialLat to initialLng) }
    val pin = remember { MarkerIconFactory.create(context, "#1565C0", false, false, "").bitmap }
    val (lat, lng) = selectedPoint.value

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.picker_title)) },
        text = {
            Box(modifier = Modifier.fillMaxSize().padding(vertical = 8.dp)) {
                LocaMap(
                    modifier = Modifier.fillMaxSize(),
                    markers = listOf(MapMarker(id = "picked", lat = lat, lng = lng, icon = pin, clickable = false)),
                    onMapClick = { p ->
                        selectedPoint.value = p.latitude to p.longitude
                        true
                    },
                    onReady = { map -> map.moveTo(initialLat, initialLng, MapGeometry.fromLegacyZoom(12.0)) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onLocationSelected(lat, lng) }) {
                Text(stringResource(R.string.common_select))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}
