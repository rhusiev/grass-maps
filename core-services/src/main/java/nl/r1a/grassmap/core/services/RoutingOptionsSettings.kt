package nl.r1a.grassmap.core.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.provider.ProfileRouteOptions
import nl.r1a.grassmap.plugin.api.provider.RouteOptions
import nl.r1a.grassmap.plugin.api.provider.RoutePreference
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import java.io.File
import java.io.IOException

/**
 * The per-profile routing options (T-0308), all defaults until the user sets
 * one. A profile with no stored entry reads as [RouteOptions], so a change to
 * bike cannot change what walk receives.
 *
 * The file holds only non-default profiles:
 * `{"profiles":{"bike":{"hills":"avoid","busy_roads":"normal","rough_surfaces":"dont_mind"}}}`.
 * The keys and values are stable. A file from T-0308 that still carries the
 * two-state `hilliness` key reads as `hills` (`avoid_hills` becomes `avoid`,
 * `default` becomes `normal`), so an existing choice survives the upgrade.
 * It lives beside the other local settings until the versioned global settings
 * file exists. Construction reads the file, so resolve it off the Main thread.
 * A missing, unreadable or corrupt file reads as defaults, because a routing
 * preference must not stop the planner. A write is atomic.
 */
class RoutingOptionsSettings(
    private val file: File? = null,
) {
    private val state = MutableStateFlow(load(file) ?: ProfileRouteOptions())

    /** The options every profile currently has. */
    val options: StateFlow<ProfileRouteOptions> = state.asStateFlow()

    /** The options for [profile], defaults when the user never set any. */
    fun forProfile(profile: RouteProfile): RouteOptions = state.value.forProfile(profile)

    /** Stores one profile's options; the other profiles keep their values. */
    @Synchronized
    fun set(
        profile: RouteProfile,
        options: RouteOptions,
    ): Outcome<Unit> {
        val next = state.value.withProfile(profile, options)
        state.value = next
        val target = file ?: return Outcome.Success(Unit)
        return AtomicFiles.write(target, ERROR_ID) { stream ->
            stream.write(document(next).toByteArray())
        }
    }

    private companion object {
        const val ERROR_ID = "core.services.routing_options_settings"
        const val PROFILES_KEY = "profiles"
        const val HILLS_KEY = "hills"
        const val BUSY_ROADS_KEY = "busy_roads"
        const val ROUGH_SURFACES_KEY = "rough_surfaces"

        /** The T-0308 key and values, read so an existing choice is not lost. */
        const val LEGACY_HILLINESS_KEY = "hilliness"
        const val LEGACY_AVOID_HILLS_NAME = "avoid_hills"

        fun document(options: ProfileRouteOptions): String =
            buildJsonObject {
                putJsonObject(PROFILES_KEY) {
                    for (profile in RouteProfile.entries) {
                        val profileOptions = options.byProfile[profile] ?: continue
                        putJsonObject(profileName(profile)) {
                            put(HILLS_KEY, profileOptions.hills.serializedValue)
                            put(BUSY_ROADS_KEY, profileOptions.busyRoads.serializedValue)
                            put(ROUGH_SURFACES_KEY, profileOptions.roughSurfaces.serializedValue)
                        }
                    }
                }
            }.toString()

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

        fun preferenceOf(name: String?): RoutePreference? = RoutePreference.ofSerialized(name)

        /** Reads the T-0308 key when the three-step key is absent. */
        fun legacyHillinessOf(name: String?): RoutePreference =
            if (name == LEGACY_AVOID_HILLS_NAME) RoutePreference.Avoid else RoutePreference.Normal

        fun load(file: File?): ProfileRouteOptions? {
            if (file == null || !file.isFile) {
                return null
            }
            return try {
                val root = Json.parseToJsonElement(file.readText()) as? JsonObject ?: return null
                val profiles = root[PROFILES_KEY] as? JsonObject ?: return null
                var options = ProfileRouteOptions()
                for ((name, element) in profiles) {
                    val profile = profileOf(name) ?: continue
                    val entry = element as? JsonObject ?: continue
                    val hillsName = (entry[HILLS_KEY] as? JsonPrimitive)?.contentOrNull
                    val hills =
                        if (hillsName == null) {
                            legacyHillinessOf((entry[LEGACY_HILLINESS_KEY] as? JsonPrimitive)?.contentOrNull)
                        } else {
                            preferenceOf(hillsName) ?: RoutePreference.Normal
                        }
                    options =
                        options.withProfile(
                            profile,
                            RouteOptions(
                                hills = hills,
                                busyRoads =
                                    preferenceOf(
                                        (entry[BUSY_ROADS_KEY] as? JsonPrimitive)?.contentOrNull,
                                    ) ?: RoutePreference.Normal,
                                roughSurfaces =
                                    preferenceOf((entry[ROUGH_SURFACES_KEY] as? JsonPrimitive)?.contentOrNull) ?: RoutePreference.Normal,
                            ),
                        )
                }
                options
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
