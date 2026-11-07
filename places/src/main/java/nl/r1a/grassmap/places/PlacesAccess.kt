package nl.r1a.grassmap.places

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.provider.Place
import nl.r1a.grassmap.plugin.api.provider.PlaceHit
import nl.r1a.grassmap.plugin.api.provider.SearchContext
import java.io.File

/** The directory under the app's external files root that holds region indexes. */
const val SEARCH_DIRECTORY: String = "search"

/** One directory name under [SEARCH_DIRECTORY]; no separators or traversal. */
private val INDEX_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

private fun isSafeIndexName(value: String): Boolean = INDEX_NAME.matches(value) && value != "." && value != ".."

/**
 * The app's handle on the offline search indexes. One [RustPlaceProvider] per
 * mounted region build, opened on first use and kept afterwards.
 *
 * The root is the app's external files directory (or null when the platform
 * gives none); a missing root or a missing index is [AppError.NotReady], so a
 * device without the pushed index reports a typed failure instead of crashing.
 */
class PlacesAccess(
    private val externalFilesRoot: File?,
    private val log: (String) -> Unit = {},
) {
    private val mutex = Mutex()
    private val providers = mutableMapOf<String, Outcome<RustPlaceProvider>>()

    /** The directory the debug command and the region installer agree on. */
    fun indexDirectory(indexName: String): File? {
        if (!isSafeIndexName(indexName)) {
            return null
        }
        return externalFilesRoot?.let { File(File(it, SEARCH_DIRECTORY), indexName) }
    }

    suspend fun provider(indexName: String): Outcome<RustPlaceProvider> =
        mutex.withLock {
            if (!isSafeIndexName(indexName)) {
                log("search: unsafe index name rejected")
                return@withLock Outcome.Failure(AppError.InvalidInput)
            }
            providers[indexName]?.let { return@withLock it }
            val opened =
                withContext(Dispatchers.IO) {
                    val directory = indexDirectory(indexName)
                    when {
                        directory == null -> {
                            log("search: no external files root")
                            Outcome.Failure(AppError.NotReady)
                        }

                        !directory.isDirectory -> {
                            log("search: index directory missing: ${directory.absolutePath}")
                            Outcome.Failure(AppError.NotReady)
                        }

                        else -> {
                            RustPlaceProvider.open(directory, indexName, log)
                        }
                    }
                }
            if (opened is Outcome.Success) {
                providers[indexName] = opened
            }
            opened
        }

    suspend fun search(
        indexName: String,
        query: String,
        context: SearchContext,
        limit: Int,
        cancellation: CancellationHandle,
    ): Outcome<List<PlaceHit>> = withProvider(indexName) { it.search(query, context, limit, cancellation) }

    suspend fun pois(
        indexName: String,
        bbox: BoundingBox,
        categories: Set<String>,
        limit: Int,
        cancellation: CancellationHandle,
    ): Outcome<List<Place>> = withProvider(indexName) { it.pois(bbox, categories, limit, cancellation) }

    suspend fun details(
        indexName: String,
        placeId: String,
        cancellation: CancellationHandle,
    ): Outcome<Place> = withProvider(indexName) { it.details(placeId, cancellation) }

    private suspend fun <T> withProvider(
        indexName: String,
        call: suspend (RustPlaceProvider) -> Outcome<T>,
    ): Outcome<T> =
        when (val resolved = provider(indexName)) {
            is Outcome.Success -> call(resolved.value)
            is Outcome.Failure -> resolved
        }
}
