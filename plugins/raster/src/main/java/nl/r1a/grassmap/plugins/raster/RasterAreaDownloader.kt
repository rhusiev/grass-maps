package nl.r1a.grassmaps.plugins.raster

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import nl.r1a.grassmaps.plugin.api.contract.CancellationHandle
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.pmtiles.PmtilesSummary
import nl.r1a.grassmaps.plugin.api.pmtiles.RasterPackBuilder
import nl.r1a.grassmaps.plugin.api.pmtiles.RasterPackInfo
import java.io.File

/** How far an area download has come. */
data class RasterAreaProgress(
    val done: Long,
    val total: Long,
)

/** A finished download: the staged package is ready for the region store's installer. */
data class RasterAreaStaged(
    val areaId: String,
    val version: String,
    val directory: File,
    val summary: PmtilesSummary,
)

/**
 * Downloads one planned area into a staging directory as a `raster.pmtiles`
 * package (T-0513).
 *
 * Every tile goes through [RasterTileClient], so the online-tiles switch
 * guards the download exactly as it guards the viewer: with the switch off no
 * socket opens. Requests run one at a time, [spacingMillis] apart, as EOX asks
 * ("spread out your requests rather than sending large batches"). The first
 * failure that is not a missing tile ends the download. The real bytes are
 * counted: the download stops at [maxBytes], or when the free space no longer
 * covers the rest of the download plus the assembly of `raster.pmtiles`, which
 * needs about the size of the finished pack again. Nothing is installed
 * here: the caller installs the staged directory through the region store, and
 * deletes it when this fails.
 */
