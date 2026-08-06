package com.carlauncher.companion.ui.map

import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.tileprovider.tilesource.XYTileSource

/**
 * CARTO's free "Dark Matter" basemap, built from OpenStreetMap data. No API key required.
 * Requests retina (`@2x`) tiles — CARTO's URL template supports an optional `@2x` scale suffix
 * for double-resolution tiles — so road/label text stays sharp on high-density screens instead
 * of being upscaled from 256px tiles. MapScreen also applies a contrast/brightness filter on top,
 * since dark_all's baked-in label color is otherwise too subdued to read easily.
 * https://github.com/CartoDB/basemap-styles
 */
val CartoDarkMatterTileSource = XYTileSource(
    "CartoDBDarkMatter",
    0,
    20,
    512,
    "@2x.png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/dark_all/",
        "https://b.basemaps.cartocdn.com/dark_all/",
        "https://c.basemaps.cartocdn.com/dark_all/",
        "https://d.basemaps.cartocdn.com/dark_all/",
    ),
    "© OpenStreetMap contributors © CARTO",
    TileSourcePolicy(
        4,
        TileSourcePolicy.FLAG_NO_BULK or
            TileSourcePolicy.FLAG_NO_PREVENTIVE or
            TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL or
            TileSourcePolicy.FLAG_USER_AGENT_NORMALIZED,
    ),
)
