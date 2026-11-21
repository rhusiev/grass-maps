package nl.r1a.grassmaps.app.backup

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmaps.alerts.AlertSettingsStore
import nl.r1a.grassmaps.app.MapStyleSettings
import nl.r1a.grassmaps.core.PluginHost
import nl.r1a.grassmaps.core.services.OnlineSearchSettings
import nl.r1a.grassmaps.core.services.ProfileCatalog
import nl.r1a.grassmaps.core.services.ProfileEntry
import nl.r1a.grassmaps.core.services.RoutingOptionsSettings
import nl.r1a.grassmaps.core.services.SearchHistorySettings
import nl.r1a.grassmaps.core.services.backup.BackupKinds
import nl.r1a.grassmaps.core.services.backup.BundleEntry
import nl.r1a.grassmaps.core.services.backup.BundleKindSource
import nl.r1a.grassmaps.plugin.api.contract.PluginId
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.model.BuiltInProfiles
import nl.r1a.grassmaps.plugin.api.model.ProfileId
import nl.r1a.grassmaps.plugin.api.provider.RouteOptions
import nl.r1a.grassmaps.plugin.api.provider.RoutePreference
import nl.r1a.grassmaps.plugin.api.provider.RouteProfile
import nl.r1a.grassmaps.plugins.layers.panel.MapStyleChoice

/** The profile catalog, one entry per profile id. */
internal class ProfileBundleKind(
    private val catalog: ProfileCatalog,
) : BundleKindSource {
    override val kind: String = BackupKinds.PROFILES

    override suspend fun count(): Outcome<Int> = Outcome.Success(catalog.load().size)

    override suspend fun read(): Outcome<List<BundleEntry>> =
        Outcome.Success(
            catalog.load().map { entry -> BundleEntry(entry.id.value, valueOf(entry)) },
        )

    override suspend fun apply(entries: List<BundleEntry>): Outcome<Unit> {
        for (entry in entries) {
            val parsed = entryOf(entry) ?: return Outcome.Failure(AppError.InvalidInput)
            when (val written = catalog.upsert(parsed)) {
                is Outcome.Failure -> return written
                is Outcome.Success -> Unit
            }
        }
        return Outcome.Success(Unit)
    }

    private fun valueOf(entry: ProfileEntry): JsonObject =
        buildJsonObject {
            put(ROUTE_PROFILE_KEY, BuiltInProfiles.idOf(entry.routeProfile).value)
            put(BUILT_IN_KEY, entry.isBuiltIn)
            entry.name?.let { put(NAME_KEY, it) }
            entry.copiedFrom?.let { put(COPIED_FROM_KEY, it.value) }
            if (entry.order > 0L) {
                put(ORDER_KEY, entry.order)
            }
        }

    private fun entryOf(entry: BundleEntry): ProfileEntry? {
        val value = entry.value
        val routeProfileId = value.stringValue(ROUTE_PROFILE_KEY) ?: return null
        val routeProfile =
            RouteProfile.entries.firstOrNull { profile ->
                BuiltInProfiles.idOf(profile).value == routeProfileId
            } ?: return null
        val order = value.longValue(ORDER_KEY) ?: 0L
        if (order < 0L) {
            return null
        }
        return ProfileEntry(
            id = ProfileId(entry.id),
            routeProfile = routeProfile,
            isBuiltIn = value.booleanValue(BUILT_IN_KEY) ?: false,
            name = value.stringValue(NAME_KEY),
            copiedFrom = value.stringValue(COPIED_FROM_KEY)?.let(::ProfileId),
            order = order,
        )
    }

    private companion object {
        const val ROUTE_PROFILE_KEY = "route_profile"
        const val BUILT_IN_KEY = "built_in"
        const val NAME_KEY = "name"
        const val COPIED_FROM_KEY = "copied_from"
        const val ORDER_KEY = "order"
    }
}

