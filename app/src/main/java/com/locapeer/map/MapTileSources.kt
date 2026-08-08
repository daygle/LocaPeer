package com.locapeer.map

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.MapTileIndex

object MapTileSources {
    // Use osmdroid's built-in MAPNIK source for the light map. It is already used by the
    // geofence picker and avoids relying on the public Carto Voyager endpoint, which can return
    // grey placeholder tiles for some networks while the rest of the map still renders.
    val LIGHT = TileSourceFactory.MAPNIK

    val CARTO_DARK = object : OnlineTileSourceBase(
        "CartoDB_DarkMatter", 0, 19, 256, ".png",
        arrayOf(
            "https://a.basemaps.cartocdn.com/dark_all/",
            "https://b.basemaps.cartocdn.com/dark_all/",
            "https://c.basemaps.cartocdn.com/dark_all/"
        ), "© CartoDB © OpenStreetMap contributors"
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String =
            "$baseUrl${MapTileIndex.getZoom(pMapTileIndex)}/" +
                    "${MapTileIndex.getX(pMapTileIndex)}/" +
                    "${MapTileIndex.getY(pMapTileIndex)}$mImageFilenameEnding"
    }
}
