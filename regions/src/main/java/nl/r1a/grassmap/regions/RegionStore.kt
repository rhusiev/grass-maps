package nl.r1a.grassmap.regions

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
import nl.r1a.grassmap.plugin.api.model.MobileDataPolicy
import nl.r1a.grassmap.plugin.api.model.RegionProblem
import java.io.File
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
 * from [cancel] knows the region's work directory is gone and nothing new was
 * mounted. An update that is cancelled before its pointer flip leaves the
 * installed version current and untouched. The flip is the commit point: a
 * cancel that arrives after it finds the new version installed whole, never a
 * half-installed one.
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
    private val metered: MeteredNetworkMonitor = NeverMetered,
    initialMobileDataPolicy: MobileDataPolicy = MobileDataPolicy.WIFI_ONLY,
    private val onMobileDataPolicyChanged: (MobileDataPolicy) -> Unit = {},
    initialAutoUpdate: Boolean = true,
    private val onAutoUpdateChanged: suspend (Boolean) -> Unit = {},
    /** A test seam: runs after a transfer decided to pause and before it takes the claim lock. */
    private val beforePause: suspend (String) -> Unit = {},
) : RegionDownloadsAccess {
    private val mutable =
        MutableStateFlow(
            DownloadsData(
                serverUrl = DownloadsDefaults.SERVER_URL,
                mobileDataPolicy = initialMobileDataPolicy,
                networkMetered = metered.isMetered.value,
                autoUpdate = initialAutoUpdate,
            ),
        )
    override val data: StateFlow<DownloadsData> = mutable.asStateFlow()

    private val catalogClient = RegionCatalogClient(transport)
    private val downloader = RegionDownloader(transport, paths, log)

    /** One refresh at a time, so two screens do not fetch the catalogue twice. */
    private val refreshMutex = Mutex()

    /**
     * Serialises every move of a transfer between WAITING and DOWNLOADING:
     * [startDownload] enqueues one, [cancel] removes a WAITING one, and the
     * worker claims it. Without this the worker can sit between its "is there a
     * transfer for this region" check and publishing the stop flag while
     * [cancel] removes the WAITING transfer and answers success - and the
     * cancelled download then runs to completion and installs.
     */
    private val claimMutex = Mutex()

    /** The user's "download now on mobile data" choice; it ends when the queue empties. */
    private val mobileDataNow = MutableStateFlow(false)

    private val partials = PartialArchives(paths)

    private val queue = Channel<String>(Channel.UNLIMITED)
    private val worker = scope.launch(Dispatchers.IO) { drain() }
    private val meteredWatcher =
        scope.launch {
            metered.isMetered.collect { isMetered -> mutable.update { it.copy(networkMetered = isMetered) } }
        }

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
            val fetchedCatalog =
                // The catalogue is network contact like a package, so it names the
                // same switch and is refused before a socket is opened.
                network.request(policy.switch) { catalogClient.fetch(serverUrl, currentToken, policy) }
            val catalog =
                when (val fetched = fetchedCatalog) {
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
        val entry = mutable.value.catalog?.download(region) ?: return Outcome.Failure(AppError.NotFound)
        // An add-on is read through its region, so it installs only beside one.
        if (entry.addonOf != null && mutable.value.installed.none { it.region == entry.addonOf }) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        return claimMutex.withLock {
            if (mutable.value.transfer(region) != null) {
                return@withLock Outcome.Success(Unit)
            }
            val queued =
                DownloadTransfer(
                    region = region,
                    state = DownloadTransferState.WAITING,
                    bytesTotal = entry.bytes,
                )
            if (mutable.value.transfers.isEmpty()) {
                // The mobile-data choice ends with the queue it was made for.
                mobileDataNow.value = false
            }
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
                return@withLock Outcome.Failure(AppError.Internal(QUEUE_ID))
            }
            Outcome.Success(Unit)
        }
    }

    override suspend fun updateAll(): Outcome<Unit> {
        // Each startDownload takes the claim lock itself, so the invariant on
        // WAITING and DOWNLOADING moves holds and no lock spans the loop.
        for (entry in mutable.value.updates) {
            val queued = startDownload(entry.id)
            if (queued is Outcome.Failure) {
                return queued
            }
        }
        return Outcome.Success(Unit)
    }

    override suspend fun cancel(region: String): Outcome<Unit> {
        if (!RegionIdentifiers.isSafeSegment(region)) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        var stopped: CompletableDeferred<Unit>? = null
        claimMutex.withLock {
            val transfer = mutable.value.transfer(region) ?: return Outcome.Success(Unit)
            if (transfer.state.isQueued()) {
                // Still in the queue: removing the transfer is enough, and the
                // worker claims nothing for a region without one.
                mutable.update { state -> state.copy(transfers = state.transfers.filterNot { it.region == region }) }
                partials.discard(region)
                finishing.remove(region)?.complete(Unit)
                return Outcome.Success(Unit)
            }
            // Already claimed by the worker, so the flag exists; the worker reads
            // it before each chunk and deletes its work directory in a finally
            // block, so the await below is the whole guarantee the caller needs.
            activeFlags[region]?.set(true)
            stopped = finishing[region]
        }
        // The worker removes its own `finishing` entry, so a new transfer for the
        // same region is never cleared here.
        stopped?.await()
        return Outcome.Success(Unit)
    }

    override suspend fun remove(region: String): Outcome<Unit> {
        if (!RegionIdentifiers.isSafeSegment(region)) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        val addonPackage = RegionIdentifiers.elevationAddonPackage(region)
        if (mutable.value.transfer(region) != null || mutable.value.transfer(addonPackage) != null) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        // An add-on is useless without its region, so removing the region takes it along.
        val directories =
            listOf(region, addonPackage).map { paths.regionDirectory(it) }
        val removed =
            try {
                directories.all { !it.exists() || it.deleteRecursively() }
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

    override suspend fun setMobileDataPolicy(policy: MobileDataPolicy): Outcome<Unit> {
        mutable.update { it.copy(mobileDataPolicy = policy) }
        onMobileDataPolicyChanged(policy)
        return Outcome.Success(Unit)
    }

    override suspend fun setAutoUpdate(enabled: Boolean): Outcome<Unit> {
        mutable.update { it.copy(autoUpdate = enabled) }
        onAutoUpdateChanged(enabled)
        return Outcome.Success(Unit)
    }

    override suspend fun useMobileDataNow(): Outcome<Unit> {
        claimMutex.withLock {
            // With nothing queued there is nothing for the choice to apply to.
            if (mutable.value.transfers.isNotEmpty()) {
                mobileDataNow.value = true
            }
        }
        return Outcome.Success(Unit)
    }

    /** Stops the worker. The store is single-use after this. */
    fun close() {
        queue.close()
        worker.cancel()
        meteredWatcher.cancel()
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

    /** True when a transfer may use the network as it is now. */
    private fun mayTransfer(): Boolean =
        mutable.value.mobileDataPolicy == MobileDataPolicy.ANY ||
            !metered.isMetered.value ||
            mobileDataNow.value

    /**
     * Holds [region] in WAITING_FOR_WIFI until a transfer may run, or until the
     * user removed it from the queue. The worker is single, so every transfer
     * behind this one waits too, which is right: the policy is global.
     */
    private suspend fun awaitAllowedNetwork(region: String) {
        if (mayTransfer()) {
            return
        }
        updateTransfer(region) { it.copy(state = DownloadTransferState.WAITING_FOR_WIFI) }
        log("download waits for an unmetered network region=$region")
        combine(mutable, metered.isMetered, mobileDataNow) { state, _, _ -> state }
            .first { state -> state.transfer(region) == null || mayTransfer() }
    }

    private suspend fun runDownload(region: String) {
        awaitAllowedNetwork(region)
        val entry = mutable.value.catalog?.download(region)
        val flag = AtomicBoolean(false)
        val claimed =
            entry != null &&
                claimMutex.withLock {
                    if (mutable.value.transfer(region) == null) {
                        false
                    } else {
                        // Published under the same lock [cancel] takes, so a
                        // cancellation cannot slip in between this transfer
                        // existing and its stop flag being visible.
                        activeFlags[region] = flag
                        updateTransfer(region) { it.copy(state = DownloadTransferState.DOWNLOADING) }
                        true
                    }
                }
        if (!claimed) {
            // Cancelled while it waited, or the catalogue changed under it.
            mutable.update { state -> state.copy(transfers = state.transfers.filterNot { it.region == region }) }
            finish(region)
            return
        }
        var paused = false
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
                    cancellation = CancellationHandle { flag.get() || !mayTransfer() },
                    onProgress = { progress -> publishProgress(region, progress) },
                    keepPartialOnCancel = { !flag.get() },
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
                    if (outcome.error == AppError.Cancelled && !flag.get()) {
                        paused = pause(region, flag)
                        return
                    }
                    if (outcome.error == AppError.Cancelled) {
                        mutable.update { state ->
                            state.copy(
                                transfers = state.transfers.filterNot { it.region == region },
                                problems = state.problems.filterNot { it.region == region },
                            )
                        }
                    } else {
                        val isUpdate = mutable.value.installed(region) != null
                        recordProblem(region, problemOf(outcome.error, tokenSent, isUpdate))
                    }
                }
            }
        } finally {
            activeFlags.remove(region)
            if (!paused) {
                finish(region)
            }
        }
    }

    /**
     * The network turned metered under a running transfer. The partial archive
     * stays, the transfer goes back to the end of the queue and waits. Returns
     * false when nothing was requeued, so the caller finishes the transfer.
     *
     * The decision is made under the lock [cancel] takes: a cancel that set
     * [flag] after the worker saw the pause is a user cancel, so the transfer
     * is dropped and its partial archive deleted.
     */
    private suspend fun pause(
        region: String,
        flag: AtomicBoolean,
    ): Boolean {
        beforePause(region)
        return claimMutex.withLock {
            if (flag.get()) {
                mutable.update { state ->
                    state.copy(
                        transfers = state.transfers.filterNot { it.region == region },
                        problems = state.problems.filterNot { it.region == region },
                    )
                }
                partials.discard(region)
                false
            } else if (mutable.value.transfer(region) == null) {
                false
            } else {
                updateTransfer(region) { it.copy(state = DownloadTransferState.WAITING_FOR_WIFI) }
                queue.trySend(region).isSuccess
            }
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
        updateTransfer(region) {
            it.copy(state = stage, bytesDone = progress.bytesDone, bytesTotal = progress.bytesTotal ?: it.bytesTotal)
        }
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

    /**
     * The installed packages, read from the pointer, manifest and headers only.
     * The first read of the process also fixes [DownloadsData.mountedPacks]: an
     * install needs a catalogue, and the catalogue needs a read, so no install
     * of this process comes before it.
     */
    private suspend fun readInstalled(): List<InstalledPack> =
        withContext(Dispatchers.IO) {
            readInstalledPacks().also { packs ->
                mutable.update { state -> if (state.mountedPacks == null) state.copy(mountedPacks = packs) else state }
            }
        }

    private fun readInstalledPacks(): List<InstalledPack> {
        val resolver = RegionMountResolver(paths.root)
        return resolver
            .resolveAll()
            .installed
            .sortedBy { it.region }
            .map { mounted ->
                val addon = resolver.elevationAddon(mounted.region)
                InstalledPack(
                    region = mounted.region,
                    version = mounted.version,
                    bytes = mounted.manifest.totalBytes + (addon?.let { addonBytes(it) } ?: 0L),
                    parts =
                        (
                            mounted.manifest.components.map { CatalogPartKind.fromWire(it.kind) } +
                                listOfNotNull(CatalogPartKind.ELEVATION_30M.takeIf { addon != null })
                        ).filter { it != CatalogPartKind.OTHER }.distinct(),
                    dataDate = RegionCatalogParser.dataDateOf(mounted.version),
                    build = (mounted.routing as? RoutingPart.Directory)?.build,
                )
            }
    }

    /** The size of the add-on's one component, read from the file the resolver checked. */
    private fun addonBytes(directory: File): Long =
        directory.listFiles()?.filter { it.isFile && it.name != RegionIdentifiers.MANIFEST_FILE }?.sumOf { it.length() } ?: 0L

    private fun problemOf(
        error: AppError,
        tokenSent: Boolean,
        isUpdate: Boolean = false,
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

            AppError.Network -> {
                DownloadProblem(DownloadProblemKind.NETWORK)
            }

            AppError.CorruptData -> {
                // The downloader already fetched the package twice, and the
                // installed version is untouched.
                DownloadProblem(if (isUpdate) DownloadProblemKind.UPDATE_FAILED else DownloadProblemKind.CORRUPT_DATA)
            }

            AppError.Unsupported -> {
                DownloadProblem(DownloadProblemKind.UNSUPPORTED)
            }

            is AppError.SwitchOff -> {
                DownloadProblem(DownloadProblemKind.SWITCH_OFF)
            }

            else -> {
                DownloadProblem(DownloadProblemKind.OTHER)
            }
        }

    private fun DownloadTransferState.isQueued(): Boolean =
        this == DownloadTransferState.WAITING || this == DownloadTransferState.WAITING_FOR_WIFI

    private companion object {
        const val REMOVE_ID = "region.store.remove"
        const val QUEUE_ID = "region.store.queue"
        const val HTTP_UNAUTHORIZED = 401
    }
}
