package com.locapeer.map

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex

object MapTileSources {
    // Voyager (rather than the near-white Positron basemap) for the light style: it keeps the
    // clean look while giving roads, water and labels real colour and contrast, so streets and
    // addresses stay legible instead of washing out.
    val CARTO_LIGHT = object : OnlineTileSourceBase(
        "CartoDB_Voyager", 0, 20, 256, ".png",
        arrayOf(
            "https://a.basemaps.cartocdn.com/rastertiles/voyager/",
            "https://b.basemaps.cartocdn.com/rastertiles/voyager/",
            "https://c.basemaps.cartocdn.com/rastertiles/voyager/"
        ), "© CartoDB © OpenStreetMap contributors"
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