class RasterAreaDownloader(
    private val client: RasterTileClient,
    private val spacingMillis: Long = REQUEST_SPACING_MS,
    private val retryDelayMillis: Long = RETRY_DELAY_MS,
    private val maxBytes: Long = RasterAreaPlan.MAX_BYTES,
    private val freeBytes: () -> Long? = { null },
    private val clock: () -> String,
) {
    @Suppress("LongParameterList")
    suspend fun download(
        plan: RasterAreaPlan,
        areaId: String,
        version: String,
        name: String,
        staging: File,
        cancellation: CancellationHandle = CancellationHandle.None,
        onProgress: (RasterAreaProgress) -> Unit = {},
    ): Outcome<RasterAreaStaged> {
        val refused = RasterOfflineDownload.prepare(plan.source)
        if (refused is Outcome.Failure) {
            return refused
        }
        val licence = plan.source.licence
        if (plan.refusal != null || licence == null) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        val info = infoOf(plan.source, licence, name)
        return RasterPackBuilder(staging, areaId, version, clock()).use { builder ->
            when (val fetched = fetchAll(plan, builder, cancellation, onProgress)) {
                is Outcome.Failure -> {
                    fetched
                }

                is Outcome.Success -> {
                    when (val done = builder.finish(fetched.value, plan.bounds, info)) {
                        is Outcome.Failure -> done
                        is Outcome.Success -> Outcome.Success(RasterAreaStaged(areaId, version, staging, done.value))
                    }
                }
            }
        }
    }

    private suspend fun fetchAll(
        plan: RasterAreaPlan,
        builder: RasterPackBuilder,
        cancellation: CancellationHandle,
        onProgress: (RasterAreaProgress) -> Unit,
    ): Outcome<Int> {
        val total = plan.tileCount
        val budget = ByteBudget(total, maxBytes, freeBytes)
        var done = 0L
        var tileType: Int? = null
        for (range in plan.ranges) {
            for (y in range.minY..range.maxY) {
                for (x in range.minX..range.maxX) {
                    currentCoroutineContext().ensureActive()
                    if (cancellation.isCancelled()) {
                        return Outcome.Failure(AppError.Cancelled)
                    }
                    val tile = fetchTile(plan.source, range.zoom, x, y)
                    if (tile is Outcome.Failure) {
                        return tile
                    }
                    val image = (tile as Outcome.Success).value
                    if (image != null) {
                        val type = tileTypeOf(image.contentType) ?: return Outcome.Failure(AppError.CorruptData)
                        if (tileType != null && tileType != type) {
                            return Outcome.Failure(AppError.CorruptData)
                        }
                        tileType = type
                        val added = builder.addTile(range.zoom, x, y, image.bytes)
                        if (added is Outcome.Failure) {
                            return added
                        }
                        budget.add(image.bytes.size)
                    }
                    done += 1
                    budget.verdict(done)?.let { return Outcome.Failure(AppError.Internal(it)) }
                    onProgress(RasterAreaProgress(done, total))
                    delay(spacingMillis)
                }
            }
        }
        return tileType?.let { Outcome.Success(it) } ?: Outcome.Failure(AppError.NotFound)
    }

    /**
     * One tile. A tile the server does not have is null. A network error or a
     * 5xx is retried once after [retryDelayMillis]. A 429 waits for the
     * server's `Retry-After` and tries again, at most [MAX_RATE_LIMIT_WAITS]
     * times for one tile. Any other failure is returned.
     */
    private suspend fun fetchTile(
        source: RasterSource,
        z: Int,
        x: Long,
        y: Long,
    ): Outcome<TileImage?> {
        var retries = 0
        var rateLimitWaits = 0
        while (true) {
            val result = client.fetch(source, z, x.toInt(), y.toInt())
            val error = (result as? Outcome.Failure)?.error ?: return Outcome.Success((result as Outcome.Success).value)
            when {
                error.isMissingTile() -> {
                    return Outcome.Success(null)
                }

                error is AppError.Server && error.status == TOO_MANY_REQUESTS && rateLimitWaits < MAX_RATE_LIMIT_WAITS -> {
                    rateLimitWaits += 1
                    delay(backoffMillis(error.retryAfter))
                }

                isWorthRetrying(error) && retries < MAX_RETRIES -> {
                    retries += 1
                    delay(retryDelayMillis)
                }

                else -> {
                    return Outcome.Failure(error)
                }
            }
        }
    }

    private fun backoffMillis(retryAfterSeconds: Long?): Long =
        ((retryAfterSeconds ?: DEFAULT_BACKOFF_S).coerceIn(0, MAX_BACKOFF_S)) * MILLIS_PER_SECOND

    private fun isWorthRetrying(error: AppError): Boolean =
        error == AppError.Network || (error is AppError.Server && error.status >= SERVER_ERROR_FROM)

    private fun AppError.isMissingTile(): Boolean = this is AppError.Server && status == NOT_FOUND

    private fun tileTypeOf(contentType: String): Int? =
        when (contentType.substringBefore(';').trim().lowercase()) {
            "image/jpeg" -> RasterPackBuilder.TILE_TYPE_JPEG
            "image/png" -> RasterPackBuilder.TILE_TYPE_PNG
            "image/webp" -> RasterPackBuilder.TILE_TYPE_WEBP
            else -> null
        }

    private fun infoOf(
        source: RasterSource,
        licence: RasterLicence,
        name: String,
    ): RasterPackInfo =
        RasterPackInfo(
            sourceId = source.id,
            name = name,
            attribution = source.attributionHtml,
            licence = licence.name,
            licenceUrl = licence.url,
            isNonCommercial = licence.isNonCommercial,
        )

    companion object {
        /** Chosen, not derived: 10 requests per second, well below any rate EOX documents as a problem. */
        const val REQUEST_SPACING_MS = 100L

        /** Chosen: a failed tile waits this long before its one retry. */
        const val RETRY_DELAY_MS = 1_000L

        /** `Retry-After` is honoured up to this wait, in seconds. Chosen, not derived. */
        const val MAX_BACKOFF_S = 120L
        private const val DEFAULT_BACKOFF_S = 10L
        private const val MAX_RATE_LIMIT_WAITS = 5
        private const val MAX_RETRIES = 1
        private const val MILLIS_PER_SECOND = 1_000L
        private const val TOO_MANY_REQUESTS = 429

        /** The [AppError.Internal] ids of a download that outgrew its cap or its disk. */
        const val TOO_LARGE_ID = "raster-area-too-large"
        const val NO_SPACE_ID = "raster-area-no-space"
        private const val NOT_FOUND = 404
        private const val SERVER_ERROR_FROM = 500
    }
}

/**
 * The real bytes of one download. The estimate decides whether a download may
 * start. This decides whether it may go on.
 */
private class ByteBudget(
    private val totalTiles: Long,
    private val maxBytes: Long,
    private val freeBytes: () -> Long?,
) {
    private var written = 0L
    private var tiles = 0L

    fun add(bytes: Int) {
        written += bytes
        tiles += 1
    }

    /**
     * The failure id when the download must stop after [done] tiles, or null.
     * The rest of the download needs the bytes the remaining tiles will take.
     * The assembly then needs the finished pack's size again.
     */
    fun verdict(done: Long): String? {
        if (written > maxBytes) {
            return RasterAreaDownloader.TOO_LARGE_ID
        }
        val free = freeBytes() ?: return null
        val remaining = if (tiles == 0L) 0L else written / tiles * (totalTiles - done)
        return if (free < written + 2 * remaining) RasterAreaDownloader.NO_SPACE_ID else null
    }
}