/** The per-profile routing preferences, one entry per profile that differs from the defaults. */
internal class RoutingBundleKind(
    private val settings: RoutingOptionsSettings,
) : BundleKindSource {
    override val kind: String = BackupKinds.ROUTING

    override suspend fun count(): Outcome<Int> = Outcome.Success(settings.options.value.size)

    override suspend fun read(): Outcome<List<BundleEntry>> =
        Outcome.Success(
            settings.options.value.map { (profile, options) ->
                BundleEntry(profile.value, valueOf(options))
            },
        )

    override suspend fun apply(entries: List<BundleEntry>): Outcome<Unit> {
        for (entry in entries) {
            val options = optionsOf(entry.value) ?: return Outcome.Failure(AppError.InvalidInput)
            when (val written = settings.set(ProfileId(entry.id), options)) {
                is Outcome.Failure -> return written
                is Outcome.Success -> Unit
            }
        }
        return Outcome.Success(Unit)
    }

    private fun valueOf(options: RouteOptions): JsonObject =
        buildJsonObject {
            put(HILLS_KEY, options.hills.serializedValue)
            put(BUSY_ROADS_KEY, options.busyRoads.serializedValue)
            put(ROUGH_SURFACES_KEY, options.roughSurfaces.serializedValue)
            put(STEPS_KEY, options.steps.serializedValue)
            put(TRAIL_DIFFICULTY_KEY, options.trailDifficulty.serializedValue)
        }

    private fun optionsOf(value: JsonObject): RouteOptions? {
        val hills = preference(value, HILLS_KEY) ?: return null
        val busyRoads = preference(value, BUSY_ROADS_KEY) ?: return null
        val roughSurfaces = preference(value, ROUGH_SURFACES_KEY) ?: return null
        val steps = preference(value, STEPS_KEY) ?: return null
        val trailDifficulty = preference(value, TRAIL_DIFFICULTY_KEY) ?: return null
        return RouteOptions(hills, busyRoads, roughSurfaces, steps, trailDifficulty)
    }

    private fun preference(
        value: JsonObject,
        key: String,
    ): RoutePreference? {
        val name = value.stringValue(key) ?: return RoutePreference.Normal
        return RoutePreference.ofSerialized(name)
    }

    private companion object {
        const val HILLS_KEY = "hills"
        const val BUSY_ROADS_KEY = "busy_roads"
        const val ROUGH_SURFACES_KEY = "rough_surfaces"
        const val STEPS_KEY = "steps"
        const val TRAIL_DIFFICULTY_KEY = "trail_difficulty"
    }
}

