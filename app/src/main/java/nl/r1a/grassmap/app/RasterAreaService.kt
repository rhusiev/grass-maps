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
import nl.r1a.grassmap.regions.InstallLock
import nl.r1a.grassmap.regions.MountedRaster
import nl.r1a.grassmap.regions.RasterPackInstaller
import nl.r1a.grassmap.regions.RegionMountResolver
import java.io.File
import java.security.MessageDigest
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
    private val freeBytes: () -> Long? = { root?.let(::usableSpaceOf) },
    private val now: () -> Instant = Instant::now,
    private val installLock: InstallLock = InstallLock(),
) {
    private val mutableAreas = MutableStateFlow<List<MountedRaster>>(emptyList())
    private val mutableState = MutableStateFlow<RasterAreaDownloadState>(RasterAreaDownloadState.Idle)
    private val cancelled = AtomicBoolean(false)
    private val isPrepared = AtomicBoolean(false)
    private var job: Job? = null

    /** The installed areas, newest first by id. */
    val areas: StateFlow<List<MountedRaster>> = mutableAreas.asStateFlow()
    val state: StateFlow<RasterAreaDownloadState> = mutableState.asStateFlow()

    /**
     * The first call of the process deletes what an earlier process left
     * behind (raster staging, versions no pointer names), then reads the
     * store. Later calls only read it. No download runs before the first
     * call, so nothing live is deleted.
     */
    suspend fun prepare() {
        val store = root ?: return
        if (job?.isActive != true && isPrepared.compareAndSet(false, true)) {
            withContext(io) { RasterPackInstaller(store, installLock).sweepLeftovers() }
        }
        refresh()
    }

    /** Re-reads the store. Call it after every change. */
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
        val removed = withContext(io) { RasterPackInstaller(store, installLock).remove(id) }
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
        val installer = RasterPackInstaller(store, installLock)
        val id = areaId(plan)
        val version = versionOf(now())
        val staging =
            when (val made = installer.newStagingDirectory(id, version)) {
                is Outcome.Success -> made.value
                is Outcome.Failure -> return RasterAreaDownloadState.Failed(failureOf(made.error))
            }
        val downloader = RasterAreaDownloader(client, spacingMillis, freeBytes = freeBytes) { now().toString() }
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
            is AppError.Internal -> internalFailureOf(error)
            else -> RasterAreaFailure.Other
        }

    private fun internalFailureOf(error: AppError.Internal): RasterAreaFailure =
        when (error.id) {
            RasterAreaDownloader.TOO_LARGE_ID -> RasterAreaFailure.TooLarge
            RasterAreaDownloader.NO_SPACE_ID -> RasterAreaFailure.NotEnoughSpace
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
        private const val HASH_HEX_DIGITS = 16

        /**
         * `raster-<source>-<16 hex of the SHA-256 of the bounds and zooms>`.
         * The same area replaces itself.
         */
        fun areaId(plan: RasterAreaPlan): String {
            val key = "${plan.bounds}|${plan.minZoom}|${plan.maxZoom}"
            val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            val hash = digest.joinToString("") { "%02x".format(it) }.take(HASH_HEX_DIGITS)
            return "raster-${plan.source.id}-$hash"
        }

        /** Digits only: the version is a path segment and sorts by time. */
        fun versionOf(instant: Instant): String = instant.toEpochMilli().toString()
    }
}

/**
 * Free bytes on the volume that holds [directory]. A directory that does not
 * exist yet reports 0 from `File.usableSpace`, so a first download would be
 * refused for lack of space. The nearest existing parent names the same volume.
 */
internal fun usableSpaceOf(directory: File): Long {
    var existing: File? = directory
    while (existing != null && !existing.exists()) {
        existing = existing.parentFile
    }
    return existing?.usableSpace ?: 0L
}
