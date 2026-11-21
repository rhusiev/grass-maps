package nl.r1a.grassmaps.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import nl.r1a.grassmaps.core.services.LegacyProfileFile
import nl.r1a.grassmaps.core.services.ProfileValueFile
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.model.BuiltInProfiles
import nl.r1a.grassmaps.plugin.api.model.ProfileId
import nl.r1a.grassmaps.plugin.api.model.StyleId
import nl.r1a.grassmaps.plugin.api.service.ProfileSettingsGroup
import nl.r1a.grassmaps.plugin.api.service.ProfileValueStore
import nl.r1a.grassmaps.plugin.api.service.ProfileValues
import nl.r1a.grassmaps.plugin.api.service.withKnownValues
import nl.r1a.grassmaps.plugins.layers.panel.MapStyleChoice
import java.io.File
import java.io.IOException

/**
 * The theme the shell and the map share: a capture fixture first, then the
 * debug pin (`core.theme.set`), then the system night mode. The default follows
 * the system; only a debug build can pin it.
 */
internal fun isDarkTheme(
    fixture: Boolean?,
    override: Boolean?,
    systemDark: Boolean,
): Boolean = fixture ?: override ?: systemDark

/**
 * The style variant the map draws for [choice] in [dark]. Route preview, the
 * Stops sheet and every other state share one map screen, so this one function
 * decides the variant for all of them. Meadow and Trail share one base document,
 * so a profile switch only changes the layer diff and never reloads the style.
 */
internal fun mapStyleId(
    choice: MapStyleChoice,
    dark: Boolean,
): StyleId =
    StyleId(
        when (choice) {
            MapStyleChoice.STANDARD -> if (dark) "standard-dark" else "standard"
            MapStyleChoice.TOURING -> if (dark) "touring-dark" else "touring"
        },
    )

/**
 * The map style chosen in the layers panel (T-0109), one value per profile
 * (T-0603). The store is keyed by [ProfileId] under the per-profile contract of
 * architecture section 20. Each profile holds the stable key [STYLE_KEY] with
 * `standard` or `touring`; only a profile that differs from Meadow is stored.
 *
 * [open] migrates the unversioned app-wide `settings/style.json` into
 * [BuiltInProfiles.WALK], the profile active before profiles existed, and keeps
 * the old file until the new one reads back equal. A built-in with no stored
 * value reads as Meadow. Construction reads the store, so resolve it off Main.
 * A store written by a newer build reads as Meadow and is never overwritten.
 */
class MapStyleSettings(
    private val store: ProfileValueStore? = null,
    initialProfile: ProfileId = BuiltInProfiles.WALK,
) : ProfileSettingsGroup {
    override val id: String = ProfileSettingsGroup.STYLE

    private var values: ProfileValues = emptyMap()
    private var isLoaded = false
    private var activeProfile: ProfileId = initialProfile
    private val activeState = MutableStateFlow(DEFAULT_CHOICE)

    /** The active style the map and the layers panel collect. */
    val active: StateFlow<MapStyleChoice> = activeState.asStateFlow()

    init {
        load()
    }

    /** Reads the store. A failure leaves every profile at Meadow. */
    @Synchronized
    fun load(): Outcome<Unit> {
        val read = store?.read() ?: Outcome.Success(emptyMap())
        return when (read) {
            is Outcome.Success -> {
                values = read.value
                isLoaded = true
                activeState.value = decode(values[activeProfile])
                Outcome.Success(Unit)
            }

            is Outcome.Failure -> {
                values = emptyMap()
                isLoaded = false
                activeState.value = DEFAULT_CHOICE
                read
            }
        }
    }

    fun current(): MapStyleChoice = active.value

    /** The style of [profile]; Meadow when the user never chose one. */
    @Synchronized
    fun forProfile(profile: ProfileId): MapStyleChoice = decode(values[profile])

    /** The profile whose style [active] publishes. */
    @Synchronized
    fun activeProfileId(): ProfileId = activeProfile

    /** Makes [profile]'s style the published value, with the other stores in the same switch. */
    @Synchronized
    fun activate(profile: ProfileId) {
        activeProfile = profile
        activeState.value = decode(values[profile])
    }

    /** Makes [choice] active for the active profile and returns when the file holds it. */
    fun select(choice: MapStyleChoice): Outcome<Unit> = select(activeProfileId(), choice)

    /** Stores [choice] for [profile] and leaves every other profile untouched. */
    @Synchronized
    fun select(
        profile: ProfileId,
        choice: MapStyleChoice,
    ): Outcome<Unit> {
        if (!isLoaded) {
            val read = load()
            if (read is Outcome.Failure) return read
        }
        val stored = if (choice == DEFAULT_CHOICE) emptyMap() else mapOf(STYLE_KEY to choice.styleId)
        val next = values.withKnownValues(profile, KNOWN_KEYS, stored)
        val target = store ?: return Outcome.Success(Unit).also { adopt(next, profile, choice) }
        return target.write(next).also { written ->
            if (written is Outcome.Success) {
                adopt(next, profile, choice)
            }
        }
    }

    override fun read(profile: ProfileId): Map<String, String> =
        forProfile(profile).takeIf { it != DEFAULT_CHOICE }?.let { mapOf(STYLE_KEY to it.styleId) } ?: emptyMap()

    override fun replace(
        profile: ProfileId,
        values: Map<String, String>,
    ): Outcome<Unit> = select(profile, MapStyleChoice.fromStyleId(values[STYLE_KEY]) ?: DEFAULT_CHOICE)

    private fun adopt(
        next: ProfileValues,
        profile: ProfileId,
        choice: MapStyleChoice,
    ) {
        values = next
        if (profile == activeProfile) {
            activeState.value = choice
        }
    }

    companion object {
        /** Meadow is the default style. */
        val DEFAULT_CHOICE: MapStyleChoice = MapStyleChoice.STANDARD

        const val SCHEMA_VERSION = 1
        const val ERROR_ID = "app.map_style_settings"
        const val STYLE_KEY = "style"

        private val KNOWN_KEYS = setOf(STYLE_KEY)

        /** Opens the per-profile store, migrating [legacyFile] into [file] when that still exists. */
        fun open(
            file: File,
            legacyFile: File,
        ): MapStyleSettings =
            MapStyleSettings(
                ProfileValueFile(
                    file = file,
                    schemaVersion = SCHEMA_VERSION,
                    errorId = ERROR_ID,
                    legacy = LegacyProfileFile(legacyFile, ::readLegacy),
                ),
            )

        /** Reads the old app-wide file as the walk profile's choice, or null when it holds none. */
        fun readLegacy(file: File): ProfileValues? {
            if (!file.isFile) {
                return null
            }
            return try {
                val root = Json.parseToJsonElement(file.readText()) as? JsonObject ?: return null
                val choice = MapStyleChoice.fromStyleId((root[STYLE_KEY] as? JsonPrimitive)?.contentOrNull) ?: return null
                if (choice == DEFAULT_CHOICE) {
                    null
                } else {
                    mapOf(BuiltInProfiles.WALK to mapOf(STYLE_KEY to choice.styleId))
                }
            } catch (exception: IOException) {
                null
            } catch (exception: SerializationException) {
                null
            } catch (exception: SecurityException) {
                null
            }
        }

        private fun decode(values: Map<String, String>?): MapStyleChoice =
            MapStyleChoice.fromStyleId(values?.get(STYLE_KEY)) ?: DEFAULT_CHOICE
    }
}
