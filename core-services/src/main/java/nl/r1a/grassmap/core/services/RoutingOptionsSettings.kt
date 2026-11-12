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
import nl.r1a.grassmap.plugin.api.provider.Hilliness
import nl.r1a.grassmap.plugin.api.provider.ProfileRouteOptions
import nl.r1a.grassmap.plugin.api.provider.RouteOptions
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import java.io.File
import java.io.IOException

/**
 * The per-profile routing options (T-0308), all defaults until the user sets
 * one. A profile with no stored entry reads as [RouteOptions], so a change to
 * bike cannot change what walk receives.
 *
 * The file holds only non-default profiles:
 * `{"profiles":{"bike":{"hilliness":"avoid_hills"}}}`. It lives beside the
 * other local settings until the versioned global settings file exists.
 * Construction reads the file, so resolve it off the Main thread. A missing,
 * unreadable or corrupt file reads as defaults, because a routing preference
 * must not stop the planner. A write is atomic.
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
        const val HILLINESS_KEY = "hilliness"
        const val DEFAULT_NAME = "default"
        const val AVOID_HILLS_NAME = "avoid_hills"

        fun document(options: ProfileRouteOptions): String =
            buildJsonObject {
                putJsonObject(PROFILES_KEY) {
                    for (profile in RouteProfile.entries) {
                        val profileOptions = options.byProfile[profile] ?: continue
                        putJsonObject(profileName(profile)) {
                            put(HILLINESS_KEY, hillinessName(profileOptions.hilliness))
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

        fun hillinessName(hilliness: Hilliness): String =
            when (hilliness) {
                Hilliness.Default -> DEFAULT_NAME
                Hilliness.AvoidHills -> AVOID_HILLS_NAME
            }

        fun hillinessOf(name: String?): Hilliness =
            when (name) {
                AVOID_HILLS_NAME -> Hilliness.AvoidHills
                else -> Hilliness.Default
            }

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
                    options =
                        options.withProfile(
                            profile,
                            RouteOptions(hillinessOf((entry[HILLINESS_KEY] as? JsonPrimitive)?.contentOrNull)),
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
