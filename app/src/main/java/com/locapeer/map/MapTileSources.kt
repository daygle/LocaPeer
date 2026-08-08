package com.locapeer.map

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.util.MapTileIndex

object MapTileSources {
    // Use the current HTTPS OSM endpoint explicitly. The built-in MAPNIK source normalizes the
    // user-agent to only "com.locapeer/39"; OSM's tile policy expects an identifiable app name,
    // version, and project URL, and can reject the normalized request with blank tile squares.
    val LIGHT = object : OnlineTileSourceBase(
        "OpenStreetMap", 0, 19, 256, ".png",
        arrayOf("https://tile.openstreetmap.org/"),
        "© OpenStreetMap contributors",
        TileSourcePolicy(
            2,
            TileSourcePolicy.FLAG_NO_BULK or
                TileSourcePolicy.FLAG_NO_PREVENTIVE or
                TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL
        )
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String =
            "$baseUrl${MapTileIndex.getZoom(pMapTileIndex)}/" +
                "${MapTileIndex.getX(pMapTileIndex)}/" +
                "${MapTileIndex.getY(pMapTileIndex)}$mImageFilenameEnding"
    }

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
