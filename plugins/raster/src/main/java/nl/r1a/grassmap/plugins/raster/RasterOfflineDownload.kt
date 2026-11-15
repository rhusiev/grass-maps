package nl.r1a.grassmap.plugins.raster

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome

/**
 * The offline bulk-download decision for a source (data-formats.md section 8).
 *
 * The OSM preset is view-only: its policy says "Offline use is not permitted
 * on tile.openstreetmap.org". The same check is the seam T-0513 will call
 * before an area download, so the preset cannot grow a download path silently.
 */
object RasterOfflineDownload {
    fun prepare(source: RasterSource): Outcome<Unit> =
        when (source.offlineDownload) {
            OfflineTileDownload.ALLOWED -> Outcome.Success(Unit)
            OfflineTileDownload.REFUSED_BY_POLICY -> Outcome.Failure(AppError.Unsupported)
        }
}
