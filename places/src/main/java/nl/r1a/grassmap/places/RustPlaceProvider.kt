package nl.r1a.grassmap.places

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.r1a.grassmap.places.core.CancelToken
import nl.r1a.grassmap.places.core.CoreException
import nl.r1a.grassmap.places.core.OsmElementType
import nl.r1a.grassmap.places.core.PlaceDetail
import nl.r1a.grassmap.places.core.SearchIndex
import nl.r1a.grassmap.places.core.SearchSession
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.OsmType
import nl.r1a.grassmap.plugin.api.provider.Place
import nl.r1a.grassmap.plugin.api.provider.PlaceDetails
import nl.r1a.grassmap.plugin.api.provider.PlaceHit
import nl.r1a.grassmap.plugin.api.provider.PlaceProvider
import nl.r1a.grassmap.plugin.api.provider.PlaceProviderContract
import nl.r1a.grassmap.plugin.api.provider.SearchContext
import java.io.File
import nl.r1a.grassmap.places.core.PlaceHit as CorePlaceHit

/** The largest result count the contract and the Rust reader accept. */
const val MAX_SEARCH_RESULTS: Int = 50

/** How often the cancellation watcher checks the caller's handle. */
private const val CANCEL_POLL_MILLIS: Long = 2L
private const val NATIVE_LINKAGE_ERROR: String = "places.native"
private const val ID_SEPARATOR: Char = ':'
private const val MAX_LATITUDE: Double = 90.0
private const val MAX_LONGITUDE: Double = 180.0

/**
 * The offline [PlaceProvider] over one region's Rust search section.
 *
 * The index is memory-mapped and opened once, off Main; queries run on
 * `Default` behind one session so the previous keystroke's resolved words are
 * reused. A query that is superseded returns [AppError.Cancelled] before it
 * produces a result.
 */
