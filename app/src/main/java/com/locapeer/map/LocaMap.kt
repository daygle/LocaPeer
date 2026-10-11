package com.locapeer.map

import android.graphics.Bitmap
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import java.util.Locale

/** Where a marker's icon sits relative to its coordinate. */
enum class MarkerAnchor(internal val value: String) { BOTTOM(Property.ICON_ANCHOR_BOTTOM), CENTER(Property.ICON_ANCHOR_CENTER) }

/** A bitmap pin. [id] is reported to `onMarkerClick` when [clickable]; higher [zIndex] draws on top. */
data class MapMarker(
    val id: String,
    val lat: Double,
    val lng: Double,
    val icon: Bitmap,
    val anchor: MarkerAnchor = MarkerAnchor.BOTTOM,
    val clickable: Boolean = true,
    val zIndex: Float = 0f,
)

/** A geofence-style circle, drawn as a filled polygon with an outline. Colours are ARGB ints. */
data class MapCircle(
    val lat: Double,
    val lng: Double,
    val radiusMetres: Double,
    val fillArgb: Int,
    val strokeArgb: Int,
    val strokeWidthDp: Float = 2f,
)

/** A polyline such as a history trail. */
data class MapLine(val points: List<LatLng>, val argb: Int, val widthDp: Float = 3f)

/**
 * Camera helpers over a ready map. Zoom values here are MapLibre's; convert stored osmdroid-scale
 * values with [MapGeometry.fromLegacyZoom].
 */
class LocaMapController internal constructor(val map: MapLibreMap, val view: MapView) {
    val target: LatLng? get() = map.cameraPosition.target
    val zoom: Double get() = map.cameraPosition.zoom

    fun animateTo(lat: Double, lng: Double, zoom: Double? = null) {
        val target = LatLng(lat, lng)
        map.animateCamera(if (zoom == null) CameraUpdateFactory.newLatLng(target) else CameraUpdateFactory.newLatLngZoom(target, zoom))
    }

    fun moveTo(lat: Double, lng: Double, zoom: Double? = null) {
        val target = LatLng(lat, lng)
        map.moveCamera(if (zoom == null) CameraUpdateFactory.newLatLng(target) else CameraUpdateFactory.newLatLngZoom(target, zoom))
    }

    /**
     * Frame [points] with [paddingPx] around them. A single point (or points too close together to
     * form bounds) is centred at [singlePointZoom] instead, since there is nothing to fit.
     */
    fun fit(points: List<LatLng>, paddingPx: Int, animate: Boolean, singlePointZoom: Double) {
        if (points.isEmpty()) return
        whenLaidOut {
            val lats = points.map { it.latitude }
            val lngs = points.map { it.longitude }
            val tiny = lats.max() - lats.min() < 1e-4 && lngs.max() - lngs.min() < 1e-4
            val update = if (points.size == 1 || tiny) {
                CameraUpdateFactory.newLatLngZoom(LatLng(lats.average(), lngs.average()), singlePointZoom)
            } else {
                CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(points).build(), paddingPx)
            }
            if (animate) map.animateCamera(update) else map.moveCamera(update)
        }
    }

    /** Bounds fitting needs the view's size; run [block] now if measured, else after first layout. */
    private fun whenLaidOut(block: () -> Unit) {
        if (view.width > 0 && view.height > 0) {
            block()
            return
        }
        view.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                if (v.width == 0 || v.height == 0) return
                v.removeOnLayoutChangeListener(this)
                block()
            }
        })
    }
}

private const val OSM_SOURCE = "osm"
private const val OSM_LAYER = "osm-tiles"
private const val CIRCLE_SOURCE = "loca-circles"
private const val CIRCLE_FILL_LAYER = "loca-circles-fill"
private const val CIRCLE_LINE_LAYER = "loca-circles-line"
private const val LINE_SOURCE = "loca-lines"
private const val LINE_LAYER = "loca-lines"
private const val MARKER_SOURCE = "loca-markers"
private const val MARKER_LAYER = "loca-markers"

/** OpenStreetMap's standard raster tiles, as osmdroid's MAPNIK source used. No API key needed. */
private const val OSM_TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
private const val OSM_ATTRIBUTION = "<a href=\"https://www.openstreetmap.org/copyright\">© OpenStreetMap contributors</a>"
private const val OSM_MAX_ZOOM = 19f
/** MapLibre zoom (512 px world) at which OSM tiles stop gaining detail; a little overzoom is fine. */
private const val MAP_MAX_ZOOM = 19.0

/**
 * The app's map: OpenStreetMap raster tiles rendered by MapLibre Native, with optional dark
 * styling, bitmap markers, circles and lines, plus the scale bar and device compass overlays.
 * The MapView's lifecycle follows the hosting screen's.
 *
 * [onReady] runs once the style has loaded, with a [LocaMapController] for camera control.
 * [onMapClick] / [onMapLongClick] return true when they consumed the gesture. [onCameraIdle]
 * reports where the camera settled after each move, in MapLibre zoom.
 */