/** The per-profile alert switches and channels, one entry per stored profile. */
internal class AlertsBundleKind(
    private val settings: AlertSettingsStore,
) : BundleKindSource {
    override val kind: String = BackupKinds.ALERTS

    override suspend fun count(): Outcome<Int> = Outcome.Success(settings.storedProfiles().size)

    override suspend fun read(): Outcome<List<BundleEntry>> {
        val ready = settings.ensureLoaded()
        if (ready is Outcome.Failure) {
            return ready
        }
        return Outcome.Success(
            settings
                .storedProfiles()
                .sortedBy { profile -> profile.value }
                .map { profile -> BundleEntry(profile.value, jsonOf(settings.read(profile))) },
        )
    }

    override suspend fun apply(entries: List<BundleEntry>): Outcome<Unit> {
        val ready = settings.ensureLoaded()
        if (ready is Outcome.Failure) {
            return ready
        }
        for (entry in entries) {
            val values = mutableMapOf<String, String>()
            for ((key, element) in entry.value) {
                val text =
                    (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: return Outcome.Failure(AppError.InvalidInput)
                values[key] = text
            }
            when (val written = settings.replace(ProfileId(entry.id), values)) {
                is Outcome.Failure -> return written
                is Outcome.Success -> Unit
            }
        }
        return Outcome.Success(Unit)
    }

    private fun jsonOf(values: Map<String, String>): JsonObject = JsonObject(values.mapValues { (_, value) -> JsonPrimitive(value) })
}

/** The two privacy switches that this build persists. */
internal class PrivacySwitchesBundleKind(
    private val searchHistory: SearchHistorySettings,
    private val onlineSearch: OnlineSearchSettings,
) : BundleKindSource {
    override val kind: String = BackupKinds.PRIVACY

    override suspend fun count(): Outcome<Int> = Outcome.Success(2)

    override suspend fun read(): Outcome<List<BundleEntry>> =
        Outcome.Success(
            listOf(
                BundleEntry(SEARCH_HISTORY_ENTRY, buildJsonObject { put(ENABLED_KEY, searchHistory.isEnabled()) }),
                BundleEntry(POSITION_ENTRY, buildJsonObject { put(ALLOWED_KEY, onlineSearch.isPositionAllowed()) }),
            ),
        )

    override suspend fun apply(entries: List<BundleEntry>): Outcome<Unit> {
        for (entry in entries) {
            val written =
                when (entry.id) {
                    SEARCH_HISTORY_ENTRY -> {
                        searchHistory.set(entry.value.booleanValue(ENABLED_KEY) ?: return Outcome.Failure(AppError.InvalidInput))
                    }

                    POSITION_ENTRY -> {
                        onlineSearch.setPositionAllowed(
                            entry.value.booleanValue(ALLOWED_KEY) ?: return Outcome.Failure(AppError.InvalidInput),
                        )
                    }

                    else -> {
                        return Outcome.Failure(AppError.NotFound)
                    }
                }
            if (written is Outcome.Failure) {
                return written
            }
        }
        return Outcome.Success(Unit)
    }

    private companion object {
        const val SEARCH_HISTORY_ENTRY = "search_history"
        const val POSITION_ENTRY = "online_search_position"
        const val ENABLED_KEY = "enabled"
        const val ALLOWED_KEY = "allowed"
    }
}

/** The one map style the layers panel selected. */
internal class MapStyleBundleKind(
    private val settings: MapStyleSettings,
) : BundleKindSource {
    override val kind: String = BackupKinds.MAP_STYLE

    override suspend fun count(): Outcome<Int> = Outcome.Success(1)

    override suspend fun read(): Outcome<List<BundleEntry>> =
        Outcome.Success(listOf(BundleEntry(STYLE_ENTRY, buildJsonObject { put(STYLE_KEY, settings.current().styleId) })))

    override suspend fun apply(entries: List<BundleEntry>): Outcome<Unit> {
        for (entry in entries) {
            if (entry.id != STYLE_ENTRY) {
                return Outcome.Failure(AppError.NotFound)
            }
            val id = entry.value.stringValue(STYLE_KEY) ?: return Outcome.Failure(AppError.InvalidInput)
            val choice = MapStyleChoice.fromStyleId(id) ?: return Outcome.Failure(AppError.Unsupported)
            when (val written = settings.select(choice)) {
                is Outcome.Failure -> return written
                is Outcome.Success -> Unit
            }
        }
        return Outcome.Success(Unit)
    }

    private companion object {
        const val STYLE_ENTRY = "style"
        const val STYLE_KEY = "style"
    }
}

/** The switchable plugin switches, one entry per plugin id. */
internal class PluginSwitchesBundleKind(
    private val host: PluginHost,
    private val switchable: Set<PluginId>,
) : BundleKindSource {
    override val kind: String = BackupKinds.PLUGINS

    override suspend fun count(): Outcome<Int> = Outcome.Success(switchable.size)

    override suspend fun read(): Outcome<List<BundleEntry>> {
        host.load()
        val disabled = host.disabledIds()
        return Outcome.Success(
            switchable
                .sortedBy { id -> id.value }
                .map { id -> BundleEntry(id.value, buildJsonObject { put(ENABLED_KEY, id !in disabled) }) },
        )
    }

    override suspend fun apply(entries: List<BundleEntry>): Outcome<Unit> {
        host.load()
        for (entry in entries) {
            val id = PluginId(entry.id)
            if (id !in switchable) {
                continue
            }
            val enabled = entry.value.booleanValue(ENABLED_KEY) ?: return Outcome.Failure(AppError.InvalidInput)
            when (val written = host.setEnabled(id, enabled)) {
                is Outcome.Failure -> return written
                is Outcome.Success -> Unit
            }
        }
        return Outcome.Success(Unit)
    }

    private companion object {
        const val ENABLED_KEY = "enabled"
    }
}
