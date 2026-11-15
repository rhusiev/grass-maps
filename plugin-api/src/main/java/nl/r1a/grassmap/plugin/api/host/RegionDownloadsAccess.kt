package nl.r1a.grassmap.plugin.api.host

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.DownloadsData

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
     * Stops [region]'s transfer or removes it from the queue. The call returns
     * after the transfer has stopped, so the region's work directory is already
     * gone.
     */
    suspend fun cancel(region: String): Outcome<Unit>

    /** Removes an installed region from the phone. */
    suspend fun remove(region: String): Outcome<Unit>
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
