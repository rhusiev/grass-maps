package nl.r1a.grassmap.alerts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
 * The file holds only profiles that differ from the defaults:
 * `{"profiles":{"bike":{"signal":true,...}}}`. A missing, unreadable or corrupt
 * file reads as every profile at its defaults, because an alert setting must
 * never stop navigation. The keys are stable; a file written by any earlier
 * build of this feature is read back as a subset of the same fields.
 *
 * The active profile is the profile a trip currently uses. The app activates
 * the trip's profile when guidance starts, so a trip reads that profile's
 * values and never a process-wide reset. A profile change applies to the next
 * alert decision; the latches in [AlertHost] are not reset by it.
 *
 * Construction does not read the file. [load] does, so the app runs it off the
 * Main thread. A write is atomic and, when [writeScope] is present, coalesced
 * on the IO dispatcher: a second change while a write is in flight causes one
 * more write of the latest value instead of a write per change.
 */
class AlertSettingsStore(
    private val file: File? = null,
    initialProfile: RouteProfile = RouteProfile.Walk,
    private val writeScope: CoroutineScope? = null,
    private val log: (String) -> Unit = {},
) {
    private val states = mutableMapOf<RouteProfile, ProfileAlertSettings>()
    private var activeProfile: RouteProfile = initialProfile
    private val activeState = MutableStateFlow(ProfileAlertSettings())
    private val writeRequested = AtomicBoolean(false)

    /** The switches, channels and chosen place kinds of the active profile. */
    val active: StateFlow<ProfileAlertSettings> = activeState.asStateFlow()

    /** The profile whose values a running trip uses. */
    fun activeProfile(): RouteProfile = synchronized(this) { activeProfile }

    /** The current value of [active]. */
    fun current(): ProfileAlertSettings = active.value

    /** The values of [profile]; the defaults when the user never changed them. */
    fun forProfile(profile: RouteProfile): ProfileAlertSettings = synchronized(this) { states[profile] ?: ProfileAlertSettings() }

    /**
     * Reads the file. A missing or unreadable file leaves every profile at its
     * defaults; the return value only says whether the read produced a file.
     */
    @Synchronized
    fun load(): Outcome<Unit> {
        states.clear()
        states.putAll(read(file))
        activeState.value = states[activeProfile] ?: ProfileAlertSettings()
        return Outcome.Success(Unit)
    }

    /**
     * Makes [profile] the profile a trip reads. The host's active switches and
     * channels change with it; an alert already latched in [AlertHost] stays
     * latched, so a new switch waits for the next episode.
     */
    @Synchronized
    fun activate(profile: RouteProfile) {
        activeProfile = profile
        activeState.value = states[profile] ?: ProfileAlertSettings()
    }

    /** Stores [value] for [profile]; every other profile keeps its values. */
    fun set(
        profile: RouteProfile,
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

    /** Stores [value] for the active profile. */
    fun setActive(value: ProfileAlertSettings): Outcome<Unit> =
        synchronized(this) {
            set(activeProfile, value)
        }

    private fun persist(): Outcome<Unit> {
        val target = file ?: return Outcome.Success(Unit)
        val scope = writeScope ?: return write(target, synchronized(this) { states.toMap() })
        if (writeRequested.compareAndSet(false, true)) {
            scope.launch(Dispatchers.IO) {
                do {
                    writeRequested.set(false)
                    val snapshot = synchronized(this@AlertSettingsStore) { states.toMap() }
                    val result = write(target, snapshot)
                    if (result is Outcome.Failure) {
                        log("alert settings not saved: ${result.error}")
                    }
                } while (writeRequested.get())
            }
        }
        return Outcome.Success(Unit)
    }

    /** Writes [snapshot]; a failure leaves the previous file untouched. */
    private fun write(
        target: File,
        snapshot: Map<RouteProfile, ProfileAlertSettings>,
    ): Outcome<Unit> {
        val parent = target.parentFile ?: return failure("no parent directory")
        val temporary = File(parent, target.name + TEMPORARY_SUFFIX)
        return try {
            if (!parent.isDirectory && !parent.mkdirs()) {
                return failure("cannot create $parent")
            }
            val json = JSON.encodeToString(StoredFile.serializer(), stored(snapshot))
            FileOutputStream(temporary).use { stream ->
                stream.write(json.toByteArray())
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
            failure(exception.message)
        } catch (exception: SecurityException) {
            temporary.delete()
            failure(exception.message)
        } catch (exception: RuntimeException) {
            temporary.delete()
            failure(exception.message)
        }
    }

    private fun failure(detail: String?): Outcome.Failure = Outcome.Failure(AppError.Internal(ERROR_ID, detail))

    private companion object {
        const val ERROR_ID = "alerts.settings_store"
        const val TEMPORARY_SUFFIX = ".tmp"

        val JSON =
            Json {
                encodeDefaults = true
                ignoreUnknownKeys = true
            }

        fun stored(states: Map<RouteProfile, ProfileAlertSettings>): StoredFile =
            StoredFile(
                states.entries
                    .filterNot { (_, value) -> value.isDefault }
                    .associate { (profile, value) -> profileName(profile) to StoredProfile.of(value) },
            )

        fun read(file: File?): Map<RouteProfile, ProfileAlertSettings> {
            if (file == null || !file.isFile) {
                return emptyMap()
            }
            return try {
                val document = JSON.decodeFromString(StoredFile.serializer(), file.readText())
                document.profiles
                    .mapNotNull { (name, stored) ->
                        val profile = profileOf(name) ?: return@mapNotNull null
                        profile to stored.toValue()
                    }.toMap()
            } catch (exception: IOException) {
                emptyMap()
            } catch (exception: SerializationException) {
                emptyMap()
            } catch (exception: SecurityException) {
                emptyMap()
            }
        }

        fun profileName(profile: RouteProfile): String =
            when (profile) {
                RouteProfile.Walk -> "walk"
                RouteProfile.Bike -> "bike"
                RouteProfile.Hike -> "hike"
                RouteProfile.Car -> "car"
            }

        fun profileOf(name: String): RouteProfile? =
            when (name) {
                "walk" -> RouteProfile.Walk
                "bike" -> RouteProfile.Bike
                "hike" -> RouteProfile.Hike
                "car" -> RouteProfile.Car
                else -> null
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

    companion object {
        fun of(value: ProfileAlertSettings): StoredProfile =
            StoredProfile(
                signal = value.settings.signal,
                reroute = value.settings.reroute,
                offRoute = value.settings.offRoute,
                arrival = value.settings.arrival,
                tunnel = value.settings.tunnel,
                railwayCrossing = value.settings.railwayCrossing,
                hazard = value.settings.hazard,
                favourite = value.settings.favourite,
                trackWaypoint = value.settings.trackWaypoint,
                poi = value.settings.poi,
                banner = value.channels.banner,
                sound = value.channels.sound,
                vibration = value.channels.vibration,
                poiKinds = value.poiKinds.sorted(),
            )
    }
}
