package nl.r1a.grassmaps.plugin.api.host

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.model.DownloadsData
import nl.r1a.grassmaps.plugin.api.model.MobileDataPolicy

/**
 * The region store as the downloads screen reads it (T-0504).
 *
 * The host owns the catalogue, the packages on disk and the transfers; the
 * plugin derives every row. [data] is the whole of what the screen may show, so
 * a row never comes from a second, private copy of the catalogue.
 *
 * A token is passed in once through [setServer] and is never read back: [data]
 * says only whether one is present. Neither this interface nor its
 * implementation returns, logs or serializes the token value.
 */
interface RegionDownloadsAccess {
    /** The catalogue, the installed packages, the transfers and their problems. */
    val data: StateFlow<DownloadsData>

    /**
     * Whether the region-download privacy switch is on. A host that does not
     * publish the switch reads as off, so a build without it never lets a screen
     * claim that downloads are allowed.
     */
    val downloadsEnabled: Boolean get() = false

    /**
     * Writes the region-download privacy switch. The host writes the same
     * switch the network gate reads, so a screen cannot grant its own network
     * access. A host that cannot write it answers a typed [AppError.NotReady].
     */
    suspend fun setDownloadsEnabled(enabled: Boolean): Outcome<Unit> = Outcome.Failure(AppError.NotReady)

    /**
     * Sets the server base URL and, when [token] is not null or blank, the
     * bearer token the downloads carry. A null or blank token clears it, which
     * is what a tokenless server needs. The catalogue is fetched again, so a
     * caller sees at once whether the server accepts the settings.
     */
    suspend fun setServer(
        url: String,
        token: String? = null,
    ): Outcome<Unit>

    /** Fetches the catalogue again and re-reads the installed packages. */
    suspend fun refresh(): Outcome<Unit>

    /**
     * Queues [region]'s archive. The call returns once the region is queued, so
     * the screen keeps drawing while the transfer runs; a region already
     * queued or downloading is not queued twice.
     */
    suspend fun startDownload(region: String): Outcome<Unit>

    /**
     * Queues every installed region whose catalogue version is newer, except
     * those already queued or downloading. Each region installs beside its old
     * version and replaces it only after verification. The call returns once
     * the regions are queued.
     */
    suspend fun updateAll(): Outcome<Unit> = Outcome.Failure(AppError.NotReady)

    /**
     * Stops [region]'s transfer or removes it from the queue. The call returns
     * after the transfer has stopped, so the region's work directory is already
     * gone.
     */
    suspend fun cancel(region: String): Outcome<Unit>

    /** Removes an installed region from the phone. */
    suspend fun remove(region: String): Outcome<Unit>

    /**
     * Sets whether transfers may use a metered network. A host that cannot
     * keep the choice answers [AppError.NotReady].
     */
    suspend fun setMobileDataPolicy(policy: MobileDataPolicy): Outcome<Unit> = Outcome.Failure(AppError.NotReady)

    /**
     * Turns the monthly automatic update on or off. A host that cannot keep
     * the choice answers [AppError.NotReady].
     */
    suspend fun setAutoUpdate(enabled: Boolean): Outcome<Unit> = Outcome.Failure(AppError.NotReady)

    /**
     * Lets the transfers that wait for an unmetered network run on a metered one
     * now. The choice ends when no transfer is left, so the policy itself does
     * not change.
     */
    suspend fun useMobileDataNow(): Outcome<Unit> = Outcome.Failure(AppError.NotReady)
}

/** The source a host uses before the region store is wired. */
object NoRegionDownloadsAccess : RegionDownloadsAccess {
    private val empty = MutableStateFlow(DownloadsData())

    override val data: StateFlow<DownloadsData> = empty.asStateFlow()

    override suspend fun setServer(
        url: String,
        token: String?,
    ): Outcome<Unit> = Outcome.Failure(AppError.NotReady)

    override suspend fun refresh(): Outcome<Unit> = Outcome.Failure(AppError.NotReady)

    override suspend fun startDownload(region: String): Outcome<Unit> = Outcome.Failure(AppError.NotReady)

    override suspend fun cancel(region: String): Outcome<Unit> = Outcome.Failure(AppError.NotReady)

    override suspend fun remove(region: String): Outcome<Unit> = Outcome.Failure(AppError.NotReady)
}
