package com.locapeer.geofence

import com.locapeer.data.entity.GeofenceEntity
import com.locapeer.util.GeoMath
import com.locapeer.util.GeofencePolygon
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.w3c.dom.Element
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import kotlin.math.roundToInt

/**
 * Import/export of geofence areas as GeoJSON or KML, the two formats other mapping tools
 * understand. Circles have no native representation in either format, so they are written
 * as a Point with a `radiusMetres` property (a KML ExtendedData value) and read back that
 * way; polygons are written as standard Polygon geometries.
 */
object GeofenceExchange {

    data class ImportedArea(
        val name: String,
        val lat: Double,
        val lng: Double,
        val radiusMetres: Int,
        val polygon: String?
    )

    private const val DEFAULT_RADIUS_M = 500

    // --- Export ---

    fun exportGeoJson(areas: List<GeofenceEntity>): String {
        val features = buildJsonArray {
            areas.forEach { area ->
                add(featureFor(area))
            }
        }
        return buildJsonObject {
            put("type", "FeatureCollection")
            put("features", features)
        }.toString()
    }

    private fun featureFor(area: GeofenceEntity): JsonObject {
        val ring = GeofencePolygon.decode(area.polygon)
        val geometry = if (ring != null) {
            buildJsonObject {
                put("type", "Polygon")
                put(
                    "coordinates",
                    buildJsonArray {
                        add(
                            buildJsonArray {
                                // GeoJSON rings are closed: repeat the first vertex.
                                (ring + ring.first()).forEach { (lat, lng) ->
                                    add(buildJsonArray {
                                        add(JsonPrimitive(lng))
                                        add(JsonPrimitive(lat))
                                    })
                                }
                            }
                        )
                    }
                )
            }
        } else {
            buildJsonObject {
                put("type", "Point")
                put(
                    "coordinates",
                    buildJsonArray {
                        add(JsonPrimitive(area.lng))
                        add(JsonPrimitive(area.lat))
                    }
                )
            }
        }
        return buildJsonObject {
            put("type", "Feature")
            put(
                "properties",
                buildJsonObject {
                    put("name", area.name)
                    if (ring == null) put("radiusMetres", area.radiusMetres)
                }
            )
            put("geometry", geometry)
        }
    }

    fun exportKml(areas: List<GeofenceEntity>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n")
        areas.forEach { area ->
            val ring = GeofencePolygon.decode(area.polygon)
            append("  <Placemark>\n")
            append("    <name>").append(escapeXml(area.name)).append("</name>\n")
            if (ring != null) {
                append("    <Polygon><outerBoundaryIs><LinearRing><coordinates>")
                (ring + ring.first()).forEach { (lat, lng) ->
                    append(lng).append(',').append(lat).append(",0 ")
                }
                append("</coordinates></LinearRing></outerBoundaryIs></Polygon>\n")
            } else {
                append("    <Point><coordinates>")
                append(area.lng).append(',').append(area.lat).append(",0")
                append("</coordinates></Point>\n")
                append("    <ExtendedData><Data name=\"radiusMetres\"><value>")
                append(area.radiusMetres)
                append("</value></Data></ExtendedData>\n")
            }
            append("  </Placemark>\n")
        }
        append("</Document>\n</kml>\n")
    }

    private fun escapeXml(value: String): String = buildString {
        value.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }

    // --- Import ---

    /**
     * Parses [text] as GeoJSON or KML, choosing by content so a mislabelled file still
     * works. Returns an empty list when the file holds no usable areas.
     */
    fun parse(text: String, fileName: String? = null): List<ImportedArea> {
        val trimmed = text.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (trimmed.isEmpty()) return emptyList()
        val fallbackBase = fileName?.substringBeforeLast('.')?.trim().orEmpty()
        return if (trimmed.startsWith("<")) {
            parseKml(trimmed, fallbackBase)
        } else {
            parseGeoJson(trimmed, fallbackBase)
        }
    }

    private fun parseGeoJson(text: String, fallbackBase: String): List<ImportedArea> {
        val root = Json.parseToJsonElement(text)
        val features = when (root.objectOrNull()?.string("type")) {
            "FeatureCollection" -> root.jsonObject["features"]?.jsonArray.orEmpty()
            "Feature" -> listOf(root)
            else -> emptyList()
        }
        return features.mapIndexedNotNull { index, feature ->
            val obj = feature.objectOrNull() ?: return@mapIndexedNotNull null
            val geometry = obj["geometry"]?.objectOrNull() ?: return@mapIndexedNotNull null
            val properties = obj["properties"]?.objectOrNull()
            val name = properties?.string("name")?.takeIf { it.isNotBlank() }
                ?: fallbackBase.takeIf { it.isNotBlank() }
                ?: "Area ${index + 1}"
            val radius = properties?.number("radiusMetres")
                ?: properties?.number("radius")
                ?: DEFAULT_RADIUS_M.toDouble()
            when (geometry.string("type")) {
                "Point" -> {
                    val coords = geometry["coordinates"]?.jsonArray ?: return@mapIndexedNotNull null
                    val lng = coords.getOrNull(0)?.numberOrNull() ?: return@mapIndexedNotNull null
                    val lat = coords.getOrNull(1)?.numberOrNull() ?: return@mapIndexedNotNull null
                    if (!validPoint(lat, lng)) return@mapIndexedNotNull null
                    ImportedArea(name, lat, lng, clampRadius(radius), null)
                }
                "Polygon" -> {
                    val rings = geometry["coordinates"]?.jsonArray ?: return@mapIndexedNotNull null
                    val ring = rings.firstOrNull()?.jsonArray
                    val points = ring?.mapNotNull { pair ->
                        val coordinates = pair as? JsonArray ?: return@mapNotNull null
                        val lng = coordinates.getOrNull(0)?.numberOrNull()
                        val lat = coordinates.getOrNull(1)?.numberOrNull()
                        if (lat != null && lng != null) lat to lng else null
                    }
                    areaForRing(name, points)
                }
                "MultiPolygon" -> {
                    val first = geometry["coordinates"]?.jsonArray?.firstOrNull()?.jsonArray
                        ?.firstOrNull()?.jsonArray
                    val points = first?.mapNotNull { pair ->
                        val coordinates = pair as? JsonArray ?: return@mapNotNull null
                        val lng = coordinates.getOrNull(0)?.numberOrNull()
                        val lat = coordinates.getOrNull(1)?.numberOrNull()
                        if (lat != null && lng != null) lat to lng else null
                    }
                    areaForRing(name, points)
                }
                else -> null
            }
        }
    }

