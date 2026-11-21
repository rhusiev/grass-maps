package nl.r1a.grassmaps.core.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.model.BuiltInProfiles
import nl.r1a.grassmaps.plugin.api.model.ProfileId
import nl.r1a.grassmaps.plugin.api.model.UnitSystem
import nl.r1a.grassmaps.plugin.api.service.ProfileSettingsGroup
import nl.r1a.grassmaps.plugin.api.service.ProfileValueStore
import nl.r1a.grassmaps.plugin.api.service.ProfileValues
import nl.r1a.grassmaps.plugin.api.service.withKnownValues
import java.io.File
import java.io.IOException

/**
 * The user's length unit system (T-0107), metric by default, one value per
 * profile (T-0603).
 *
 * The store is keyed by [ProfileId] under the per-profile contract of
 * architecture section 20. Each profile holds the stable key [UNITS_KEY] with
 * `metric` or `imperial`; only a profile that differs from the default is
 * stored. [open] migrates the unversioned app-wide `settings/units.json` into
 * [BuiltInProfiles.WALK], the profile active before profiles existed, and
 * retires the old file only after the new one reads back equal. A built-in with
 * no stored value reads as metric.
 *
 * Construction reads the store, so resolve it off the Main thread. A missing,
 * unreadable or corrupt store reads as metric for every profile, because a unit
 * preference must not stop the map from starting. A store written by a newer
 * build reads as defaults and is never overwritten. A write keeps every key
 * this build does not know, as [withKnownValues] does for the other stores.
 */
class UnitSettings(
    private val store: ProfileValueStore? = null,
    initialProfile: ProfileId = BuiltInProfiles.WALK,
) : ProfileSettingsGroup {
    override val id: String = ProfileSettingsGroup.UNITS

    private var values: ProfileValues = emptyMap()
    private var isLoaded = false
    private var activeProfile: ProfileId = initialProfile
    private val activeState = MutableStateFlow(UnitSystem.Metric)

    /** The active unit system. Renderers collect this flow. */
    val units: StateFlow<UnitSystem> = activeState.asStateFlow()

    init {
        load()
    }

    /** Reads the store. A failure leaves every profile at metric. */
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
                activeState.value = UnitSystem.Metric
                read
            }
        }
    }

    /** The units of [profile]; metric when the user never set any. */
    @Synchronized
    fun forProfile(profile: ProfileId): UnitSystem = decode(values[profile])

    /** The profile whose units [units] publishes. */
    @Synchronized
    fun activeProfileId(): ProfileId = activeProfile

    /** Makes [profile]'s units the published value, with the other stores in the same switch. */
    @Synchronized
    fun activate(profile: ProfileId) {
        activeProfile = profile
        activeState.value = decode(values[profile])
    }

    /** Stores [units] for [profile] and leaves every other profile untouched. */
    @Synchronized
    fun set(
        profile: ProfileId,
        units: UnitSystem,
    ): Outcome<Unit> {
        if (!isLoaded) {
            val read = load()
            if (read is Outcome.Failure) return read
        }
        val stored = if (units == UnitSystem.Metric) emptyMap() else mapOf(UNITS_KEY to wireName(units))
        val next = values.withKnownValues(profile, KNOWN_KEYS, stored)
        val target = store ?: return Outcome.Success(Unit).also { adopt(next, profile, units) }
        val written = target.write(next)
        if (written is Outcome.Success) {
            adopt(next, profile, units)
        }
        return written
    }

    /** Stores [units] for the active profile. */
    fun set(units: UnitSystem): Outcome<Unit> = set(activeProfileId(), units)

    override fun read(profile: ProfileId): Map<String, String> =
        forProfile(profile).takeIf { it != UnitSystem.Metric }?.let { mapOf(UNITS_KEY to wireName(it)) } ?: emptyMap()

    override fun replace(
        profile: ProfileId,
        values: Map<String, String>,
    ): Outcome<Unit> = set(profile, decode(values))

    private fun adopt(
        next: ProfileValues,
        profile: ProfileId,
        units: UnitSystem,
    ) {
        values = next
        if (profile == activeProfile) {
            activeState.value = units
        }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val ERROR_ID = "core.services.unit_settings"
        const val UNITS_KEY = "units"

        private val KNOWN_KEYS = setOf(UNITS_KEY)

        /** Opens the per-profile store, migrating [legacyFile] into [file] when that still exists. */
        fun open(
            file: File,
            legacyFile: File,
        ): UnitSettings =
            UnitSettings(
                ProfileValueFile(
                    file = file,
                    schemaVersion = SCHEMA_VERSION,
                    errorId = ERROR_ID,
                    legacy = LegacyProfileFile(legacyFile, ::readLegacy),
                ),
            )

        /** Reads the old app-wide file as the walk profile's value, or null when it holds no choice. */
        fun readLegacy(file: File): ProfileValues? {
            if (!file.isFile) {
                return null
            }
            return try {
                val root = Json.parseToJsonElement(file.readText()) as? JsonObject ?: return null
                val units = decode(mapOf(UNITS_KEY to ((root[UNITS_KEY] as? JsonPrimitive)?.contentOrNull ?: return null)))
                if (units == UnitSystem.Metric) {
                    null
                } else {
                    mapOf(BuiltInProfiles.WALK to mapOf(UNITS_KEY to wireName(units)))
                }
            } catch (exception: IOException) {
                null
            } catch (exception: SerializationException) {
                null
            } catch (exception: SecurityException) {
                null
            }
        }

        private fun wireName(units: UnitSystem): String =
            when (units) {
                UnitSystem.Metric -> "metric"
                UnitSystem.Imperial -> "imperial"
            }

        private fun decode(values: Map<String, String>?): UnitSystem =
            when (values?.get(UNITS_KEY)) {
                "imperial" -> UnitSystem.Imperial
                else -> UnitSystem.Metric
            }
    }
}
