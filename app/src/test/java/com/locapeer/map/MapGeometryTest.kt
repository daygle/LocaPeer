package com.locapeer.map

import com.locapeer.util.GeoMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapGeometryTest {
    @Test fun `circle points all lie on the requested radius and the ring is closed`() {
        val ring = MapGeometry.circle(-33.87, 151.21, 500.0)
        assertEquals(65, ring.size)
        assertEquals(ring.first(), ring.last())
        ring.forEach { (lat, lng) ->
            assertEquals(500.0, GeoMath.haversineMetres(-33.87, 151.21, lat, lng), 1.0)
        }
    }

    @Test fun `circle crossing the antimeridian keeps longitudes in range`() {
        MapGeometry.circle(0.0, 179.999, 5_000.0).forEach { (_, lng) -> assertTrue(lng in -180.0..180.0) }
    }

    @Test fun `legacy osmdroid zoom maps one level lower and back`() {
        assertEquals(15.0, MapGeometry.fromLegacyZoom(16.0), 0.0)
        assertEquals(16.0, MapGeometry.toLegacyZoom(MapGeometry.fromLegacyZoom(16.0)), 0.0)
    }

    @Test fun `metres per dp halves with each zoom level`() {
        val z10 = MapGeometry.metresPerDp(0.0, 10.0)
        assertEquals(z10 / 2, MapGeometry.metresPerDp(0.0, 11.0), 1e-9)
        // At zoom 0 the 512 dp world spans the equator.
        assertEquals(40_075_016.686 / 512, MapGeometry.metresPerDp(0.0, 0.0), 1e-6)
    }

    @Test fun `scale bar picks the largest 1-2-5 distance that fits`() {
        assertEquals(500.0, MapGeometry.niceScaleMetres(730.0), 0.0)
        assertEquals(2_000.0, MapGeometry.niceScaleMetres(4_999.0), 0.0)
        assertEquals(1.0, MapGeometry.niceScaleMetres(1.9), 0.0)
        assertEquals(0.0, MapGeometry.niceScaleMetres(0.0), 0.0)
    }

    @Test fun `scale bar labels use the chosen unit and roll up at a kilometre or mile`() {
        assertEquals(200.0 to "200 m", MapGeometry.scaleBar(350.0, imperial = false))
        assertEquals(2_000.0 to "2 km", MapGeometry.scaleBar(3_100.0, imperial = false))
        val (feetMetres, feetLabel) = MapGeometry.scaleBar(100.0, imperial = true)
        assertEquals("200 ft", feetLabel)
        assertEquals(200 / 3.28084, feetMetres, 1e-9)
        assertEquals("1 mi", MapGeometry.scaleBar(2_000.0, imperial = true).second)
    }
}