class RustPlaceProvider private constructor(
    private val index: SearchIndex,
    private val regionBuild: String,
) : PlaceProvider {
    override val contract = PlaceProviderContract.REFERENCE

    /** Rows in the mounted place table; a diagnostics value, not a contract. */
    val placeCount: Int get() = index.placeCount().toInt()

    private val sessionMutex = Mutex()
    private var session: SearchSession? = null

    override suspend fun search(
        query: String,
        context: SearchContext,
        limit: Int,
        cancellation: CancellationHandle,
    ): Outcome<List<PlaceHit>> {
        if (query.isBlank() || limit !in 1..MAX_SEARCH_RESULTS) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        if (cancellation.isCancelled()) {
            return Outcome.Failure(AppError.Cancelled)
        }
        return withContext(Dispatchers.Default) {
            sessionMutex.withLock {
                val active = session ?: index.newSession(null, null).also { session = it }
                when (val origin = context.position) {
                    null -> active.clearOrigin()
                    else -> active.setOrigin(origin.latitude, origin.longitude)
                }
                queryWithCancellation(cancellation) { token ->
                    active.query(query, limit.toUInt(), token).map(::toHit)
                }
            }
        }
    }

    override suspend fun pois(
        bbox: BoundingBox,
        categories: Set<String>,
        limit: Int,
        cancellation: CancellationHandle,
    ): Outcome<List<Place>> {
        if (limit !in 1..MAX_SEARCH_RESULTS || !isValid(bbox)) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        if (cancellation.isCancelled()) {
            return Outcome.Failure(AppError.Cancelled)
        }
        return withContext(Dispatchers.Default) {
            queryWithCancellation(cancellation) { token ->
                index
                    .pois(
                        bbox.southWest.latitude,
                        bbox.southWest.longitude,
                        bbox.northEast.latitude,
                        bbox.northEast.longitude,
                        categories.toList(),
                        limit.toUInt(),
                        token,
                    ).map(::toPlace)
            }
        }
    }

    override suspend fun details(
        placeId: String,
        cancellation: CancellationHandle,
    ): Outcome<PlaceDetails> {
        if (cancellation.isCancelled()) {
            return Outcome.Failure(AppError.Cancelled)
        }
        val id = parseId(placeId) ?: return Outcome.Failure(AppError.NotFound)
        return withContext(Dispatchers.Default) {
            runCatchingCore { toDetails(index.details(id)) }
        }
    }

    /** Releases the native handles; the provider is unusable afterwards. */
    fun close() {
        session?.close()
        session = null
        index.close()
    }

    private suspend fun <T> queryWithCancellation(
        cancellation: CancellationHandle,
        call: (CancelToken) -> T,
    ): Outcome<T> {
        val token = CancelToken()
        return coroutineScope {
            val watcher =
                launch {
                    while (isActive) {
                        if (cancellation.isCancelled()) {
                            token.cancel()
                            return@launch
                        }
                        delay(CANCEL_POLL_MILLIS)
                    }
                }
            try {
                Outcome.Success(call(token))
            } catch (error: CoreException) {
                Outcome.Failure(error.toAppError())
            } catch (error: LinkageError) {
                Outcome.Failure(AppError.Internal(NATIVE_LINKAGE_ERROR))
            } finally {
                watcher.cancel()
                token.close()
            }
        }
    }

    private inline fun <T> runCatchingCore(call: () -> T): Outcome<T> =
        try {
            Outcome.Success(call())
        } catch (error: CoreException) {
            Outcome.Failure(error.toAppError())
        } catch (error: LinkageError) {
            Outcome.Failure(AppError.Internal(NATIVE_LINKAGE_ERROR))
        }

    private fun toHit(hit: CorePlaceHit): PlaceHit =
        PlaceHit(
            id = idOf(hit.id.toInt()),
            name = hit.name,
            kind = hit.kind,
            position = GeoPoint(hit.latitude, hit.longitude),
            address = hit.context.ifBlank { null },
            regionBuild = regionBuild,
            score = hit.score,
            brand = hit.brand?.takeIf { it.isNotBlank() },
        )

    private fun toDetails(detail: PlaceDetail): PlaceDetails =
        PlaceDetails(
            place = toPlace(detail),
            osmType = detail.osmType?.toOsmType(),
            osmId = detail.osmId,
            tags = detail.tags,
        )

    private fun toPlace(place: PlaceDetail): Place =
        Place(
            id = idOf(place.id.toInt()),
            name = place.name,
            kind = place.kind,
            position = GeoPoint(place.latitude, place.longitude),
            address = place.context.ifBlank { null },
            regionBuild = regionBuild,
        )

    private fun idOf(index: Int): String = "$regionBuild$ID_SEPARATOR$index"

    private fun parseId(placeId: String): UInt? {
        val separator = placeId.lastIndexOf(ID_SEPARATOR)
        if (separator <= 0 || placeId.substring(0, separator) != regionBuild) {
            return null
        }
        return placeId.substring(separator + 1).toUIntOrNull()
    }

    private fun isValid(bbox: BoundingBox): Boolean =
        bbox.southWest.latitude.isFinite() &&
            bbox.southWest.longitude.isFinite() &&
            bbox.northEast.latitude.isFinite() &&
            bbox.northEast.longitude.isFinite() &&
            bbox.southWest.latitude in -MAX_LATITUDE..MAX_LATITUDE &&
            bbox.northEast.latitude in -MAX_LATITUDE..MAX_LATITUDE &&
            bbox.southWest.longitude in -MAX_LONGITUDE..MAX_LONGITUDE &&
            bbox.northEast.longitude in -MAX_LONGITUDE..MAX_LONGITUDE &&
            bbox.southWest.latitude <= bbox.northEast.latitude &&
            bbox.southWest.longitude <= bbox.northEast.longitude

    companion object {
        /**
         * Opens [indexDirectory] off Main. A missing or corrupt section is a
         * typed failure, never a crash. [regionBuild] is the identity the caller
         * wants on every hit.
         */
        suspend fun open(
            indexDirectory: File,
            regionBuild: String,
            log: (String) -> Unit = {},
        ): Outcome<RustPlaceProvider> =
            withContext(Dispatchers.IO) {
                if (!indexDirectory.isDirectory) {
                    return@withContext Outcome.Failure(AppError.NotReady)
                }
                try {
                    val index = SearchIndex.open(indexDirectory.absolutePath)
                    log(
                        "search index $regionBuild opened: ${index.placeCount()} places " +
                            "from ${indexDirectory.absolutePath}",
                    )
                    Outcome.Success(RustPlaceProvider(index, regionBuild))
                } catch (error: CoreException) {
                    log("search index $regionBuild failed: ${error.message}")
                    Outcome.Failure(error.toAppError())
                } catch (error: LinkageError) {
                    log("search index $regionBuild failed to link: ${error.message}")
                    Outcome.Failure(AppError.Internal(NATIVE_LINKAGE_ERROR))
                }
            }
    }
}

private fun CoreException.toAppError(): AppError =
    when (this) {
        is CoreException.Io -> AppError.NotReady
        is CoreException.CorruptData -> AppError.CorruptData
        is CoreException.InvalidInput -> AppError.InvalidInput
        is CoreException.NotFound -> AppError.NotFound
        is CoreException.Cancelled -> AppError.Cancelled
        is CoreException.NotReady -> AppError.NotReady
        is CoreException.Unsupported -> AppError.Unsupported
    }

private fun OsmElementType.toOsmType(): OsmType =
    when (this) {
        OsmElementType.NODE -> OsmType.NODE
        OsmElementType.WAY -> OsmType.WAY
        OsmElementType.RELATION -> OsmType.RELATION
    }