@Composable
fun LocaMap(
    modifier: Modifier = Modifier,
    isDark: Boolean = false,
    markers: List<MapMarker> = emptyList(),
    circles: List<MapCircle> = emptyList(),
    lines: List<MapLine> = emptyList(),
    showScaleBar: Boolean = false,
    showCompass: Boolean = false,
    onMarkerClick: ((String) -> Unit)? = null,
    onMapClick: ((LatLng) -> Boolean)? = null,
    onMapLongClick: ((LatLng) -> Boolean)? = null,
    onCameraIdle: ((target: LatLng, zoom: Double) -> Unit)? = null,
    onReady: (LocaMapController) -> Unit = {},
    configureView: (MapView) -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val context = LocalContext.current
    val densityDpi = LocalResources.current.displayMetrics.densityDpi
    val lifecycleOwner = LocalLifecycleOwner.current
    val markerClick by rememberUpdatedState(onMarkerClick)
    val mapClick by rememberUpdatedState(onMapClick)
    val mapLongClick by rememberUpdatedState(onMapLongClick)
    val ready by rememberUpdatedState(onReady)
    val cameraIdle by rememberUpdatedState(onCameraIdle)

    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).apply {
            onCreate(null)
            configureView(this)
        }
    }
    var loaded by remember { mutableStateOf<Pair<LocaMapController, Style>?>(null) }
    var camera by remember { mutableStateOf<Pair<Double, Double>?>(null) } // (lat, zoom)
    val registeredImages = remember { HashSet<String>() }

    // Mirror the screen's lifecycle onto the MapView. addObserver replays the events up to the
    // current state, so a map created on an already-resumed screen still starts and resumes.
    DisposableEffect(lifecycleOwner, mapView) {
        var state = Lifecycle.State.CREATED
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { mapView.onStart(); state = Lifecycle.State.STARTED }
                Lifecycle.Event.ON_RESUME -> { mapView.onResume(); state = Lifecycle.State.RESUMED }
                Lifecycle.Event.ON_PAUSE -> { mapView.onPause(); state = Lifecycle.State.STARTED }
                Lifecycle.Event.ON_STOP -> { mapView.onStop(); state = Lifecycle.State.CREATED }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (state == Lifecycle.State.RESUMED) mapView.onPause()
            if (state.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }

    DisposableEffect(mapView) {
        mapView.getMapAsync { map ->
            map.uiSettings.apply {
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
                isCompassEnabled = false
                isLogoEnabled = false
                isAttributionEnabled = false
            }
            map.setMaxZoomPreference(MAP_MAX_ZOOM)
            map.addOnCameraMoveListener { camera = map.cameraPosition.let { (it.target?.latitude ?: 0.0) to it.zoom } }
            map.addOnCameraIdleListener {
                val position = map.cameraPosition
                camera = (position.target?.latitude ?: 0.0) to position.zoom
                position.target?.let { cameraIdle?.invoke(it, position.zoom) }
            }
            map.addOnMapClickListener { point ->
                val hit = markerAt(map, point)
                if (hit != null) {
                    markerClick?.invoke(hit)
                    true
                } else {
                    mapClick?.invoke(point) ?: false
                }
            }
            map.addOnMapLongClickListener { point -> mapLongClick?.invoke(point) ?: false }
            map.setStyle(baseStyle()) { style ->
                addOverlayLayers(style)
                val controller = LocaMapController(map, mapView)
                camera = map.cameraPosition.let { (it.target?.latitude ?: 0.0) to it.zoom }
                loaded = controller to style
                ready(controller)
            }
        }
        onDispose { loaded = null }
    }

    LaunchedEffect(loaded, isDark) {
        val style = loaded?.second ?: return@LaunchedEffect
        style.getLayerAs<RasterLayer>(OSM_LAYER)?.setProperties(*rasterStyle(isDark))
    }
    LaunchedEffect(loaded, markers) {
        val style = loaded?.second ?: return@LaunchedEffect
        val used = HashSet<String>()
        val features = markers.map { m ->
            val key = "icon-" + System.identityHashCode(m.icon)
            if (used.add(key) && !registeredImages.contains(key)) {
                // MapLibre sizes images by bitmap density; an unset density would draw them at 0 px.
                if (m.icon.density == Bitmap.DENSITY_NONE) m.icon.density = densityDpi
                style.addImage(key, m.icon)
                registeredImages.add(key)
            }
            Feature.fromGeometry(Point.fromLngLat(m.lng, m.lat)).apply {
                addStringProperty("id", m.id)
                addStringProperty("icon", key)
                addStringProperty("anchor", m.anchor.value)
                addStringProperty("click", if (m.clickable) "1" else "0")
                addNumberProperty("z", m.zIndex)
            }
        }
        style.getSourceAs<GeoJsonSource>(MARKER_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(features))
        // Icons are regenerated as pins change; drop the ones no marker uses any more.
        val toRemove = registeredImages - used
        registeredImages.removeAll(toRemove)
        toRemove.forEach { style.removeImage(it) }
    }
    LaunchedEffect(loaded, circles) {
        val style = loaded?.second ?: return@LaunchedEffect
        val features = circles.map { c ->
            val ring = MapGeometry.circle(c.lat, c.lng, c.radiusMetres).map { (lat, lng) -> Point.fromLngLat(lng, lat) }
            Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))).apply {
                addStringProperty("fill", rgba(c.fillArgb))
                addStringProperty("stroke", rgba(c.strokeArgb))
                addNumberProperty("width", c.strokeWidthDp)
            }
        }
        style.getSourceAs<GeoJsonSource>(CIRCLE_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }
    LaunchedEffect(loaded, lines) {
        val style = loaded?.second ?: return@LaunchedEffect
        val features = lines.filter { it.points.size >= 2 }.map { l ->
            Feature.fromGeometry(LineString.fromLngLats(l.points.map { Point.fromLngLat(it.longitude, it.latitude) })).apply {
                addStringProperty("color", rgba(l.argb))
                addNumberProperty("width", l.widthDp)
            }
        }
        style.getSourceAs<GeoJsonSource>(LINE_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    Box(modifier = modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        val cam = camera
        if (showScaleBar && cam != null) MapScaleBar(lat = cam.first, zoom = cam.second, isDark = isDark)
        if (showCompass) DeviceCompass()
        overlay()
    }
}

/** The clickable marker under [point], if any. */
private fun markerAt(map: MapLibreMap, point: LatLng): String? {
    val screen = map.projection.toScreenLocation(point)
    return map.queryRenderedFeatures(screen, MARKER_LAYER)
        .firstOrNull { it.getStringProperty("click") == "1" }
        ?.getStringProperty("id")
}

private fun baseStyle(): Style.Builder {
    val tiles = TileSet("2.2.0", OSM_TILE_URL).apply {
        attribution = OSM_ATTRIBUTION
        setMaxZoom(OSM_MAX_ZOOM)
    }
    return Style.Builder()
        .withSource(RasterSource(OSM_SOURCE, tiles, 256))
        .withLayer(RasterLayer(OSM_LAYER, OSM_SOURCE))
}

private fun addOverlayLayers(style: Style) {
    style.addSource(GeoJsonSource(CIRCLE_SOURCE))
    style.addSource(GeoJsonSource(LINE_SOURCE))
    style.addSource(GeoJsonSource(MARKER_SOURCE))
    style.addLayer(
        FillLayer(CIRCLE_FILL_LAYER, CIRCLE_SOURCE).withProperties(
            PropertyFactory.fillColor(Expression.toColor(Expression.get("fill")))
        )
    )
    style.addLayer(
        LineLayer(CIRCLE_LINE_LAYER, CIRCLE_SOURCE).withProperties(
            PropertyFactory.lineColor(Expression.toColor(Expression.get("stroke"))),
            PropertyFactory.lineWidth(Expression.get("width")),
        )
    )
    style.addLayer(
        LineLayer(LINE_LAYER, LINE_SOURCE).withProperties(
            PropertyFactory.lineColor(Expression.toColor(Expression.get("color"))),
            PropertyFactory.lineWidth(Expression.get("width")),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        )
    )
    style.addLayer(
        SymbolLayer(MARKER_LAYER, MARKER_SOURCE).withProperties(
            PropertyFactory.iconImage(Expression.get("icon")),
            PropertyFactory.iconAnchor(Expression.get("anchor")),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.symbolSortKey(Expression.get("z")),
        )
    )
}

/**
 * Dark mode recolours the same OSM tiles instead of using a separate dark tile server: fully
 * desaturated, with brightness inverted (light land becomes dark, dark labels become light).
 * Swapping the brightness bounds is what inverts it.
 */
private fun rasterStyle(isDark: Boolean) = if (isDark) {
    arrayOf(
        PropertyFactory.rasterSaturation(-1f),
        PropertyFactory.rasterBrightnessMin(0.98f),
        PropertyFactory.rasterBrightnessMax(0.08f),
    )
} else {
    arrayOf(
        PropertyFactory.rasterSaturation(0f),
        PropertyFactory.rasterBrightnessMin(0f),
        PropertyFactory.rasterBrightnessMax(1f),
    )
}

/** ARGB int to a MapLibre colour string. */
internal fun rgba(argb: Int): String = String.format(
    Locale.US, "rgba(%d, %d, %d, %.3f)",
    (argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF, ((argb ushr 24) and 0xFF) / 255f
)
