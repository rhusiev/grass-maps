package nl.r1a.grassmap.core.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.BuiltInProfiles
import nl.r1a.grassmap.plugin.api.model.ProfileId
import nl.r1a.grassmap.plugin.api.provider.RouteOptions
import nl.r1a.grassmap.plugin.api.provider.RoutePreference
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.service.ProfileSettingsGroup
import nl.r1a.grassmap.plugin.api.service.ProfileValueStore
import nl.r1a.grassmap.plugin.api.service.ProfileValues
import java.io.File
import java.io.IOException

/**
 * The per-profile routing options (T-0308), all defaults until the user sets
 * one. A profile with no stored entry reads as [RouteOptions], so a change to
 * bike cannot change what walk receives.
 *
 * The store is keyed by [ProfileId] under the per-profile contract of
 * architecture section 20. Each profile holds the stable keys `hills`,
 * `busy_roads`, `rough_surfaces`, `steps` and `trail_difficulty` with the
 * stable values of [RoutePreference]; only non-default profiles are stored. The
 * [RouteProfile] overloads read and write the built-in profile that carries
 * that route profile, until the UI chooses a profile itself.
 *
 * The schema is version [SCHEMA_VERSION] in `profiles/routing-options.json`.
 * [open] reads the unversioned file of T-0308 and T-0421, `settings/routing-options.json`,
 * forward and removes it once the new file reads back equal; that file may
 * still carry the two-state `hilliness` key (`avoid_hills` becomes `avoid`,
 * `default` becomes `normal`) and omit `steps` and `trail_difficulty`, which
 * read as `normal`.
 *
 * Construction reads the store, so resolve it off the Main thread. A missing,
 * unreadable or corrupt store reads as defaults, because a routing preference
 * must not stop the planner. A store written by a newer build is refused: it
 * reads as defaults and is never overwritten, because every write fails.
 */
