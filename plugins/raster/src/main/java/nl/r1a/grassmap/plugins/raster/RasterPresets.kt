package nl.r1a.grassmap.plugins.raster

/**
 * The tile sources the app ships (data-formats.md section 8).
 *
 * OSM's own tiles are the one online preset. Its policy forbids offline bulk
 * download, so the preset is for viewing only; any other source is user-added
 * and is the user's responsibility.
 */
object RasterPresets {
    const val OPEN_STREET_MAP_ID = "osm"

    const val OPEN_STREET_MAP_ATTRIBUTION_HTML =
        "© <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors"

    val openStreetMap: RasterSource =
        RasterSource(
            id = OPEN_STREET_MAP_ID,
            displayName = "OpenStreetMap",
            tileUrlTemplate = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
            attributionHtml = OPEN_STREET_MAP_ATTRIBUTION_HTML,
            minZoom = 0,
            maxZoom = 19,
            tileSize = 256,
            offlineDownload = OfflineTileDownload.REFUSED_BY_POLICY,
        )

    /** The largest zoom the app asks any source for. */
    const val MAX_SUPPORTED_ZOOM = 24
}