    private fun parseKml(text: String, fallbackBase: String): List<ImportedArea> {
        val document = secureDocumentBuilderFactory().newDocumentBuilder()
            .parse(InputSource(StringReader(text)))
        val placemarks = document.getElementsByTagName("*").let { nodes ->
            (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
                .filter { it.localNameOrTag() == "Placemark" }
        }
        return placemarks.mapIndexedNotNull { index, placemark ->
            val name = placemark.child("name")?.textContent?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: fallbackBase.takeIf { it.isNotBlank() }
                ?: "Area ${index + 1}"
            val polygon = placemark.descendant("Polygon")
            val point = placemark.descendant("Point")
            when {
                polygon != null -> {
                    val coordinates = polygon.descendant("outerBoundaryIs")
                        ?.descendant("coordinates")?.textContent
                    areaForRing(name, parseCoordinates(coordinates))
                }
                point != null -> {
                    val coordinates = point.descendant("coordinates")?.textContent
                    val pair = parseCoordinates(coordinates)?.firstOrNull()
                        ?: return@mapIndexedNotNull null
                    val radius = placemark.descendants("Data")
                        .firstOrNull { it.getAttribute("name") in setOf("radiusMetres", "radius") }
                        ?.child("value")?.textContent?.toDoubleOrNull()
                        ?: DEFAULT_RADIUS_M.toDouble()
                    if (!validPoint(pair.first, pair.second)) return@mapIndexedNotNull null
                    ImportedArea(name, pair.first, pair.second, clampRadius(radius), null)
                }
                else -> null
            }
        }
    }

    private fun areaForRing(name: String, points: List<Pair<Double, Double>>?): ImportedArea? {
        val open = if (points != null && points.size >= 2 && points.first() == points.last()) {
            points.dropLast(1)
        } else {
            points.orEmpty()
        }
        if (open.size < 3 || open.size > GeofencePolygon.MAX_POINTS) return null
        if (open.any { !validPoint(it.first, it.second) }) return null
        if (!GeoMath.isRingSimple(open)) return null
        val (lat, lng) = GeoMath.ringCentroid(open)
        val radius = clampRadius(GeoMath.ringEffectiveRadiusMetres(open))
        return ImportedArea(name, lat, lng, radius, GeofencePolygon.encode(open))
    }

    /** KML coordinate tuples are `lng,lat[,alt]`, whitespace separated. */
    private fun parseCoordinates(raw: String?): List<Pair<Double, Double>>? {
        if (raw.isNullOrBlank()) return null
        val pairs = raw.trim().split(Regex("\\s+")).mapNotNull { tuple ->
            val parts = tuple.split(',')
            val lng = parts.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
            val lat = parts.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
            lat to lng
        }
        return pairs.takeIf { it.isNotEmpty() }
    }

    private fun secureDocumentBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setXIncludeAware(false) }
            runCatching { isExpandEntityReferences = false }
        }

    private fun validPoint(lat: Double, lng: Double): Boolean = GeoMath.isValidCoordinate(lat, lng)

    private fun clampRadius(metres: Double): Int = metres.roundToInt().coerceIn(50, 50000)

    // --- Small JSON/DOM helpers ---

    private fun JsonElement.objectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.number(key: String): Double? =
        (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()

    private fun JsonElement.numberOrNull(): Double? =
        (this as? JsonPrimitive)?.content?.toDoubleOrNull()

    private fun Element.localNameOrTag(): String = localName ?: tagName.substringAfter(':')

    private fun Element.child(tag: String): Element? {
        val children = childNodes
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            if (child.localNameOrTag() == tag) return child
        }
        return null
    }

    private fun Element.descendant(tag: String): Element? = descendants(tag).firstOrNull()

    private fun Element.descendants(tag: String): List<Element> {
        val found = ArrayList<Element>()
        val nodes = getElementsByTagName("*")
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as? Element ?: continue
            if (element.localNameOrTag() == tag) found += element
        }
        return found
    }
}

/** Empty JSON array when the node is absent or not an array, so callers can iterate freely. */
private fun JsonElement?.jsonArray(): JsonArray? = (this as? JsonArray)
