package nl.r1a.grassmap.places

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.SearchContext
import nl.r1a.grassmap.plugin.api.service.Clock
import nl.r1a.grassmap.plugin.api.service.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Whether the search history may be written and read (T-0207).
 *
 * This is the seam a feature module can see; the persisted switch lives in
 * `core-services` and `app` wires the two together. It mirrors
 * `DistanceByTapSetting`: the feature asks a predicate, and `app` owns the
 * storage behind it.
 */
fun interface SearchHistorySetting {
    fun isEnabled(): Boolean
}

/**
 * One remembered search and the exact context it ran with, so it can be replayed
 * by passing [context] back to `PlaceProvider.search` unchanged.
 *
 * [atMillis] is the injected clock's time when the search completed; it is the
 * only ordering key, and the store keeps the file order for equal values.
 */
data class SearchHistoryEntry(
    val query: String,
    val index: String,
    val limit: Int,
    val atMillis: Long,
    val viewport: BoundingBox? = null,
    val position: GeoPoint? = null,
    val language: String? = null,
) {
    /** The [SearchContext] a replay of this entry passes to the provider. */
    fun context(): SearchContext =
        SearchContext(
            viewport = viewport,
            language = language,
            position = position,
        )
}

/**
 * The last [maxEntries] searches, newest first, in one app-private JSON file
 * (T-0207).
 *
 * A "search" is a terminal keystroke the search screen completed, not every
 * letter on the way there: the newest entry is replaced while the next query
 * extends or shortens it, so typing "lviv" leaves one row, not four. Repeating
 * a query moves it to the front with a new time, and its replay context is the
 * one the repeat carried.
 *
 * The file is written through a temporary sibling, fsynced and moved
 * atomically, so a failed write leaves the previous list in place. The
 * constructor does no I/O; [remember], [list] and [clear] are the lifecycle.
 *
 * History is disposable data, not a document the user authored. A missing or
 * unreadable file is an empty history, and the next search replaces a corrupt
 * one. A file whose format is newer than [FORMAT_VERSION] is not overwritten:
 * both [remember] and [list] return [AppError.Unsupported] until the app is
 * updated, following the repository's rule for newer schemas.
 *
 * With the [setting] off, [remember] is a no-op that does not even read the
 * file, and [list] is empty without touching it. [clear] is an explicit user
 * action and always empties the store, so history can be erased while it is
 * off.
 */
