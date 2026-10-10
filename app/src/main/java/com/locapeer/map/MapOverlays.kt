package com.locapeer.map

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.locapeer.util.DisplayFormat

/** Longest the scale bar may grow, in dp. */
private const val SCALE_BAR_MAX_DP = 100.0

/**
 * Distance scale in the top-right corner, under the compass, sized for the current zoom at the map's centre
 * latitude, in the user's distance unit.
 */
@Composable
internal fun BoxScope.MapScaleBar(lat: Double, zoom: Double, isDark: Boolean) {
    val metresPerDp = MapGeometry.metresPerDp(lat, zoom)
    if (!metresPerDp.isFinite() || metresPerDp <= 0.0) return
    val (metres, label) = MapGeometry.scaleBar(SCALE_BAR_MAX_DP * metresPerDp, DisplayFormat.useImperialDistance)
    val widthDp = (metres / metresPerDp).toFloat()
    if (widthDp <= 0f) return
    val ink = if (isDark) Color.White else Color.Black
    Column(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .statusBarsPadding()
            .padding(top = 72.dp, end = 20.dp),
        horizontalAlignment = Alignment.End
    ) {
        Text(label, color = ink, fontSize = 11.sp)
        Canvas(modifier = Modifier.width(widthDp.dp).height(6.dp)) {
            val stroke = 2.dp.toPx()
            drawLine(ink, Offset(0f, size.height), Offset(size.width, size.height), stroke)
            drawLine(ink, Offset(0f, 0f), Offset(0f, size.height), stroke)
            drawLine(ink, Offset(size.width, 0f), Offset(size.width, size.height), stroke)
        }
    }
}

/**
 * Device-heading compass in the top-right corner: the red needle points to magnetic north as the
 * phone turns (the map itself never rotates). Hidden on devices without a rotation sensor.
 */
@Composable
internal fun BoxScope.DeviceCompass() {
    val context = LocalContext.current
    var azimuth by remember { mutableStateOf<Float?>(null) }

    DisposableEffect(context) {
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val listener = object : SensorEventListener {
            private val rotation = FloatArray(9)
            private val remapped = FloatArray(9)
            private val orientation = FloatArray(3)
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                // Account for the screen orientation so north stays correct in landscape.
                val (x, y) = when (displayRotation(context)) {
                    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                SensorManager.remapCoordinateSystem(rotation, x, y, remapped)
                SensorManager.getOrientation(remapped, orientation)
                azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (sensor != null) sensors?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sensors?.unregisterListener(listener) }
    }

    val heading = azimuth ?: return
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .statusBarsPadding()
            .padding(top = 16.dp, end = 21.dp)
            .size(48.dp)
            .background(Color.White.copy(alpha = 0.85f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(36.dp).rotate(-heading)) {
            val cx = size.width / 2
            val cy = size.height / 2
            val half = size.width * 0.12f
            val north = Path().apply {
                moveTo(cx, 0f); lineTo(cx - half, cy); lineTo(cx + half, cy); close()
            }
            val south = Path().apply {
                moveTo(cx, size.height); lineTo(cx - half, cy); lineTo(cx + half, cy); close()
            }
            drawPath(north, Color(0xFFD32F2F))
            drawPath(south, Color(0xFF616161))
        }
    }
}

@Suppress("DEPRECATION")
private fun displayRotation(context: Context): Int =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        // getDisplay() throws for a context not tied to a display; treat that as portrait.
        runCatching { context.display?.rotation }.getOrNull() ?: Surface.ROTATION_0
    } else {
        (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.rotation ?: Surface.ROTATION_0
    }
