package nl.r1a.grassmap.app.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmap.core.services.SearchHistorySettings
import nl.r1a.grassmap.core.services.backup.BackupKinds
import nl.r1a.grassmap.core.services.backup.BundleEntry
import nl.r1a.grassmap.core.services.backup.BundleKindSource
import nl.r1a.grassmap.core.services.backup.sha256Hex
import nl.r1a.grassmap.places.SearchHistoryEntry
import nl.r1a.grassmap.places.SearchHistoryStore
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.RouteHistoryEntry
import nl.r1a.grassmap.plugin.api.model.RouteHistoryRules
import nl.r1a.grassmap.routing.RouteHistoryStore

/** The last searches, one entry per query. */
internal class SearchHistoryBundleKind(
    private val store: SearchHistoryStore,
    private val settings: SearchHistorySettings,
) : BundleKindSource {
    override val kind: String = BackupKinds.SEARCH_HISTORY

    override suspend fun count(): Outcome<Int> =
        when (val listed = store.list()) {
            is Outcome.Success -> Outcome.Success(listed.value.size)
            is Outcome.Failure -> listed
        }

    override suspend fun read(): Outcome<List<BundleEntry>> =
        when (val listed = store.list()) {
            is Outcome.Success -> {
                Outcome.Success(
                    listed.value.map { entry -> BundleEntry(entry.query, valueOf(entry)) },
                )
            }

            is Outcome.Failure -> {
                listed
            }
        }

    override suspend fun apply(entries: List<BundleEntry>): Outcome<Unit> {
        if (!settings.isEnabled()) {
            return Outcome.Failure(AppError.PermissionDenied)
        }
        val existing =
            when (val listed = store.list()) {
                is Outcome.Success -> listed.value
                is Outcome.Failure -> return listed
            }
        val merged = existing.toMutableList()
        for (entry in entries) {
            val incoming = entryOf(entry) ?: return Outcome.Failure(AppError.InvalidInput)
            val index = merged.indexOfFirst { current -> current.query == incoming.query }
            if (index >= 0) merged[index] = incoming else merged += incoming
        }
        return store.restore(merged)
    }

    private fun valueOf(entry: SearchHistoryEntry): JsonObject =
        buildJsonObject {
            put(QUERY_KEY, entry.query)
            put(INDEX_KEY, entry.index)
            put(LIMIT_KEY, entry.limit)
            put(AT_MILLIS_KEY, entry.atMillis)
            entry.viewport?.let { box ->
                put(
                    VIEWPORT_KEY,
                    buildJsonObject {
                        put(SOUTH_KEY, box.southWest.latitude)
                        put(WEST_KEY, box.southWest.longitude)
                        put(NORTH_KEY, box.northEast.latitude)
                        put(EAST_KEY, box.northEast.longitude)
                    },
                )
            }
            entry.position?.let { position ->
                put(
                    POSITION_KEY,
                    buildJsonObject {
                        put(LATITUDE_KEY, position.latitude)
                        put(LONGITUDE_KEY, position.longitude)
                    },
                )
            }
            entry.language?.let { put(LANGUAGE_KEY, it) }
        }

    private fun entryOf(entry: BundleEntry): SearchHistoryEntry? {
        val value = entry.value
        val index = value.stringValue(INDEX_KEY) ?: return null
        val limit = value.intValue(LIMIT_KEY) ?: return null
        val atMillis = value.longValue(AT_MILLIS_KEY) ?: return null
        val viewport =
            when (val box = value[VIEWPORT_KEY] as? JsonObject) {
                null -> {
                    null
                }

                else -> {
                    val south = box.doubleValue(SOUTH_KEY) ?: return null
                    val west = box.doubleValue(WEST_KEY) ?: return null
                    val north = box.doubleValue(NORTH_KEY) ?: return null
                    val east = box.doubleValue(EAST_KEY) ?: return null
                    BoundingBox(GeoPoint(south, west), GeoPoint(north, east))
                }
            }
        val position =
            when (val point = value[POSITION_KEY] as? JsonObject) {
                null -> {
                    null
                }

                else -> {
                    val latitude = point.doubleValue(LATITUDE_KEY) ?: return null
                    val longitude = point.doubleValue(LONGITUDE_KEY) ?: return null
                    GeoPoint(latitude, longitude)
                }
            }
        return SearchHistoryEntry(
            query = value.stringValue(QUERY_KEY) ?: entry.id,
            index = index,
            limit = limit,
            atMillis = atMillis,
            viewport = viewport,
            position = position,
            language = value.stringValue(LANGUAGE_KEY),
        )
    }

    private companion object {
        const val QUERY_KEY = "query"
        const val INDEX_KEY = "index"
        const val LIMIT_KEY = "limit"
        const val AT_MILLIS_KEY = "atMillis"
        const val VIEWPORT_KEY = "viewport"
        const val POSITION_KEY = "position"
        const val LANGUAGE_KEY = "language"
        const val SOUTH_KEY = "south"
        const val WEST_KEY = "west"
        const val NORTH_KEY = "north"
        const val EAST_KEY = "east"
        const val LATITUDE_KEY = "lat"
        const val LONGITUDE_KEY = "lon"
    }
}

/** The last planned routes, one entry per waypoint chain. */
internal class RouteHistoryBundleKind(
    private val store: RouteHistoryStore,
    private val settings: SearchHistorySettings,
) : BundleKindSource {
    override val kind: String = BackupKinds.ROUTE_HISTORY

    override suspend fun count(): Outcome<Int> =
        when (val listed = store.recent()) {
            is Outcome.Success -> Outcome.Success(listed.value.size)
            is Outcome.Failure -> listed
        }

    override suspend fun read(): Outcome<List<BundleEntry>> =
        when (val listed = store.recent()) {
            is Outcome.Success -> {
                Outcome.Success(
                    listed.value.map { entry ->
                        BundleEntry(
                            chainId(entry),
                            Json.encodeToJsonElement(RouteHistoryEntry.serializer(), entry) as JsonObject,
                        )
                    },
                )
            }

            is Outcome.Failure -> {
                listed
            }
        }

    override suspend fun apply(entries: List<BundleEntry>): Outcome<Unit> {
        if (!settings.isEnabled()) {
            return Outcome.Failure(AppError.PermissionDenied)
        }
        val existing =
            when (val listed = store.recent()) {
                is Outcome.Success -> listed.value
                is Outcome.Failure -> return listed
            }
        val merged = existing.toMutableList()
        for (entry in entries) {
            val incoming =
                try {
                    Json.decodeFromString(RouteHistoryEntry.serializer(), entry.value.toString())
                } catch (exception: IllegalArgumentException) {
                    return Outcome.Failure(AppError.InvalidInput)
                }
            val index = merged.indexOfFirst { current -> RouteHistoryRules.sameChain(current, incoming) }
            if (index >= 0) merged[index] = incoming else merged += incoming
        }
        return store.restore(merged)
    }

    /** The chain is the identity: the same points in the same order are one entry. */
    private fun chainId(entry: RouteHistoryEntry): String =
        sha256Hex(
            entry.stops
                .joinToString(separator = "\n") { point ->
                    "${point.position.latitude},${point.position.longitude},${point.label}"
                }.toByteArray(),
        )
}
