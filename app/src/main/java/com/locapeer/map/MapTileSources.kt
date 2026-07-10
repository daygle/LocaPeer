package com.locapeer.map

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex

object MapTileSources {
    val CARTO_LIGHT = object : OnlineTileSourceBase(
        "CartoDB_Positron", 0, 19, 256, ".png",
        arrayOf(
            "https://a.basemaps.cartocdn.com/light_all/",
            "https://b.basemaps.cartocdn.com/light_all/",
            "https://c.basemaps.cartocdn.com/light_all/"
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
