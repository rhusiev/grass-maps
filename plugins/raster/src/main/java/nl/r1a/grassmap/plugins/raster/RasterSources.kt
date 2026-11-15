package nl.r1a.grassmap.plugins.raster

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome

/** Builds the user-added tile source that a URL template describes. */
object RasterSources {
    const val CUSTOM_ID = "custom"

    private const val OSM_TILE_HOST = "tile.openstreetmap.org"

    /**
     * Validates [template] and returns the custom source it describes.
     *
     * [attributionHtml] is what the user says the source requires. It may be
     * empty: the app cannot invent a licence for a server it does not know.
     */
    fun custom(
        template: String,
        attributionHtml: String = "",
        minZoom: Int = 0,
        maxZoom: Int = RasterPresets.MAX_SUPPORTED_ZOOM,
    ): Outcome<RasterSource> {
        val problem = RasterTileTemplate.problem(template)
        if (problem != null) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        if (minZoom !in 0..maxZoom || maxZoom > RasterPresets.MAX_SUPPORTED_ZOOM) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        // The OSM policy applies to its server, not to the preset id: a user
        // who pastes the same host as a custom source still cannot download it.
        val offline =
            if (RasterTileTemplate.host(template) == OSM_TILE_HOST) {
                OfflineTileDownload.REFUSED_BY_POLICY
            } else {
                OfflineTileDownload.ALLOWED
            }
        return Outcome.Success(
            RasterSource(
                id = CUSTOM_ID,
                displayName = "Custom source",
                tileUrlTemplate = template,
                attributionHtml = attributionHtml,
                minZoom = minZoom,
                maxZoom = maxZoom,
                tileSize = 256,
                offlineDownload = offline,
            ),
        )
    }
}
