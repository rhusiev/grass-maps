package nl.r1a.grassmaps.plugins.raster

import nl.r1a.grassmaps.plugin.api.model.MapCredits

/**
 * The tile sources the app ships (data-formats.md section 8).
 *
 * OSM's own tiles are the one online preset. Its policy forbids offline bulk
 * download, so the preset is for viewing only; any other source is user-added
 * and is the user's responsibility.
 */
object RasterPresets {
    const val OPEN_STREET_MAP_ID = "osm"

    const val OPEN_STREET_MAP_ATTRIBUTION_HTML = MapCredits.OPENSTREETMAP

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

    const val EOX_2016_ID = "eox-s2-2016"
    const val EOX_2018_ID = "eox-s2-2018"
    const val EOX_MAX_ZOOM = 14
    private const val EOX_TILE_BYTES = 25L * 1024
    private const val EOX_URL = "https://tiles.maps.eox.at/wmts/1.0.0"

    /** Sentinel-2 cloudless 2016, CC BY 4.0: free to cache on the device (research-data.md 1.7). */
    val eox2016: RasterSource =
        RasterSource(
            id = EOX_2016_ID,
            displayName = "Sentinel-2 cloudless 2016",
            tileUrlTemplate = "$EOX_URL/s2cloudless-2016_3857/default/g/{z}/{y}/{x}.jpg",
            attributionHtml = MapCredits.EOX_2016,
            minZoom = 0,
            maxZoom = EOX_MAX_ZOOM,
            tileSize = 256,
            offlineDownload = OfflineTileDownload.ALLOWED,
            licence = RasterLicence("CC BY 4.0", "https://creativecommons.org/licenses/by/4.0/", false),
            estimatedTileBytes = EOX_TILE_BYTES,
        )

    /**
     * Sentinel-2 cloudless 2018, CC BY-NC-SA 4.0: personal, non-commercial use
     * only. The user decided it stays online-only, so offline download is
     * refused here. Setting [OfflineTileDownload.ALLOWED] turns it back on.
     */
    val eox2018: RasterSource =
        RasterSource(
            id = EOX_2018_ID,
            displayName = "Sentinel-2 cloudless 2018",
            tileUrlTemplate = "$EOX_URL/s2cloudless-2018_3857/default/g/{z}/{y}/{x}.jpg",
            attributionHtml = MapCredits.EOX_2018,
            minZoom = 0,
            maxZoom = EOX_MAX_ZOOM,
            tileSize = 256,
            offlineDownload = OfflineTileDownload.REFUSED_BY_POLICY,
            licence =
                RasterLicence(
                    "CC BY-NC-SA 4.0",
                    "https://creativecommons.org/licenses/by-nc-sa/4.0/",
                    true,
                ),
            estimatedTileBytes = EOX_TILE_BYTES,
        )

    /** Every built-in source, the OSM preset first. */
    val all: List<RasterSource> = listOf(openStreetMap, eox2016, eox2018)

    /** The presets an area can be downloaded from: those whose terms allow it. */
    val areaSources: List<RasterSource> = all.filter { it.offlineDownload == OfflineTileDownload.ALLOWED }

    /** The preset with [id], or null. */
    fun byId(id: String): RasterSource? = all.firstOrNull { it.id == id }

    /** The largest zoom the app asks any source for. */
    const val MAX_SUPPORTED_ZOOM = 24
}
