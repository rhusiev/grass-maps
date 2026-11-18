package nl.r1a.grassmap.alerts

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.BuiltInProfiles
import nl.r1a.grassmap.plugin.api.model.ProfileId
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.service.ProfileValueStore
import nl.r1a.grassmap.plugin.api.service.ProfileValues
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** The alert switches, the output channels and the chosen place kinds of one profile. */
@Serializable
data class ProfileAlertSettings(
    val settings: AlertSettings = AlertSettings.ALL_OFF,
    val channels: AlertChannels = AlertChannels.ALL_OFF,
    val poiKinds: Set<String> = emptySet(),
) {
    /** Whether this profile still carries the product defaults. */
    val isDefault: Boolean
        get() = this == DEFAULT

    companion object {
        val DEFAULT: ProfileAlertSettings = ProfileAlertSettings()
    }
}

/**
 * The per-profile alert settings (T-0413). A profile stores the ten switches,
 * the three output channels and the place kinds chosen on the alerts page.
 *
 * The store is keyed by [ProfileId] under the per-profile contract of
 * architecture section 20, version [SCHEMA_VERSION] in `profiles/alerts.json`.
 * Each profile holds the stable keys `signal`, `reroute`, `off_route`,
 * `arrival`, `tunnel`, `railway_crossing`, `hazard`, `favourite`,
 * `track_waypoint`, `poi`, `banner`, `sound` and `vibration` with the values
 * `true` or `false`, and `poi_kinds` with a JSON array of the chosen kinds.
 * Only profiles that differ from the defaults are stored. The [RouteProfile]
 * overloads read and write the built-in profile that carries that route
 * profile, until the UI chooses a profile itself.
 *
 * [readLegacy] reads the unversioned `settings/alerts.json` of T-0413 forward
 * into the profile store. A missing, unreadable or corrupt store reads as every
 * profile at its defaults, because an alert setting must never stop
 * navigation. A store written by a newer build makes [load] fail with
 * [nl.r1a.grassmap.plugin.api.error.AppError.NewerSchema], every profile reads
 * as its defaults, and every write fails, so the newer data is never
 * overwritten.
 *
 * The active profile is the profile a trip currently uses. The app activates
 * the trip's profile when guidance starts, so a trip reads that profile's
 * values and never a process-wide reset. A profile change applies to the next
 * alert decision; the latches in [AlertHost] are not reset by it.
 *
 * Construction does not read the store. [load] does, so the app runs it off the
 * Main thread. When [writeScope] is present, writes are coalesced on
 * [writeDispatcher], the IO dispatcher by default: a second change while a write is in flight causes one more write
 * of the latest value instead of a write per change. One write runs at a time
 * and each takes its snapshot when it starts, so the file ends with the newest
 * state.
 */
