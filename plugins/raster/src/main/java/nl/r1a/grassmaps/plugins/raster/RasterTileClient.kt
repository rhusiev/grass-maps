package nl.r1a.grassmaps.plugins.raster

import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.service.NetworkGate
import nl.r1a.grassmaps.plugin.api.service.NetworkSwitches
import nl.r1a.grassmaps.plugin.api.service.NoNetworkGate

/**
 * One tile request behind the online-tiles privacy switch (T-0512).
 *
 * The gate refuses before [RasterTileTransport] runs, so a switch that is off
 * opens no socket. The client fetches single tiles only: no retry, no
 * prefetch, no cache. A custom source is probed once when the user adds it,
 * which is also a single tile request.
 */
class RasterTileClient(
    private val gate: NetworkGate = NoNetworkGate,
    private val transport: RasterTileTransport = HttpRasterTileTransport(),
    private val userAgent: String = USER_AGENT,
) {
    suspend fun fetch(
        source: RasterSource,
        z: Int,
        x: Int,
        y: Int,
    ): Outcome<TileImage> {
        val url = source.tileUrl(z, x, y) ?: return Outcome.Failure(AppError.InvalidInput)
        return gate.request(NetworkSwitches.onlineTiles) {
            transport.get(url, userAgent)
        }
    }

    /** Fetches the source's lowest-zoom origin tile as a reachability check. */
    suspend fun probe(source: RasterSource): Outcome<TileImage> = fetch(source, source.minZoom, 0, 0)

    companion object {
        const val USER_AGENT = "GrassMaps (Android; online raster tiles; +https://github.com/rhusiev/grass-maps)"
    }
}
