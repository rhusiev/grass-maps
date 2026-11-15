package nl.r1a.grassmap.regions

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.RegionDownloadsAccess
import nl.r1a.grassmap.plugin.api.model.CatalogPartKind
import nl.r1a.grassmap.plugin.api.model.DownloadProblem
import nl.r1a.grassmap.plugin.api.model.DownloadProblemKind
import nl.r1a.grassmap.plugin.api.model.DownloadTransfer
import nl.r1a.grassmap.plugin.api.model.DownloadTransferState
import nl.r1a.grassmap.plugin.api.model.DownloadsData
import nl.r1a.grassmap.plugin.api.model.DownloadsDefaults
import nl.r1a.grassmap.plugin.api.model.InstalledPack
import nl.r1a.grassmap.plugin.api.model.RegionProblem
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The region store behind `plugins/downloads-ui` (T-0504).
 *
 * It owns the catalogue, the packages on disk and the transfers, and publishes
 * all three as [data]; the plugin derives every row from that one state. There
 * is one download worker, so transfers run one at a time and in the order the
 * screen queued them, and a queue has no length limit: every region the
 * catalogue offers can be queued, and this build imposes no cap of its own.
 *
 * Cancellation is cooperative through [CancellationHandle] and the call does not
 * return until the worker has stopped, so a caller that gets [Outcome.Success]
 * from [cancel] knows the region's work directory is gone and nothing was
 * mounted.
 *
 * The bearer token lives only in this object. It never reaches [data], a log
 * line or a file name; the state says whether one is present and nothing else.
 */
