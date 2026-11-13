package nl.r1a.grassmap.plugin.api.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One step of a route preference (T-0421). The three values are the same for
 * hills, busy roads and rough surfaces, so the UI and the serialized form stay
 * one vocabulary.
 *
 * [Avoid] asks for the strongest avoidance the engine offers, [Normal] leaves
 * the profile's tuned value alone, and [DontMind] asks the engine to accept the
 * feature freely. A step is a cost preference, not a guarantee: an engine can
 * still use an avoided road when no other route exists.
 *
 * [serializedValue] is the stable spelling in settings, state streams and logs.
 * It must not change when the enum entry is renamed.
 */
@Serializable
enum class RoutePreference(
    val serializedValue: String,
) {
    @SerialName("avoid")
    Avoid("avoid"),

    @SerialName("normal")
    Normal("normal"),

    @SerialName("dont_mind")
    DontMind("dont_mind"),
    ;

    companion object {
        /** The step with [serializedValue] [name], or null for an unknown value. */
        fun ofSerialized(name: String?): RoutePreference? = entries.firstOrNull { preference -> preference.serializedValue == name }
    }
}

/**
 * One profile's routing options (T-0308, three steps in T-0421).
 *
 * Each attribute is independent: changing hills cannot change busy roads, and a
 * profile with no stored entry reads as all [RoutePreference.Normal]. The
 * profile's values are the defaults for every route; a route preview can
 * override them for one route without writing this object back.
 */
@Serializable
data class RouteOptions(
    @SerialName("hills")
    val hills: RoutePreference = RoutePreference.Normal,
    @SerialName("busy_roads")
    val busyRoads: RoutePreference = RoutePreference.Normal,
    @SerialName("rough_surfaces")
    val roughSurfaces: RoutePreference = RoutePreference.Normal,
) {
    /** True when every attribute is [RoutePreference.Normal]. */
    val isDefault: Boolean
        get() = this == RouteOptions()

    /** How many attributes differ from [RoutePreference.Normal]. */
    val setCount: Int
        get() = ROUTE_OPTION_ATTRIBUTES.count { attribute -> attribute(this) != RoutePreference.Normal }
}

/** The attributes in the order the preferences screen shows them. */
private val ROUTE_OPTION_ATTRIBUTES: List<(RouteOptions) -> RoutePreference> =
    listOf({ options -> options.hills }, { options -> options.busyRoads }, { options -> options.roughSurfaces })

/**
 * Every profile's options together. A profile with no entry uses the defaults,
 * so changing one profile can never change another: [forProfile] reads only
 * the requested key and [withProfile] replaces only that key.
 */
data class ProfileRouteOptions(
    val byProfile: Map<RouteProfile, RouteOptions> = emptyMap(),
) {
    fun forProfile(profile: RouteProfile): RouteOptions = byProfile[profile] ?: RouteOptions()

    fun withProfile(
        profile: RouteProfile,
        options: RouteOptions,
    ): ProfileRouteOptions =
        copy(
            byProfile =
                if (options.isDefault) {
                    byProfile - profile
                } else {
                    byProfile + (profile to options)
                },
        )
}
