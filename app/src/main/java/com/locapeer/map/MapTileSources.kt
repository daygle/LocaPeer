package com.locapeer.map

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.MapView
import java.util.WeakHashMap

object MapTileSources {
    // Use osmdroid's built-in MAPNIK source for the light map. It is already used by the
    // geofence picker and avoids relying on the public Carto Voyager endpoint, which can return
    // grey placeholder tiles for some networks while the rest of the map still renders.
    val LIGHT = TileSourceFactory.MAPNIK

    // Dark mode renders the same MAPNIK tiles through a colour filter rather than a separate
    // dark tile server: Carto's Dark Matter endpoint now serves "API key required" tiles.
    // The filter produces an inverted, desaturated map (dark land, light labels).
    private val DARK_FILTER = run {
        val k = 0.9f
        val lift = 20f
        val row = floatArrayOf(-0.2126f * k, -0.7152f * k, -0.0722f * k, 0f, 255f * k + lift)
        ColorMatrixColorFilter(
            ColorMatrix(
                row + row + row + floatArrayOf(0f, 0f, 0f, 1f, 0f)
            )
        )
    }

    // Tracks which maps currently have the dark filter so recompositions don't re-apply it.
    private val darkApplied = WeakHashMap<MapView, Boolean>()

    fun apply(mapView: MapView, isDark: Boolean) {
        if (mapView.tileProvider.tileSource != LIGHT) mapView.setTileSource(LIGHT)
        if (darkApplied[mapView] != isDark) {
            darkApplied[mapView] = isDark
            mapView.overlayManager.tilesOverlay.setColorFilter(if (isDark) DARK_FILTER else null)
            mapView.invalidate()
        }
    }
}
