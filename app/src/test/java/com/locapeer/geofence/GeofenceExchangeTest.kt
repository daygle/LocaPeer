package com.locapeer.geofence

import com.locapeer.data.entity.GeofenceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeofenceExchangeTest {

    private val circle = GeofenceEntity(
        id = "circle",
        name = "Home & Work",
        lat = 51.5,
        lng = -0.12,
        radiusMetres = 250
    )

    private val polygon = GeofenceEntity(
        id = "polygon",
        name = "Park",
        lat = 0.005,
        lng = 0.005,
        radiusMetres = 628,
        polygon = "0.0,0.0;0.0,0.01;0.01,0.01;0.01,0.0"
    )

    @Test
    fun `geojson round-trips circles and polygons`() {
        val parsed = GeofenceExchange.parse(
            GeofenceExchange.exportGeoJson(listOf(circle, polygon))
        )

        assertEquals(2, parsed.size)
        val exportedCircle = parsed[0]
        assertEquals("Home & Work", exportedCircle.name)
        assertEquals(51.5, exportedCircle.lat, 1e-9)
        assertEquals(-0.12, exportedCircle.lng, 1e-9)
        assertEquals(250, exportedCircle.radiusMetres)
        assertNull(exportedCircle.polygon)

        val exportedPolygon = parsed[1]
        assertEquals("Park", exportedPolygon.name)
        assertEquals(polygon.polygon, exportedPolygon.polygon)
    }

    @Test
    fun `kml round-trips circles and polygons with escaped names`() {
        val parsed = GeofenceExchange.parse(
            GeofenceExchange.exportKml(listOf(circle, polygon)),
            fileName = "areas.kml"
        )

        assertEquals(2, parsed.size)
        assertEquals("Home & Work", parsed[0].name)
        assertEquals(51.5, parsed[0].lat, 1e-9)
        assertEquals(250, parsed[0].radiusMetres)
        assertEquals(polygon.polygon, parsed[1].polygon)
    }

    @Test
    fun `geojson import accepts a closed polygon ring`() {
        val json = """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "properties": { "name": "Closed" },
                  "geometry": {
                    "type": "Polygon",
                    "coordinates": [[
                      [0.0, 0.0], [0.01, 0.0], [0.01, 0.01], [0.0, 0.01], [0.0, 0.0]
                    ]]
                  }
                }
              ]
            }
        """.trimIndent()

        val parsed = GeofenceExchange.parse(json)
        assertEquals(1, parsed.size)
        assertEquals("Closed", parsed[0].name)
        assertEquals("0.0,0.0;0.0,0.01;0.01,0.01;0.01,0.0", parsed[0].polygon)
    }

    @Test
    fun `kml import reads a point radius from extended data`() {
        val kml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <kml xmlns="http://www.opengis.net/kml/2.2">
              <Document>
                <Placemark>
                  <name>Office</name>
                  <Point><coordinates>-0.12,51.5,0</coordinates></Point>
                  <ExtendedData>
                    <Data name="radiusMetres"><value>300</value></Data>
                  </ExtendedData>
                </Placemark>
              </Document>
            </kml>
        """.trimIndent()

        val parsed = GeofenceExchange.parse(kml)
        assertEquals(1, parsed.size)
        assertEquals("Office", parsed[0].name)
        assertEquals(51.5, parsed[0].lat, 1e-9)
        assertEquals(-0.12, parsed[0].lng, 1e-9)
        assertEquals(300, parsed[0].radiusMetres)
    }

    @Test
    fun `invalid rings are skipped rather than imported`() {
        val bowTie = """
            {"type":"Feature","properties":{"name":"Bow tie"},"geometry":{
              "type":"Polygon","coordinates":[[[0,0],[1,1],[1,0],[0,1],[0,0]]]}}
        """.trimIndent()

        assertTrue(GeofenceExchange.parse(bowTie).isEmpty())
    }

    @Test
    fun `empty input and empty collections import nothing`() {
        assertTrue(GeofenceExchange.parse("").isEmpty())
        assertTrue(
            GeofenceExchange.parse("""{"type":"FeatureCollection","features":[]}""").isEmpty()
        )
    }
}