class RoutingOptionsSettings(
    private val store: ProfileValueStore? = null,
) : ProfileSettingsGroup {
    private val state = MutableStateFlow(load(store))

    override val id: String = ProfileSettingsGroup.ROUTING

    /** The options of every profile that differs from the defaults. */
    val options: StateFlow<Map<ProfileId, RouteOptions>> = state.asStateFlow()

    /** The options for [profile], defaults when the user never set any. */
    fun forProfile(profile: ProfileId): RouteOptions = state.value[profile] ?: RouteOptions()

    /** The options of the built-in profile that carries [profile]. */
    fun forProfile(profile: RouteProfile): RouteOptions = forProfile(BuiltInProfiles.idOf(profile))

    /**
     * Stores one profile's options; the other profiles keep their values. The
     * options change in memory only when the write succeeded, so the planner
     * never uses a value that is not on disk.
     */
    @Synchronized
    fun set(
        profile: ProfileId,
        options: RouteOptions,
    ): Outcome<Unit> {
        val next = if (options.isDefault) state.value - profile else state.value + (profile to options)
        val written = store?.write(encode(next)) ?: Outcome.Success(Unit)
        if (written is Outcome.Success) {
            state.value = next
        }
        return written
    }

    /** Stores the options of the built-in profile that carries [profile]. */
    fun set(
        profile: RouteProfile,
        options: RouteOptions,
    ): Outcome<Unit> = set(BuiltInProfiles.idOf(profile), options)

    override fun read(profile: ProfileId): Map<String, String> = state.value[profile]?.let(::encodeOptions).orEmpty()

    override fun replace(
        profile: ProfileId,
        values: Map<String, String>,
    ): Outcome<Unit> = set(profile, decode(values))

    companion object {
        const val SCHEMA_VERSION = 1
        private const val ERROR_ID = "core.services.routing_options_settings"
        private const val HILLS_KEY = "hills"
        private const val BUSY_ROADS_KEY = "busy_roads"
        private const val ROUGH_SURFACES_KEY = "rough_surfaces"
        private const val STEPS_KEY = "steps"
        private const val TRAIL_DIFFICULTY_KEY = "trail_difficulty"
        private const val LEGACY_PROFILES_KEY = "profiles"

        /** The T-0308 key and values, read so an existing choice is not lost. */
        private const val LEGACY_HILLINESS_KEY = "hilliness"
        private const val LEGACY_AVOID_HILLS_NAME = "avoid_hills"

        /** Opens the store at [file], migrating [legacyFile] into it when that still exists. */
        fun open(
            file: File,
            legacyFile: File,
        ): RoutingOptionsSettings =
            RoutingOptionsSettings(
                ProfileValueFile(file, SCHEMA_VERSION, ERROR_ID, LegacyProfileFile(legacyFile, ::readLegacy)),
            )

        private fun load(store: ProfileValueStore?): Map<ProfileId, RouteOptions> =
            ((store?.read() as? Outcome.Success)?.value ?: emptyMap())
                .mapValues { (_, values) -> decode(values) }
                .filterValues { !it.isDefault }

        private fun encode(options: Map<ProfileId, RouteOptions>): ProfileValues = options.mapValues { (_, value) -> encodeOptions(value) }

        private fun encodeOptions(value: RouteOptions): Map<String, String> =
            mapOf(
                HILLS_KEY to value.hills.serializedValue,
                BUSY_ROADS_KEY to value.busyRoads.serializedValue,
                ROUGH_SURFACES_KEY to value.roughSurfaces.serializedValue,
                STEPS_KEY to value.steps.serializedValue,
                TRAIL_DIFFICULTY_KEY to value.trailDifficulty.serializedValue,
            )

        private fun decode(values: Map<String, String>): RouteOptions =
            RouteOptions(
                hills = preference(values[HILLS_KEY]),
                busyRoads = preference(values[BUSY_ROADS_KEY]),
                roughSurfaces = preference(values[ROUGH_SURFACES_KEY]),
                steps = preference(values[STEPS_KEY]),
                trailDifficulty = preference(values[TRAIL_DIFFICULTY_KEY]),
            )

        private fun preference(name: String?): RoutePreference = RoutePreference.ofSerialized(name) ?: RoutePreference.Normal

        /** The step stored under [key], [RoutePreference.Normal] when absent or unknown. */
        private fun JsonObject.preference(key: String): String = preference((this[key] as? JsonPrimitive)?.contentOrNull).serializedValue

        /** Reads the T-0308 key when the three-step key is absent. */
        private fun legacyHills(entry: JsonObject): String {
            val hills = (entry[HILLS_KEY] as? JsonPrimitive)?.contentOrNull
            if (hills != null) {
                return preference(hills).serializedValue
            }
            val legacy = (entry[LEGACY_HILLINESS_KEY] as? JsonPrimitive)?.contentOrNull
            return (if (legacy == LEGACY_AVOID_HILLS_NAME) RoutePreference.Avoid else RoutePreference.Normal).serializedValue
        }

        /**
         * Reads the unversioned file of T-0308 and T-0421 as profile values, or
         * null for a file that is missing or not that file. Entries under an
         * unknown profile name are dropped, as they always were.
         */
        fun readLegacy(file: File): ProfileValues? {
            if (!file.isFile) {
                return null
            }
            return try {
                val root = Json.parseToJsonElement(file.readText()) as? JsonObject ?: return null
                val profiles = root[LEGACY_PROFILES_KEY] as? JsonObject ?: return null
                val options = mutableMapOf<ProfileId, RouteOptions>()
                for ((name, element) in profiles) {
                    val id = ProfileId(name)
                    if (BuiltInProfiles.routeProfileOf(id) == null) {
                        continue
                    }
                    val entry = element as? JsonObject ?: continue
                    val values = legacyValues(entry)
                    if (!decode(values).isDefault) {
                        options[id] = decode(values)
                    }
                }
                encode(options)
            } catch (exception: IOException) {
                null
            } catch (exception: SerializationException) {
                null
            } catch (exception: SecurityException) {
                null
            }
        }

        private fun legacyValues(entry: JsonObject): Map<String, String> =
            mapOf(
                HILLS_KEY to legacyHills(entry),
                BUSY_ROADS_KEY to entry.preference(BUSY_ROADS_KEY),
                ROUGH_SURFACES_KEY to entry.preference(ROUGH_SURFACES_KEY),
                STEPS_KEY to entry.preference(STEPS_KEY),
                TRAIL_DIFFICULTY_KEY to entry.preference(TRAIL_DIFFICULTY_KEY),
            )
    }
}