class AlertSettingsStore(
    private val store: ProfileValueStore? = null,
    initialProfile: RouteProfile = RouteProfile.Walk,
    private val writeScope: CoroutineScope? = null,
    private val log: (String) -> Unit = {},
    private val writeDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val states = mutableMapOf<ProfileId, ProfileAlertSettings>()
    private var activeProfile: ProfileId = BuiltInProfiles.idOf(initialProfile)
    private val activeState = MutableStateFlow(ProfileAlertSettings())
    private val writeRequested = AtomicBoolean(false)
    private val writeLock = Mutex()

    /** The switches, channels and chosen place kinds of the active profile. */
    val active: StateFlow<ProfileAlertSettings> = activeState.asStateFlow()

    /** The profile whose values a running trip uses. */
    fun activeProfileId(): ProfileId = synchronized(this) { activeProfile }

    /** The route profile the active profile carries; walk when it carries none of the built-ins. */
    fun activeProfile(): RouteProfile = BuiltInProfiles.routeProfileOf(activeProfileId()) ?: RouteProfile.Walk

    /** The current value of [active]. */
    fun current(): ProfileAlertSettings = active.value

    /** The values of [profile]; the defaults when the user never changed them. */
    fun forProfile(profile: ProfileId): ProfileAlertSettings = synchronized(this) { states[profile] ?: ProfileAlertSettings() }

    /** The values of the built-in profile that carries [profile]. */
    fun forProfile(profile: RouteProfile): ProfileAlertSettings = forProfile(BuiltInProfiles.idOf(profile))

    /**
     * Reads the store. A missing or unreadable store leaves every profile at its
     * defaults and succeeds. A newer schema leaves them at the defaults and fails.
     */
    @Synchronized
    fun load(): Outcome<Unit> {
        val read = store?.read() ?: Outcome.Success(emptyMap())
        states.clear()
        if (read is Outcome.Success) {
            states.putAll(read.value.mapValues { (_, values) -> decode(values) }.filterValues { !it.isDefault })
        }
        activeState.value = states[activeProfile] ?: ProfileAlertSettings()
        return if (read is Outcome.Failure) read else Outcome.Success(Unit)
    }

    /**
     * Makes [profile] the profile a trip reads. The host's active switches and
     * channels change with it; an alert already latched in [AlertHost] stays
     * latched, so a new switch waits for the next episode.
     */
    @Synchronized
    fun activate(profile: ProfileId) {
        activeProfile = profile
        activeState.value = states[profile] ?: ProfileAlertSettings()
    }

    /** Makes the built-in profile that carries [profile] the profile a trip reads. */
    fun activate(profile: RouteProfile) = activate(BuiltInProfiles.idOf(profile))

    /** Stores [value] for [profile]; every other profile keeps its values. */
    fun set(
        profile: ProfileId,
        value: ProfileAlertSettings,
    ): Outcome<Unit> =
        synchronized(this) {
            if (value.isDefault) {
                states.remove(profile)
            } else {
                states[profile] = value
            }
            if (profile == activeProfile) {
                activeState.value = value
            }
            persist()
        }

    /** Stores [value] for the built-in profile that carries [profile]. */
    fun set(
        profile: RouteProfile,
        value: ProfileAlertSettings,
    ): Outcome<Unit> = set(BuiltInProfiles.idOf(profile), value)

    /** Stores [value] for the active profile. */
    fun setActive(value: ProfileAlertSettings): Outcome<Unit> =
        synchronized(this) {
            set(activeProfile, value)
        }

    private fun persist(): Outcome<Unit> {
        val target = store ?: return Outcome.Success(Unit)
        val scope = writeScope ?: return write(target, synchronized(this) { states.toMap() })
        if (writeRequested.compareAndSet(false, true)) {
            scope.launch(writeDispatcher) {
                do {
                    writeRequested.set(false)
                    writeLatest(target)
                } while (writeRequested.get())
            }
        }
        return Outcome.Success(Unit)
    }

    /** Takes the snapshot inside the write lock, so the last write to run holds the newest state. */
    private suspend fun writeLatest(target: ProfileValueStore) {
        writeLock.withLock {
            val snapshot = synchronized(this) { states.toMap() }
            val result = write(target, snapshot)
            if (result is Outcome.Failure) {
                log("alert settings not saved: ${result.error}")
            }
        }
    }

    private fun write(
        target: ProfileValueStore,
        snapshot: Map<ProfileId, ProfileAlertSettings>,
    ): Outcome<Unit> = target.write(snapshot.filterValues { !it.isDefault }.mapValues { (_, value) -> encode(value) })

    companion object {
        const val SCHEMA_VERSION = 1
        const val ERROR_ID = "alerts.settings_store"
        private const val TRUE = "true"
        private const val SIGNAL_KEY = "signal"
        private const val REROUTE_KEY = "reroute"
        private const val OFF_ROUTE_KEY = "off_route"
        private const val ARRIVAL_KEY = "arrival"
        private const val TUNNEL_KEY = "tunnel"
        private const val RAILWAY_CROSSING_KEY = "railway_crossing"
        private const val HAZARD_KEY = "hazard"
        private const val FAVOURITE_KEY = "favourite"
        private const val TRACK_WAYPOINT_KEY = "track_waypoint"
        private const val POI_KEY = "poi"
        private const val BANNER_KEY = "banner"
        private const val SOUND_KEY = "sound"
        private const val VIBRATION_KEY = "vibration"
        private const val POI_KINDS_KEY = "poi_kinds"

        private val JSON =
            Json {
                encodeDefaults = true
                ignoreUnknownKeys = true
            }

        private fun encode(value: ProfileAlertSettings): Map<String, String> =
            mapOf(
                SIGNAL_KEY to value.settings.signal.toString(),
                REROUTE_KEY to value.settings.reroute.toString(),
                OFF_ROUTE_KEY to value.settings.offRoute.toString(),
                ARRIVAL_KEY to value.settings.arrival.toString(),
                TUNNEL_KEY to value.settings.tunnel.toString(),
                RAILWAY_CROSSING_KEY to value.settings.railwayCrossing.toString(),
                HAZARD_KEY to value.settings.hazard.toString(),
                FAVOURITE_KEY to value.settings.favourite.toString(),
                TRACK_WAYPOINT_KEY to value.settings.trackWaypoint.toString(),
                POI_KEY to value.settings.poi.toString(),
                BANNER_KEY to value.channels.banner.toString(),
                SOUND_KEY to value.channels.sound.toString(),
                VIBRATION_KEY to value.channels.vibration.toString(),
                POI_KINDS_KEY to buildJsonArray { value.poiKinds.sorted().forEach { add(JsonPrimitive(it)) } }.toString(),
            )

        private fun decode(values: Map<String, String>): ProfileAlertSettings {
            fun flag(key: String): Boolean = values[key] == TRUE
            return ProfileAlertSettings(
                settings =
                    AlertSettings(
                        signal = flag(SIGNAL_KEY),
                        reroute = flag(REROUTE_KEY),
                        offRoute = flag(OFF_ROUTE_KEY),
                        arrival = flag(ARRIVAL_KEY),
                        tunnel = flag(TUNNEL_KEY),
                        railwayCrossing = flag(RAILWAY_CROSSING_KEY),
                        hazard = flag(HAZARD_KEY),
                        favourite = flag(FAVOURITE_KEY),
                        trackWaypoint = flag(TRACK_WAYPOINT_KEY),
                        poi = flag(POI_KEY),
                    ),
                channels = AlertChannels(banner = flag(BANNER_KEY), sound = flag(SOUND_KEY), vibration = flag(VIBRATION_KEY)),
                poiKinds = decodeKinds(values[POI_KINDS_KEY]),
            )
        }

        private fun decodeKinds(text: String?): Set<String> =
            try {
                (text?.let(Json::parseToJsonElement) as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }
                    .orEmpty()
                    .toSet()
            } catch (exception: SerializationException) {
                emptySet()
            }

        /**
         * Reads the unversioned file of T-0413 as profile values, or null for a
         * file that is missing or unreadable. Entries under an unknown profile
         * name are dropped, as they always were.
         */
        fun readLegacy(file: File): ProfileValues? {
            if (!file.isFile) {
                return null
            }
            return try {
                val document = JSON.decodeFromString(StoredFile.serializer(), file.readText())
                document.profiles
                    .mapNotNull { (name, stored) ->
                        val id = ProfileId(name)
                        if (BuiltInProfiles.routeProfileOf(id) == null) null else id to stored.toValue()
                    }.filterNot { (_, value) -> value.isDefault }
                    .associate { (id, value) -> id to encode(value) }
            } catch (exception: IOException) {
                null
            } catch (exception: SerializationException) {
                null
            } catch (exception: SecurityException) {
                null
            }
        }
    }
}

