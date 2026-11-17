package nl.r1a.grassmap.plugins.raster

import nl.r1a.grassmap.plugin.api.model.BoundingBox
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** The tile columns and rows that cover a bounding box at one zoom. */
data class RasterTileRange(
    val zoom: Int,
    val minX: Long,
    val maxX: Long,
    val minY: Long,
    val maxY: Long,
) {
    val count: Long get() = (maxX - minX + 1) * (maxY - minY + 1)

    companion object {
        /** The range covering [bounds] at [zoom]. Web Mercator limits the latitude. */
        fun of(
            bounds: BoundingBox,
            zoom: Int,
        ): RasterTileRange {
            val axis = 1L shl zoom
            val west = column(bounds.southWest.longitude, axis)
            val east = column(bounds.northEast.longitude, axis)
            val north = row(bounds.northEast.latitude, axis)
            val south = row(bounds.southWest.latitude, axis)
            return RasterTileRange(zoom, minOf(west, east), maxOf(west, east), minOf(north, south), maxOf(north, south))
        }

        private fun column(
            longitude: Double,
            axis: Long,
        ): Long = floor((longitude + HALF_TURN_DEGREES) / FULL_TURN_DEGREES * axis).toLong().coerceIn(0, axis - 1)

        private fun row(
            latitude: Double,
            axis: Long,
        ): Long {
            val radians = latitude.coerceIn(-MAX_MERCATOR_LATITUDE, MAX_MERCATOR_LATITUDE) * PI / HALF_TURN_DEGREES
            val mercator = ln(tan(PI / 4 + radians / 2))
            return floor((1 - mercator / PI) / 2 * axis).toLong().coerceIn(0, axis - 1)
        }

        private const val HALF_TURN_DEGREES = 180.0
        private const val FULL_TURN_DEGREES = 360.0
        private const val MAX_MERCATOR_LATITUDE = 85.0511
    }
}

/**
 * The size of an area download, known before it starts (T-0513).
 *
 * [bytes] is an estimate: the source's typical tile size times the tile count.
 * The cap is hard: a plan over [RasterAreaPlan.MAX_TILES] or
 * [RasterAreaPlan.MAX_BYTES] never downloads, and the sheet says which limit
 * stopped it.
 */
data class RasterAreaPlan(
    val source: RasterSource,
    val bounds: BoundingBox,
    val minZoom: Int,
    val maxZoom: Int,
    val ranges: List<RasterTileRange>,
    val estimatedBytes: Long,
) {
    val tileCount: Long get() = ranges.sumOf { it.count }

    /** Why this plan must not run, or null when it may. */
    val refusal: Refusal? get() = refusalFor(freeBytes = null)

    /**
     * Like [refusal], and also refuses an area that cannot fit in [freeBytes].
     * The download holds the tiles twice while it assembles the pack, so the
     * space check is [SPACE_FACTOR] times the estimate.
     */
    fun refusalFor(freeBytes: Long?): Refusal? =
        when {
            freeBytes != null && estimatedBytes > freeBytes / SPACE_FACTOR -> Refusal.NotEnoughSpace
            else -> staticRefusal
        }

    private val staticRefusal: Refusal?
        get() =
            when {
                source.offlineDownload != OfflineTileDownload.ALLOWED -> Refusal.SourceForbidsOffline
                tileCount > MAX_TILES -> Refusal.TooManyTiles
                estimatedBytes > MAX_BYTES -> Refusal.TooManyBytes
                else -> null
            }

    enum class Refusal {
        SourceForbidsOffline,
        TooManyTiles,
        TooManyBytes,
        NotEnoughSpace,
    }

    companion object {
        /** Chosen, not derived: 20,000 tiles at EOX's 25 KB is about 500 MB, and at 1 request per 100 ms about 33 minutes. */
        const val MAX_TILES = 20_000L

        /** Chosen, not derived: a hard bytes ceiling for a source whose tiles are larger than the preset's. */
        const val MAX_BYTES = 600L * 1024 * 1024

        /** The staging copy and the finished pack exist together for a moment. */
        const val SPACE_FACTOR = 2

        const val DEFAULT_TILE_BYTES = 30L * 1024

        /** The plan for [source] over [bounds] and the zooms [minZoom]..[maxZoom], clamped to the source's range. */
        fun of(
            source: RasterSource,
            bounds: BoundingBox,
            minZoom: Int,
            maxZoom: Int,
        ): RasterAreaPlan {
            val low = minZoom.coerceIn(source.minZoom, source.maxZoom)
            val high = maxZoom.coerceIn(low, source.maxZoom)
            val ranges = (low..high).map { RasterTileRange.of(bounds, it) }
            val tiles = ranges.sumOf { it.count }
            val perTile = source.estimatedTileBytes
            val bytes = if (tiles > Long.MAX_VALUE / perTile) Long.MAX_VALUE else tiles * perTile
            return RasterAreaPlan(source, bounds, low, high, ranges, bytes)
        }
    }
}