class RegionStore(
    private val paths: RegionPaths,
    private val policy: DownloadPolicy,
    private val network: NetworkAccess,
    private val scope: CoroutineScope,
    transport: DownloadTransport = HttpDownloadTransport(),
    private val log: (String) -> Unit = {},
) : RegionDownloadsAccess {
    private val mutable = MutableStateFlow(DownloadsData(serverUrl = DownloadsDefaults.SERVER_URL))
    override val data: StateFlow<DownloadsData> = mutable.asStateFlow()

    private val catalogClient = RegionCatalogClient(transport)
    private val downloader = RegionDownloader(transport, paths, log)

    /** One refresh at a time, so two screens do not fetch the catalogue twice. */
    private val refreshMutex = Mutex()

    private val queue = Channel<String>(Channel.UNLIMITED)
    private val worker = scope.launch(Dispatchers.IO) { drain() }

    // The worker reads these while a caller may be replacing them.
    @Volatile
    private var serverUrl: String = DownloadsDefaults.SERVER_URL

    @Volatile
    private var token: BearerToken? = null

    /** The stop flag of the transfer that is running, if any. */
    private val activeFlags = ConcurrentHashMap<String, AtomicBoolean>()

    /** Completed when a region's transfer has stopped and its work directory is gone. */
    private val finishing = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    override suspend fun setServer(
        url: String,
        token: String?,
    ): Outcome<Unit> {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        val parsed =
            when (val raw = token?.trim()?.takeIf { it.isNotEmpty() }) {
                null -> {
                    null
                }

                else -> {
                    when (val outcome = BearerToken.of(raw)) {
                        is Outcome.Success -> outcome.value
                        is Outcome.Failure -> return outcome
                    }
                }
            }
        refreshMutex.withLock {
            serverUrl = trimmed
            this.token = parsed
            mutable.update { it.copy(serverUrl = serverUrl, tokenPresent = parsed != null) }
        }
        return refresh()
    }

    override suspend fun refresh(): Outcome<Unit> =
        refreshMutex.withLock {
            val currentToken = token
            val catalog =
                when (val fetched = catalogClient.fetch(serverUrl, currentToken, policy)) {
                    is Outcome.Failure -> {
                        val problem = problemOf(fetched.error, tokenSent = currentToken != null)
                        log("catalog failed kind=${problem.kind} status=${problem.status}")
                        mutable.update { it.copy(catalogProblem = problem) }
                        return@withLock fetched
                    }

                    is Outcome.Success -> {
                        fetched.value
                    }
                }
            val installed = readInstalled()
            mutable.update {
                it.copy(
                    catalog = catalog,
                    catalogProblem = null,
                    installed = installed,
                    serverUrl = serverUrl,
                    tokenPresent = currentToken != null,
                )
            }
            Outcome.Success(Unit)
        }

    override suspend fun startDownload(region: String): Outcome<Unit> {
        if (!RegionIdentifiers.isSafeSegment(region)) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        val entry = mutable.value.catalog?.regions?.firstOrNull { it.id == region }
            ?: return Outcome.Failure(AppError.NotFound)
        if (mutable.value.transfer(region) != null) {
            return Outcome.Success(Unit)
        }
        val queued =
            DownloadTransfer(
                region = region,
                state = DownloadTransferState.WAITING,
                bytesTotal = entry.bytes,
            )
        // The deferred exists before the worker can see the region, so a fast
        // transfer cannot finish before its cancellation handle is registered.
        finishing[region] = CompletableDeferred()
        mutable.update { state ->
            state.copy(
                transfers = state.transfers + queued,
                problems = state.problems.filterNot { it.region == region },
            )
        }
        val enqueued = queue.trySend(region)
        if (enqueued.isFailure) {
            mutable.update { state -> state.copy(transfers = state.transfers.filterNot { it.region == region }) }
            finishing.remove(region)
            return Outcome.Failure(AppError.Internal(QUEUE_ID))
        }
        return Outcome.Success(Unit)
    }

    override suspend fun cancel(region: String): Outcome<Unit> {
        if (!RegionIdentifiers.isSafeSegment(region)) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        val transfer = mutable.value.transfer(region) ?: return Outcome.Success(Unit)
        // The worker reads the flag before each chunk, and the downloader deletes
        // its work directory in a finally block, so the await below is the whole
        // guarantee the caller needs.
        activeFlags[region]?.set(true)
        if (transfer.state == DownloadTransferState.WAITING) {
            // Still in the queue: removing the transfer is enough, and the worker
            // skips a region it has no transfer for.
            mutable.update { state -> state.copy(transfers = state.transfers.filterNot { it.region == region }) }
            finishing.remove(region)?.complete(Unit)
            return Outcome.Success(Unit)
        }
        val finished = finishing[region]
        if (finished != null) {
            finished.await()
            finishing.remove(region)
        }
        return Outcome.Success(Unit)
    }

    override suspend fun remove(region: String): Outcome<Unit> {
        if (!RegionIdentifiers.isSafeSegment(region)) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        if (mutable.value.transfer(region) != null) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        val directory = paths.regionDirectory(region)
        val removed =
            try {
                !directory.exists() || directory.deleteRecursively()
            } catch (exception: SecurityException) {
                false
            }
        if (!removed) {
            return Outcome.Failure(AppError.Internal(REMOVE_ID))
        }
        val installed = readInstalled()
        mutable.update { state ->
            state.copy(
                installed = installed,
                problems = state.problems.filterNot { it.region == region },
            )
        }
        return Outcome.Success(Unit)
    }

    /** Stops the worker. The store is single-use after this. */
    fun close() {
        queue.close()
        worker.cancel()
        for (pending in finishing.values) {
            pending.complete(Unit)
        }
        finishing.clear()
    }

    private suspend fun drain() {
        for (region in queue) {
            runDownload(region)
        }
    }

    private suspend fun runDownload(region: String) {
        val entry = mutable.value.catalog?.regions?.firstOrNull { it.id == region }
        if (entry == null || mutable.value.transfer(region) == null) {
            // Cancelled while it waited, or the catalogue changed under it.
            mutable.update { state -> state.copy(transfers = state.transfers.filterNot { it.region == region }) }
            finish(region)
            return
        }
        val flag = AtomicBoolean(false)
        activeFlags[region] = flag
        updateTransfer(region) { it.copy(state = DownloadTransferState.DOWNLOADING) }
        try {
            val url = RegionServerUrls.packageArchive(serverUrl, region, entry.version)
            if (url == null) {
                recordProblem(region, DownloadProblem(DownloadProblemKind.OTHER))
                return
            }
            val tokenSent = token != null
            val outcome =
                downloader.download(
                    request = RegionRequest(url = url, region = region, version = entry.version, token = token),
                    policy = policy,
                    access = network,
                    cancellation = CancellationHandle { flag.get() },
                    onProgress = { progress -> publishProgress(region, progress) },
                )
            when (outcome) {
                is Outcome.Success -> {
                    val installed = readInstalled()
                    mutable.update { state ->
                        state.copy(
                            transfers = state.transfers.filterNot { it.region == region },
                            problems = state.problems.filterNot { it.region == region },
                            installed = installed,
                        )
                    }
                }

                is Outcome.Failure -> {
                    if (outcome.error == AppError.Cancelled) {
                        mutable.update { state ->
                            state.copy(
                                transfers = state.transfers.filterNot { it.region == region },
                                problems = state.problems.filterNot { it.region == region },
                            )
                        }
                    } else {
                        recordProblem(region, problemOf(outcome.error, tokenSent))
                    }
                }
            }
        } finally {
            activeFlags.remove(region)
            finish(region)
        }
    }

    /** Records a problem and drops the transfer, so the row shows the failure once. */
    private fun recordProblem(
        region: String,
        problem: DownloadProblem,
    ) {
        log("download failed region=$region kind=${problem.kind} status=${problem.status}")
        mutable.update { state ->
            state.copy(
                transfers = state.transfers.filterNot { it.region == region },
                problems = state.problems.filterNot { it.region == region } + RegionProblem(region, problem),
            )
        }
    }

    private fun finish(region: String) {
        finishing.remove(region)?.complete(Unit)
    }

    private fun publishProgress(
        region: String,
        progress: DownloadProgress,
    ) {
        val stage =
            when (progress.stage) {
                DownloadStage.ARCHIVE -> DownloadTransferState.DOWNLOADING
                DownloadStage.VERIFY -> DownloadTransferState.VERIFYING
            }
        updateTransfer(region) { it.copy(state = stage, bytesDone = progress.bytesDone) }
    }

    private fun updateTransfer(
        region: String,
        transform: (DownloadTransfer) -> DownloadTransfer,
    ) {
        mutable.update { state ->
            state.copy(
                transfers =
                    state.transfers.map { transfer ->
                        if (transfer.region == region) transform(transfer) else transfer
                    },
            )
        }
    }

    /** The installed packages, read from the pointer, manifest and headers only. */
    private suspend fun readInstalled(): List<InstalledPack> =
        withContext(Dispatchers.IO) {
            val mounts = RegionMountResolver(paths.root).resolveAll()
            mounts.installed
                .sortedBy { it.region }
                .map { mounted ->
                    InstalledPack(
                        region = mounted.region,
                        version = mounted.version,
                        bytes = mounted.manifest.totalBytes,
                        parts =
                            mounted.manifest.components
                                .map { CatalogPartKind.fromWire(it.kind) }
                                .filter { it != CatalogPartKind.OTHER }
                                .distinct(),
                        dataDate = RegionCatalogParser.dataDateOf(mounted.version),
                    )
                }
        }

    private fun problemOf(
        error: AppError,
        tokenSent: Boolean,
    ): DownloadProblem =
        when (error) {
            is AppError.Server -> {
                if (error.status == HTTP_UNAUTHORIZED) {
                    DownloadProblem(
                        if (tokenSent) DownloadProblemKind.TOKEN_REJECTED else DownloadProblemKind.TOKEN_REQUIRED,
                        status = error.status,
                    )
                } else {
                    DownloadProblem(DownloadProblemKind.SERVER, status = error.status)
                }
            }

            AppError.Network -> DownloadProblem(DownloadProblemKind.NETWORK)
            AppError.CorruptData -> DownloadProblem(DownloadProblemKind.CORRUPT_DATA)
            AppError.Unsupported -> DownloadProblem(DownloadProblemKind.UNSUPPORTED)
            is AppError.SwitchOff -> DownloadProblem(DownloadProblemKind.SWITCH_OFF)
            else -> DownloadProblem(DownloadProblemKind.OTHER)
        }

    private companion object {
        const val REMOVE_ID = "region.store.remove"
        const val QUEUE_ID = "region.store.queue"
        const val HTTP_UNAUTHORIZED = 401
    }
}
