package nl.r1a.grassmap.map

import nl.r1a.grassmap.plugin.api.model.SourceId
import nl.r1a.grassmap.plugin.api.model.StyleSpec

/**
 * The tiles of one logical map source, supplied by the app instead of loaded
 * from a URL. Implementations are called from the engine's tile threads, so
 * they must be safe to call concurrently, and they may be cancelled.
 */
fun interface MapTileSource {
    /** The uncompressed MVT bytes of one tile; an empty array is an empty tile, null a failed one. */
    fun tile(
        zoom: Int,
        x: Long,
        y: Long,
    ): ByteArray?
}

/**
 * A declared style source the app supplies tiles for, with the style's zoom
 * range. Two plans are equal only when the tile source is the same object, so
 * a new source reloads the style and an unchanged one does not.
 */
internal data class AppSource(
    val id: SourceId,
    val minZoom: Int,
    val maxZoom: Int,
    val tiles: MapTileSource,
)

/**
 * How every source the style declares is satisfied: by a URI the engine loads,
 * or by tiles the app supplies (T-0503).
 *
 * A source the app supplies never also gets a URI. That is the one rule that
 * keeps a shared tile from being loaded twice under two source ids.
 */
internal data class SourcePlan(
    val uris: Map<SourceId, String>,
    val app: List<AppSource>,
)

/**
 * Splits [spec]'s declared sources into [SourcePlan.uris] and [SourcePlan.app].
 * A declared source that has neither a URI nor app tiles is left out, so the
 * style may declare a source this build has no data for.
 */
internal fun planSources(
    spec: StyleSpec,
    resolver: (SourceId) -> String?,
    appTiles: Map<SourceId, MapTileSource>,
): SourcePlan {
    val uris = LinkedHashMap<SourceId, String>()
    val app = mutableListOf<AppSource>()
    for (source in spec.sources) {
        val tiles = appTiles[source.id]
        if (tiles != null) {
            app += AppSource(source.id, source.minZoom, source.maxZoom, tiles)
            continue
        }
        resolver(source.id)?.let { uris[source.id] = it }
    }
    return SourcePlan(uris, app)
}
