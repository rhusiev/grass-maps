package nl.r1a.grassmap.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugins.raster.RasterAreaDownloadState
import nl.r1a.grassmap.plugins.raster.RasterAreaDownloader
import nl.r1a.grassmap.plugins.raster.RasterAreaFailure
import nl.r1a.grassmap.plugins.raster.RasterAreaItem
import nl.r1a.grassmap.plugins.raster.RasterAreaLayer
import nl.r1a.grassmap.plugins.raster.RasterAreaPlan
import nl.r1a.grassmap.plugins.raster.RasterAreaProgress
import nl.r1a.grassmap.plugins.raster.RasterLicence
import nl.r1a.grassmap.plugins.raster.RasterTileClient
import nl.r1a.grassmap.regions.MountedRaster
import nl.r1a.grassmap.regions.RasterPackInstaller
import nl.r1a.grassmap.regions.RegionMountResolver
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Downloads, installs and lists offline raster areas (T-0513).
 *
 * One download runs at a time. The tiles come through [client], so the
 * online-tiles switch guards them. The staged package goes through
 * [RasterPackInstaller], the region store's verify-then-flip path. Listing
 * reads the store only, so it needs no network.
 */
class RasterAreaService(
    private val root: File?,
    private val client: RasterTileClient,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val spacingMillis: Long = RasterAreaDownloader.REQUEST_SPACING_MS,
    private val freeBytes: () -> Long? = { root?.usableSpace },
    private val now: () -> Instant = Instant::now,
) {
    private val mutableAreas = MutableStateFlow<List<MountedRaster>>(emptyList())
    private val mutableState = MutableStateFlow<RasterAreaDownloadState>(RasterAreaDownloadState.Idle)
    private val cancelled = AtomicBoolean(false)
    private var job: Job? = null

    /** The installed areas, newest first by id. */
    val areas: StateFlow<List<MountedRaster>> = mutableAreas.asStateFlow()
    val state: StateFlow<RasterAreaDownloadState> = mutableState.asStateFlow()

    /** Re-reads the store. Call it at start and after every change. */
    suspend fun refresh() {
        val store = root ?: return
        mutableAreas.value = withContext(io) { RegionMountResolver(store).rasterAreas() }
    }

    /** Free bytes on the store's filesystem now, or null when unknown. */
    fun freeBytesNow(): Long? = freeBytes()

    /** Why [plan] may not start now, or null. Includes the free-space check. */
    fun refusalOf(plan: RasterAreaPlan): RasterAreaPlan.Refusal? = plan.refusalFor(freeBytes())

    /** Starts the download in the background. A second start while one runs is ignored. */
    fun start(
        plan: RasterAreaPlan,
        name: String,
    ) {
        if (job?.isActive == true || root == null) {
            return
        }
        cancelled.set(false)
        mutableState.value = RasterAreaDownloadState.Running(RasterAreaProgress(0, plan.tileCount))
        job = scope.launch { mutableState.value = download(plan, name) }
    }

    fun cancel() {
        cancelled.set(true)
    }

    /** Clears a finished or failed state so the sheet shows its idle form. */
    fun acknowledge() {
        if (mutableState.value !is RasterAreaDownloadState.Running) {
            mutableState.value = RasterAreaDownloadState.Idle
        }
    }

    suspend fun remove(id: String): Outcome<Unit> {
        val store = root ?: return Outcome.Failure(AppError.NotFound)
        val removed = withContext(io) { RasterPackInstaller(store).remove(id) }
        refresh()
        return removed
    }

    /** The download and install, as one value for the sheet. Visible for tests. */
    suspend fun download(
        plan: RasterAreaPlan,
        name: String,
    ): RasterAreaDownloadState {
        val store = root ?: return RasterAreaDownloadState.Failed(RasterAreaFailure.Other)
        if (refusalOf(plan) != null) {
            return RasterAreaDownloadState.Failed(RasterAreaFailure.Refused)
        }
        val installer = RasterPackInstaller(store)
        val id = areaId(plan)
        val version = versionOf(now())
        val staging = installer.newStagingDirectory(id, version)
        val downloader = RasterAreaDownloader(client, spacingMillis) { now().toString() }
        val cancellation = CancellationHandle { cancelled.get() }
        val outcome =
            withContext(io) {
                try {
                    when (val staged = downloader.download(plan, id, version, name, staging, cancellation, ::report)) {
                        is Outcome.Failure -> staged
                        is Outcome.Success -> installer.install(id, version, staging, cancellation)
                    }
                } finally {
                    staging.deleteRecursively()
                }
            }
        refresh()
        return when (outcome) {
            is Outcome.Success -> RasterAreaDownloadState.Done
            is Outcome.Failure -> RasterAreaDownloadState.Failed(failureOf(outcome.error))
        }
    }

    private fun report(progress: RasterAreaProgress) {
        mutableState.value = RasterAreaDownloadState.Running(progress)
    }

    private fun failureOf(error: AppError): RasterAreaFailure =
        when (error) {
            AppError.Cancelled -> RasterAreaFailure.Cancelled
            is AppError.SwitchOff -> RasterAreaFailure.SwitchOff
            AppError.Network, is AppError.Server -> RasterAreaFailure.Network
            AppError.InvalidInput -> RasterAreaFailure.Refused
            else -> RasterAreaFailure.Other
        }

    /** The layers for the installed areas. They read files, so no network switch applies. */
    fun layers(): List<RasterAreaLayer> =
        mutableAreas.value.map { area ->
            RasterAreaLayer(
                id = area.id,
                packFile = area.file.absolutePath,
                minZoom = area.minZoom,
                maxZoom = area.maxZoom,
                attributionHtml = area.metadata.attribution,
            )
        }

    /** The sheet rows. The licence is the pack's own, so it shows offline. */
    fun items(): List<RasterAreaItem> =
        mutableAreas.value.map { area ->
            RasterAreaItem(
                id = area.id,
                name = area.metadata.name,
                bytes = area.bytes,
                tileCount = area.tileCount,
                minZoom = area.minZoom,
                maxZoom = area.maxZoom,
                licence =
                    RasterLicence(
                        name = area.metadata.licence,
                        url = area.metadata.licenceUrl,
                        isNonCommercial = area.metadata.isNonCommercial,
                    ),
                attributionHtml = area.metadata.attribution,
            )
        }

    companion object {
        private const val HEX_RADIX = 16
        private const val HASH_DIGITS = 8

        /** `raster-<source>-<8 hex of the bounds and zooms>`. The same area replaces itself. */
        fun areaId(plan: RasterAreaPlan): String {
            val key = "${plan.bounds}|${plan.minZoom}|${plan.maxZoom}"
            val hash =
                key
                    .hashCode()
                    .toUInt()
                    .toString(HEX_RADIX)
                    .padStart(HASH_DIGITS, '0')
            return "raster-${plan.source.id}-$hash"
        }

        /** Digits only: the version is a path segment and sorts by time. */
        fun versionOf(instant: Instant): String = instant.toEpochMilli().toString()
    }
}