@Serializable
private data class StoredFile(
    @SerialName("profiles") val profiles: Map<String, StoredProfile> = emptyMap(),
)

@Serializable
private data class StoredProfile(
    val signal: Boolean = false,
    val reroute: Boolean = false,
    @SerialName("off_route") val offRoute: Boolean = false,
    val arrival: Boolean = false,
    val tunnel: Boolean = false,
    @SerialName("railway_crossing") val railwayCrossing: Boolean = false,
    val hazard: Boolean = false,
    val favourite: Boolean = false,
    @SerialName("track_waypoint") val trackWaypoint: Boolean = false,
    val poi: Boolean = false,
    val banner: Boolean = false,
    val sound: Boolean = false,
    val vibration: Boolean = false,
    @SerialName("poi_kinds") val poiKinds: List<String> = emptyList(),
) {
    fun toValue(): ProfileAlertSettings =
        ProfileAlertSettings(
            settings =
                AlertSettings(
                    signal = signal,
                    reroute = reroute,
                    offRoute = offRoute,
                    arrival = arrival,
                    tunnel = tunnel,
                    railwayCrossing = railwayCrossing,
                    hazard = hazard,
                    favourite = favourite,
                    trackWaypoint = trackWaypoint,
                    poi = poi,
                ),
            channels = AlertChannels(banner = banner, sound = sound, vibration = vibration),
            poiKinds = poiKinds.toSet(),
        )
}