class SearchHistoryStore(
    private val file: File? = null,
    private val setting: SearchHistorySetting = SearchHistorySetting { true },
    private val clock: Clock = SystemClock,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val mutex = Mutex()

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    /**
     * Records one completed search. A blank or malformed search is
     * [AppError.InvalidInput]; with history off the result is success and the
     * file is not read or written.
     */
    suspend fun remember(
        query: String,
        index: String = SearchScreenSession.DEFAULT_INDEX,
        limit: Int = SearchScreenSession.DEFAULT_LIMIT,
        context: SearchContext = SearchContext(),
    ): Outcome<Unit> =
        mutex.withLock {
            if (!setting.isEnabled()) {
                return@withLock Outcome.Success(Unit)
            }
            val text = query.trim()
            if (!isValidSearch(text, index, limit, context)) {
                return@withLock Outcome.Failure(AppError.InvalidInput)
            }
            val stored =
                when (val loaded = readLocked()) {
                    is Outcome.Failure -> return@withLock loaded
                    is Outcome.Success -> loaded.value
                }
            val entry =
                SearchHistoryEntry(
                    query = text,
                    index = index,
                    limit = limit,
                    atMillis = clock.nowMillis(),
                    viewport = context.viewport,
                    position = context.position,
                    language = context.language,
                )
            writeLocked((listOf(entry) + collapsed(stored, text)).take(maxEntries))
        }

    /** The stored searches, newest first. Empty when history is off. */
    suspend fun list(): Outcome<List<SearchHistoryEntry>> =
        mutex.withLock {
            if (!setting.isEnabled()) {
                return@withLock Outcome.Success(emptyList())
            }
            readLocked()
        }

    /**
     * Removes every stored search, including a temporary sibling a crashed
     * write may have left. Works with history off, because erasing history is
     * the user's own action.
     */
    suspend fun clear(): Outcome<Unit> =
        mutex.withLock {
            val target = file ?: return@withLock Outcome.Success(Unit)
            val temporary = File(target.parentFile, target.name + TEMPORARY_SUFFIX)
            try {
                if (temporary.exists() && !temporary.delete()) {
                    return@withLock writeFailure("cannot delete $temporary")
                }
                if (target.exists() && !target.delete()) {
                    return@withLock writeFailure("cannot delete $target")
                }
                Outcome.Success(Unit)
            } catch (exception: SecurityException) {
                writeFailure(exception.message)
            }
        }

    /**
     * The file's entries without the new query's exact matches. The newest
     * remaining entry is dropped too when the new query extends or shortens it,
     * so one typing session keeps one row.
     */
    private fun collapsed(
        entries: List<SearchHistoryEntry>,
        query: String,
    ): List<SearchHistoryEntry> {
        val different = entries.filterNot { entry -> entry.query == query }
        val newest = different.firstOrNull() ?: return different
        val sameSession = query.startsWith(newest.query) || newest.query.startsWith(query)
        return if (sameSession) different.drop(1) else different
    }

    private fun isValidSearch(
        query: String,
        index: String,
        limit: Int,
        context: SearchContext,
    ): Boolean {
        if (query.isEmpty() || query.length > MAX_QUERY_LENGTH) {
            return false
        }
        if (index.isEmpty() || index.length > MAX_INDEX_LENGTH) {
            return false
        }
        if (limit !in 1..MAX_SEARCH_RESULTS) {
            return false
        }
        if (context.viewport?.isValid() == false) {
            return false
        }
        if (context.position?.isValid() == false) {
            return false
        }
        val language = context.language
        return language == null || language.length in 1..MAX_LANGUAGE_LENGTH
    }

    private fun readLocked(): Outcome<List<SearchHistoryEntry>> {
        val target = file ?: return Outcome.Success(emptyList())
        if (!target.isFile) {
            return Outcome.Success(emptyList())
        }
        val text =
            try {
                target.readText()
            } catch (exception: IOException) {
                return Outcome.Success(emptyList())
            } catch (exception: SecurityException) {
                return Outcome.Success(emptyList())
            }
        val root =
            try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (exception: SerializationException) {
                null
            } ?: return Outcome.Success(emptyList())
        val format = (root[FORMAT] as? JsonPrimitive)?.intOrNull ?: return Outcome.Success(emptyList())
        if (format > FORMAT_VERSION) {
            return Outcome.Failure(AppError.Unsupported)
        }
        if (format != FORMAT_VERSION) {
            return Outcome.Success(emptyList())
        }
        val entries = parseEntries(root[ENTRIES]) ?: return Outcome.Success(emptyList())
        return Outcome.Success(
            entries
                .distinctBy { entry -> entry.query }
                .sortedByDescending { entry -> entry.atMillis }
                .take(maxEntries),
        )
    }

    private fun parseEntries(element: JsonElement?): List<SearchHistoryEntry>? {
        val array = element as? JsonArray ?: return null
        val entries = mutableListOf<SearchHistoryEntry>()
        for (item in array) {
            entries += parseEntry(item as? JsonObject ?: return null) ?: return null
        }
        return entries
    }

    private fun parseEntry(json: JsonObject): SearchHistoryEntry? {
        val query = stringOf(json[QUERY]) ?: return null
        val index = stringOf(json[INDEX]) ?: return null
        val limit = (json[LIMIT] as? JsonPrimitive)?.intOrNull ?: return null
        val atMillis = (json[AT_MILLIS] as? JsonPrimitive)?.longOrNull ?: return null
        val viewport =
            when (val element = json[VIEWPORT]) {
                null -> null
                is JsonObject -> parseBox(element) ?: return null
                else -> return null
            }
        val position =
            when (val element = json[POSITION]) {
                null -> null
                is JsonObject -> parsePoint(element) ?: return null
                else -> return null
            }
        val language =
            when (val element = json[LANGUAGE]) {
                null -> null
                else -> stringOf(element) ?: return null
            }
        val entry =
            SearchHistoryEntry(
                query = query.trim(),
                index = index,
                limit = limit,
                atMillis = atMillis,
                viewport = viewport,
                position = position,
                language = language,
            )
        if (atMillis < 0 || !isValidSearch(query.trim(), index, limit, entry.context())) {
            return null
        }
        return entry
    }

    private fun parseBox(json: JsonObject): BoundingBox? {
        val south = (json[SOUTH] as? JsonPrimitive)?.doubleOrNull ?: return null
        val west = (json[WEST] as? JsonPrimitive)?.doubleOrNull ?: return null
        val north = (json[NORTH] as? JsonPrimitive)?.doubleOrNull ?: return null
        val east = (json[EAST] as? JsonPrimitive)?.doubleOrNull ?: return null
        return BoundingBox(GeoPoint(south, west), GeoPoint(north, east)).takeIf { it.isValid() }
    }

    private fun parsePoint(json: JsonObject): GeoPoint? {
        val latitude = (json[LATITUDE] as? JsonPrimitive)?.doubleOrNull ?: return null
        val longitude = (json[LONGITUDE] as? JsonPrimitive)?.doubleOrNull ?: return null
        return GeoPoint(latitude, longitude).takeIf { it.isValid() }
    }

    /** A JSON string value; any other shape, including a number, is null. */
    private fun stringOf(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content

    private fun writeLocked(entries: List<SearchHistoryEntry>): Outcome<Unit> {
        val target = file ?: return Outcome.Success(Unit)
        val parent = target.parentFile ?: return writeFailure("no parent directory")
        val temporary = File(parent, target.name + TEMPORARY_SUFFIX)
        return try {
            if (!parent.isDirectory && !parent.mkdirs()) {
                return writeFailure("cannot create $parent")
            }
            FileOutputStream(temporary).use { stream ->
                stream.write(encode(entries).toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            Outcome.Success(Unit)
        } catch (exception: IOException) {
            temporary.delete()
            writeFailure(exception.message)
        } catch (exception: SecurityException) {
            temporary.delete()
            writeFailure(exception.message)
        }
    }

    private fun encode(entries: List<SearchHistoryEntry>): String =
        buildJsonObject {
            put(FORMAT, FORMAT_VERSION)
            put(
                ENTRIES,
                buildJsonArray {
                    entries.forEach { entry -> add(encodeEntry(entry)) }
                },
            )
        }.toString()

    private fun encodeEntry(entry: SearchHistoryEntry): JsonObject =
        buildJsonObject {
            put(QUERY, entry.query)
            put(INDEX, entry.index)
            put(LIMIT, entry.limit)
            put(AT_MILLIS, entry.atMillis)
            entry.viewport?.let { box ->
                put(
                    VIEWPORT,
                    buildJsonObject {
                        put(SOUTH, box.southWest.latitude)
                        put(WEST, box.southWest.longitude)
                        put(NORTH, box.northEast.latitude)
                        put(EAST, box.northEast.longitude)
                    },
                )
            }
            entry.position?.let { point ->
                put(
                    POSITION,
                    buildJsonObject {
                        put(LATITUDE, point.latitude)
                        put(LONGITUDE, point.longitude)
                    },
                )
            }
            entry.language?.let { language -> put(LANGUAGE, language) }
        }

    private fun GeoPoint.isValid(): Boolean =
        latitude.isFinite() &&
            longitude.isFinite() &&
            latitude in MIN_LATITUDE..MAX_LATITUDE &&
            longitude in MIN_LONGITUDE..MAX_LONGITUDE

    private fun writeFailure(detail: String?): Outcome.Failure = Outcome.Failure(AppError.Internal(ERROR_ID, detail))

    companion object {
        /**
         * The number of searches the history keeps. A list of 20 is about a few
         * days of real use and reads without paging; it bounds the file at
         * roughly 7 kB even when every query and context is at its widest.
         */
        const val DEFAULT_MAX_ENTRIES: Int = 20

        /** The widest query text the store accepts, in characters. */
        const val MAX_QUERY_LENGTH: Int = 256

        /** The version of the file this build writes and reads. */
        private const val FORMAT_VERSION = 1

        private const val FORMAT = "format"
        private const val ENTRIES = "entries"
        private const val QUERY = "query"
        private const val INDEX = "index"
        private const val LIMIT = "limit"
        private const val AT_MILLIS = "atMillis"
        private const val VIEWPORT = "viewport"
        private const val POSITION = "position"
        private const val LANGUAGE = "language"
        private const val SOUTH = "south"
        private const val WEST = "west"
        private const val NORTH = "north"
        private const val EAST = "east"
        private const val LATITUDE = "lat"
        private const val LONGITUDE = "lon"

        private const val MAX_INDEX_LENGTH = 64
        private const val MAX_LANGUAGE_LENGTH = 32
        private const val TEMPORARY_SUFFIX = ".tmp"
        private const val ERROR_ID = "places.search_history"
        private const val MIN_LATITUDE = -90.0
        private const val MAX_LATITUDE = 90.0
        private const val MIN_LONGITUDE = -180.0
        private const val MAX_LONGITUDE = 180.0
    }
}
