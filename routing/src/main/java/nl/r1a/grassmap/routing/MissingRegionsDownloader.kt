package nl.r1a.grassmaps.routing

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.host.RegionDownloadsAccess
import nl.r1a.grassmaps.plugin.api.host.RoutingPackAccess
import nl.r1a.grassmaps.plugin.api.model.DownloadProblem
import nl.r1a.grassmaps.plugin.api.model.DownloadProblemKind
import nl.r1a.grassmaps.plugin.api.model.DownloadsData
import nl.r1a.grassmaps.plugin.api.model.MissingRegions
import nl.r1a.grassmaps.plugin.api.model.MissingRegionsDownload

/** How "Download and route" ended before the route was asked for. */
sealed interface MissingDownloadResult {
    /** Every region is installed and the routing mount was rebuilt from them. */
    data object Ready : MissingDownloadResult

    /** A transfer failed, or the store refused to queue one. */
    data class Failed(
        val progress: MissingRegionsDownload,
    ) : MissingDownloadResult

    /** A transfer left the queue without installing, for example cancelled on the downloads screen. */
    data object Stopped : MissingDownloadResult

    /** The regions are installed but no routing directory could be mounted from them. */
    data object MountUnavailable : MissingDownloadResult
}

/**
 * Downloads the regions a route needs and rebuilds the routing mount
 * (T-0511). The steps run in this order, and the caller asks for the route
 * only after [run] returns [MissingDownloadResult.Ready]:
 *
 * 1. Queue every region through the store's public API.
 * 2. Wait on the store's data until each region is installed with the parts
 *    the prompt listed, or a problem is recorded, or its transfer is gone.
 * 3. Ask [RoutingPackAccess] for the pack directory. That rebuilds the merged
 *    mount from the installed packs. The Valhalla engine compares the
 *    directory it opened with the one it is given, and reopens on a change.
 */
class MissingRegionsDownloader(
    private val downloads: RegionDownloadsAccess,
    private val packs: RoutingPackAccess,
) {
    suspend fun run(
        missing: MissingRegions,
        onProgress: suspend (MissingRegionsDownload) -> Unit,
    ): MissingDownloadResult {
        val total = missing.bytes
        for (region in missing.regions) {
            val queued = downloads.startDownload(region.region)
            if (queued is Outcome.Failure) {
                return MissingDownloadResult.Failed(MissingRegionsDownload(0, total, problemOf(queued.error)))
            }
        }
        val outcome = awaitInstalled(missing, onProgress)
        if (outcome !is Wait.Done) {
            return outcome.toResult(total)
        }
        return if (packs.packDirectory() == null) {
            MissingDownloadResult.MountUnavailable
        } else {
            MissingDownloadResult.Ready
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun awaitInstalled(
        missing: MissingRegions,
        onProgress: suspend (MissingRegionsDownload) -> Unit,
    ): Wait =
        downloads.data
            .map { data -> evaluate(missing, data) }
            .distinctUntilChanged()
            .onEach { if (it is Wait.Running) onProgress(it.progress) }
            .mapLatest { wait ->
                if (wait is Wait.Vanished) delay(VANISH_GRACE_MS)
                wait
            }.first { it !is Wait.Running }

    private fun evaluate(
        missing: MissingRegions,
        data: DownloadsData,
    ): Wait {
        var done = 0L
        var allInstalled = true
        var vanished = false
        for (region in missing.regions) {
            data.problem(region.region)?.let { problem ->
                return Wait.Failed(MissingRegionsDownload(done, missing.bytes, problem))
            }
            val transfer = data.transfer(region.region)
            val have = data.installed(region.region)?.parts.orEmpty()
            val isInstalled = transfer == null && region.parts.all { it.kind in have }
            when {
                isInstalled -> done += region.bytes
                transfer != null -> done += transfer.fractionOf(region.bytes)
                else -> vanished = true
            }
            allInstalled = allInstalled && isInstalled
        }
        return when {
            allInstalled -> Wait.Done
            vanished -> Wait.Vanished
            else -> Wait.Running(MissingRegionsDownload(done, missing.bytes))
        }
    }

    private fun problemOf(error: AppError): DownloadProblem =
        when (error) {
            AppError.Network -> DownloadProblem(DownloadProblemKind.NETWORK)
            is AppError.SwitchOff -> DownloadProblem(DownloadProblemKind.SWITCH_OFF)
            is AppError.Server -> DownloadProblem(DownloadProblemKind.SERVER, error.status)
            else -> DownloadProblem(DownloadProblemKind.OTHER)
        }

    private sealed interface Wait {
        data object Done : Wait

        /** A region has neither a transfer nor an install; the grace period lets a store finish its two updates. */
        data object Vanished : Wait

        data class Running(
            val progress: MissingRegionsDownload,
        ) : Wait

        data class Failed(
            val progress: MissingRegionsDownload,
        ) : Wait

        fun toResult(total: Long): MissingDownloadResult =
            when (this) {
                is Failed -> MissingDownloadResult.Failed(progress)
                Vanished -> MissingDownloadResult.Stopped
                Done, is Running -> MissingDownloadResult.Failed(MissingRegionsDownload(0, total))
            }
    }

    private companion object {
        /** The store removes a finished transfer and publishes the install as two updates. */
        const val VANISH_GRACE_MS = 2_000L
    }
}
