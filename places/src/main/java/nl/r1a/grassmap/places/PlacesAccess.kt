package nl.r1a.grassmap.places

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.CoordinateParser
import nl.r1a.grassmap.plugin.api.provider.Place
import nl.r1a.grassmap.plugin.api.provider.PlaceDetails
import nl.r1a.grassmap.plugin.api.provider.PlaceHit
import nl.r1a.grassmap.plugin.api.provider.SearchContext
import java.io.File

/** The directory under the app's external files root that holds region indexes. */
const val SEARCH_DIRECTORY: String = "search"

/**
 * The id of the row a typed coordinate produces. It names a point, not a
 * record in an index, so [PlacesAccess.details] has nothing for it.
 */
const val COORDINATE_HIT_ID: String = "coordinate"

/** The one score a coordinate row carries; it is the only row in its result set. */
private const val COORDINATE_SCORE: Float = 1.0f

/** One directory name under [SEARCH_DIRECTORY]; no separators or traversal. */
private val INDEX_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

private fun isSafeIndexName(value: String): Boolean = INDEX_NAME.matches(value) && value != "." && value != ".."

/**
 * The app's handle on the offline search indexes. One [RustPlaceProvider] per
 * mounted region build, opened on first use and kept afterwards.
 *
 * An index name resolves to a mounted pack's section first and to
 * [externalFilesRoot] after that. The external root keeps the pushed debug and
 * bench section working; a downloaded pack carries its section beside its map,
 * so a pack needs no copy under `search/`.
 *
 * A missing root or a missing index is [AppError.NotReady], so a device without
 * a pushed index reports a typed failure instead of crashing.
 *
 * A [CoordinateParser] runs before the index (T-0206): a coordinate the user
 * types is one row at that point on any device, with or without an installed
 * region. Nothing about such a row comes from the index, so a point outside
 * the installed region still shows and still moves the map; only opening a
 * place card for it has no record behind it.
 */
class PlacesAccess(
    private val externalFilesRoot: File?,
    private val coordinateParser: CoordinateParser = CoordinateParser.None,
    private val log: (String) -> Unit = {},
    /** A mounted pack's section by index name; it wins over [externalFilesRoot]. */
    private val packIndex: (String) -> File? = { null },
) {
    private val mutex = Mutex()

    /** Opened readers by section directory, so a new pack version opens a new one. */
    private val providers = mutableMapOf<String, Outcome<RustPlaceProvider>>()

    /** The directory [indexName] opens: the pack's section, else the external one. */
    fun indexDirectory(indexName: String): File? {
        if (!isSafeIndexName(indexName)) {
            return null
        }
        packIndex(indexName)?.let { return it }
        return externalFilesRoot?.let { File(File(it, SEARCH_DIRECTORY), indexName) }
    }

    suspend fun provider(indexName: String): Outcome<RustPlaceProvider> =
        mutex.withLock {
            if (indexName.isEmpty()) {
                log("search: no index name")
                return@withLock Outcome.Failure(AppError.NotReady)
            }
            if (!isSafeIndexName(indexName)) {
                log("search: unsafe index name rejected")
                return@withLock Outcome.Failure(AppError.InvalidInput)
            }
            val directory = indexDirectory(indexName)
            if (directory == null) {
                log("search: no directory for index $indexName")
                return@withLock Outcome.Failure(AppError.NotReady)
            }
            val key = directory.absolutePath
            providers[key]?.let { return@withLock it }
            val opened =
                withContext(Dispatchers.IO) {
                    when {
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
                providers[key] = opened
            }
            opened
        }

    suspend fun search(
        indexName: String,
        query: String,
        context: SearchContext,
        limit: Int,
        cancellation: CancellationHandle,
    ): Outcome<List<PlaceHit>> {
        if (limit !in 1..MAX_SEARCH_RESULTS) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        val point = coordinateParser.parse(query)
        if (point != null) {
            return coordinateResult(point, query, indexName, context, cancellation)
        }
        return withProvider(indexName) { it.search(query, context, limit, cancellation) }
    }

    /**
     * One row at [point]. The row is not from the index, so it does not need
     * one; "this area" still applies to it, because a result outside the map
     * would contradict what the caller asked for.
     */
    private fun coordinateResult(
        point: GeoPoint,
        query: String,
        indexName: String,
        context: SearchContext,
        cancellation: CancellationHandle,
    ): Outcome<List<PlaceHit>> {
        if (cancellation.isCancelled()) {
            return Outcome.Failure(AppError.Cancelled)
        }
        val viewport = context.viewport
        if (viewport != null) {
            if (!viewport.isValid()) {
                return Outcome.Failure(AppError.InvalidInput)
            }
            if (!viewport.contains(point)) {
                return Outcome.Success(emptyList())
            }
        }
        return Outcome.Success(
            listOf(
                PlaceHit(
                    id = COORDINATE_HIT_ID,
                    name = query.trim(),
                    kind = "",
                    position = point,
                    address = null,
                    regionBuild = indexName,
                    score = COORDINATE_SCORE,
                    isCoordinate = true,
                ),
            ),
        )
    }

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
    ): Outcome<PlaceDetails> = withProvider(indexName) { it.details(placeId, cancellation) }

    private suspend fun <T> withProvider(
        indexName: String,
        call: suspend (RustPlaceProvider) -> Outcome<T>,
    ): Outcome<T> =
        when (val resolved = provider(indexName)) {
            is Outcome.Success -> call(resolved.value)
            is Outcome.Failure -> resolved
        }
}
