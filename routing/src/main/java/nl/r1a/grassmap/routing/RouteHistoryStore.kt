package nl.r1a.grassmap.routing

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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.RouteHistoryAccess
import nl.r1a.grassmap.plugin.api.model.RouteHistoryEntry
import nl.r1a.grassmap.plugin.api.model.RouteHistoryRules
import nl.r1a.grassmap.plugin.api.model.RoutePlanPoint
import nl.r1a.grassmap.plugin.api.service.Clock
import nl.r1a.grassmap.plugin.api.service.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Whether the planning history may be written and read (T-0304).
 *
 * This is the seam a feature module sees; the persisted switch is the same one
 * the search history uses, and `app` wires the two together. Sharing the switch
 * is deliberate: "keep local history" is one privacy decision about where the
 * user went and what they looked for, and a user who turned it off once should
 * not have to find a second switch.
 */
fun interface RouteHistorySetting {
    fun isEnabled(): Boolean
}

/**
 * The last [maxEntries] routes the planner produced, newest first, in one
 * app-private JSON file (T-0304).
 *
 * A "used route" is a route the planner produced and drew, because the Start
 * action is still inert until guidance lands; the entry keeps the waypoint
 * chain, not the geometry, so a reuse is routed again against the current map
 * data. Repeating a chain moves it to the front with a new time.
 *
 * The privacy discipline is the search history's: with the setting off
 * [remember] does not read or write the file, [recent] returns empty without
 * touching it, and the file stays byte identical. [clear] is an explicit user
 * action and always removes the file, so history can be erased while it is off.
 *
 * The file is written through a temporary sibling, fsynced and moved atomically.
 * A missing or unreadable file is an empty history and the next route replaces a
 * corrupt one; a file whose format is newer than [FORMAT_VERSION] is not
 * overwritten, and both [remember] and [recent] refuse with
 * [AppError.Unsupported]. The constructor does no I/O.
 */
class RouteHistoryStore(
    private val file: File? = null,
    private val setting: RouteHistorySetting = RouteHistorySetting { true },
    private val clock: Clock = SystemClock,
    private val maxEntries: Int = RouteHistoryRules.DEFAULT_MAX_ENTRIES,
) : RouteHistoryAccess {
    private val mutex = Mutex()

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    override suspend fun remember(entry: RouteHistoryEntry): Outcome<List<RouteHistoryEntry>> =
        mutex.withLock {
            if (!setting.isEnabled()) {
                return@withLock Outcome.Success(emptyList())
            }
            if (!isValid(entry)) {
                return@withLock Outcome.Failure(AppError.InvalidInput)
            }
            val stored =
                when (val loaded = readLocked()) {
                    is Outcome.Failure -> return@withLock loaded
                    is Outcome.Success -> loaded.value
                }
            val timed = entry.copy(atMillis = clock.nowMillis())
            val next = listOf(timed) + stored.filterNot { existing -> RouteHistoryRules.sameChain(existing, timed) }
            val bounded = next.take(maxEntries)
            when (val written = writeLocked(bounded)) {
                is Outcome.Failure -> written
                is Outcome.Success -> Outcome.Success(bounded)
            }
        }

    override suspend fun recent(): Outcome<List<RouteHistoryEntry>> =
        mutex.withLock {
            if (!setting.isEnabled()) {
                return@withLock Outcome.Success(emptyList())
            }
            readLocked()
        }

    override suspend fun clear(): Outcome<Unit> =
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

    private fun isValid(entry: RouteHistoryEntry): Boolean {
        if (entry.atMillis < 0L) {
            return false
        }
        if (entry.stops.size < MIN_STOPS || entry.stops.size > MAX_STOPS) {
            return false
        }
        if (!entry.distanceMeters.isFinite() || entry.distanceMeters < 0.0) {
            return false
        }
        if (!entry.durationSeconds.isFinite() || entry.durationSeconds < 0.0) {
            return false
        }
        return entry.stops.all(::isValidPoint)
    }

    private fun isValidPoint(point: RoutePlanPoint): Boolean {
        val position = point.position
        return position.latitude.isFinite() &&
            position.longitude.isFinite() &&
            position.latitude in MIN_LATITUDE..MAX_LATITUDE &&
            position.longitude in MIN_LONGITUDE..MAX_LONGITUDE &&
            point.label.length <= MAX_LABEL_LENGTH
    }

    private fun readLocked(): Outcome<List<RouteHistoryEntry>> {
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
        return Outcome.Success(entries.take(maxEntries))
    }

    private fun parseEntries(element: JsonElement?): List<RouteHistoryEntry>? {
        val array = element as? JsonArray ?: return null
        val entries = mutableListOf<RouteHistoryEntry>()
        for (item in array) {
            val entry =
                try {
                    Json.decodeFromString(RouteHistoryEntry.serializer(), item.toString())
                } catch (exception: SerializationException) {
                    return null
                } catch (exception: IllegalArgumentException) {
                    return null
                }
            if (!isValid(entry)) {
                return null
            }
            entries += entry
        }
        return entries
    }

    private fun writeLocked(entries: List<RouteHistoryEntry>): Outcome<Unit> {
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

    private fun encode(entries: List<RouteHistoryEntry>): String =
        buildJsonObject {
            put(FORMAT, FORMAT_VERSION)
            put(
                ENTRIES,
                buildJsonArray {
                    entries.forEach { entry -> add(Json.encodeToJsonElement(RouteHistoryEntry.serializer(), entry)) }
                },
            )
        }.toString()

    private fun writeFailure(detail: String?): Outcome.Failure = Outcome.Failure(AppError.Internal(ERROR_ID, detail))

    private companion object {
        /** The version of the file this build writes and reads. */
        private const val FORMAT_VERSION = 1

        private const val FORMAT = "format"
        private const val ENTRIES = "entries"
        private const val TEMPORARY_SUFFIX = ".tmp"
        private const val ERROR_ID = "routing.route_history"
        private const val MIN_STOPS = 2
        private const val MAX_STOPS = 26
        private const val MAX_LABEL_LENGTH = 256
        private const val MIN_LATITUDE = -90.0
        private const val MAX_LATITUDE = 90.0
        private const val MIN_LONGITUDE = -180.0
        private const val MAX_LONGITUDE = 180.0
    }
}
