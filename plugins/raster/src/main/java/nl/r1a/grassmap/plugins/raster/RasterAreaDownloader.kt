package nl.r1a.grassmap.plugins.raster

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.pmtiles.PmtilesSummary
import nl.r1a.grassmap.plugin.api.pmtiles.RasterPackBuilder
import nl.r1a.grassmap.plugin.api.pmtiles.RasterPackInfo
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
 * failure that is not a missing tile ends the download. Nothing is installed
 * here: the caller installs the staged directory through the region store, and
 * deletes it when this fails.
 */
class RasterAreaDownloader(
    private val client: RasterTileClient,
    private val spacingMillis: Long = REQUEST_SPACING_MS,
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
                is Outcome.Failure -> fetched
                is Outcome.Success ->
                    when (val done = builder.finish(fetched.value, plan.bounds, info)) {
                        is Outcome.Failure -> done
                        is Outcome.Success -> Outcome.Success(RasterAreaStaged(areaId, version, staging, done.value))
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
        var done = 0L
        var tileType: Int? = null
        for (range in plan.ranges) {
            for (y in range.minY..range.maxY) {
                for (x in range.minX..range.maxX) {
                    currentCoroutineContext().ensureActive()
                    if (cancellation.isCancelled()) {
                        return Outcome.Failure(AppError.Cancelled)
                    }
                    when (val tile = fetchTile(plan.source, range.zoom, x, y)) {
                        is Outcome.Failure -> return tile
                        is Outcome.Success -> {
                            val image = tile.value ?: continue
                            val type = tileTypeOf(image.contentType) ?: return Outcome.Failure(AppError.CorruptData)
                            if (tileType != null && tileType != type) {
                                return Outcome.Failure(AppError.CorruptData)
                            }
                            tileType = type
                            val added = builder.addTile(range.zoom, x, y, image.bytes)
                            if (added is Outcome.Failure) {
                                return added
                            }
                        }
                    }
                    done += 1
                    onProgress(RasterAreaProgress(done, total))
                    delay(spacingMillis)
                }
            }
        }
        return tileType?.let { Outcome.Success(it) } ?: Outcome.Failure(AppError.NotFound)
    }

    /** One tile, retried once. A tile the server does not have is null; any other failure is returned. */
    private suspend fun fetchTile(
        source: RasterSource,
        z: Int,
        x: Long,
        y: Long,
    ): Outcome<TileImage?> {
        var last: AppError = AppError.Network
        repeat(MAX_ATTEMPTS) {
            when (val result = client.fetch(source, z, x.toInt(), y.toInt())) {
                is Outcome.Success -> return Outcome.Success(result.value)
                is Outcome.Failure -> {
                    last = result.error
                    if (!isWorthRetrying(last)) {
                        return if (last.isMissingTile()) Outcome.Success(null) else result
                    }
                }
            }
        }
        return Outcome.Failure(last)
    }

    private fun isWorthRetrying(error: AppError): Boolean = error == AppError.Network || (error is AppError.Server && error.status >= SERVER_ERROR_FROM)

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
        private const val MAX_ATTEMPTS = 2
        private const val NOT_FOUND = 404
        private const val SERVER_ERROR_FROM = 500
    }
}
