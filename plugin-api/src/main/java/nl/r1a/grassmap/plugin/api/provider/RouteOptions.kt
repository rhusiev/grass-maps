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
 * One routing attribute a profile can offer as a three-step preference
 * (T-0421). The design shows a different set per profile, see [forProfile].
 */
enum class RouteAttribute {
    Hills,
    Steps,
    TrailDifficulty,
    BusyRoads,
    RoughSurfaces,
    ;

    companion object {
        /**
         * The attributes [profile] shows, in the order its sheet lists them:
         * walk has hills and steps, hike adds trail difficulty, bike has hills,
         * busy roads and rough surfaces. Car is out of v1 and has none.
         */
        fun forProfile(profile: RouteProfile): List<RouteAttribute> =
            when (profile) {
                RouteProfile.Walk -> listOf(Hills, Steps)
                RouteProfile.Hike -> listOf(Hills, Steps, TrailDifficulty)
                RouteProfile.Bike -> listOf(Hills, BusyRoads, RoughSurfaces)
                RouteProfile.Car -> emptyList()
            }
    }
}

/**
 * One profile's routing options (T-0308, three steps in T-0421).
 *
 * Each attribute is independent: changing hills cannot change busy roads, and a
 * profile with no stored entry reads as all [RoutePreference.Normal]. Every
 * profile keeps all five fields so the stored form is one shape, but only the
 * attributes in [RouteAttribute.forProfile] are shown and sent to the engine.
 * The profile's values are the defaults for every route; a route preview can
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
    @SerialName("steps")
    val steps: RoutePreference = RoutePreference.Normal,
    @SerialName("trail_difficulty")
    val trailDifficulty: RoutePreference = RoutePreference.Normal,
) {
    /** True when every attribute is [RoutePreference.Normal]. */
    val isDefault: Boolean
        get() = this == RouteOptions()

    /** How many attributes differ from [RoutePreference.Normal]. */
    val setCount: Int
        get() = RouteAttribute.entries.count { attribute -> this[attribute] != RoutePreference.Normal }

    /** How many of [profile]'s own attributes differ from [RoutePreference.Normal]. */
    fun setCount(profile: RouteProfile): Int =
        RouteAttribute.forProfile(profile).count { attribute -> this[attribute] != RoutePreference.Normal }

    /** The step chosen for [attribute]. */
    operator fun get(attribute: RouteAttribute): RoutePreference =
        when (attribute) {
            RouteAttribute.Hills -> hills
            RouteAttribute.Steps -> steps
            RouteAttribute.TrailDifficulty -> trailDifficulty
            RouteAttribute.BusyRoads -> busyRoads
            RouteAttribute.RoughSurfaces -> roughSurfaces
        }

    /** A copy with [attribute] set to [preference]. */
    fun with(
        attribute: RouteAttribute,
        preference: RoutePreference,
    ): RouteOptions =
        when (attribute) {
            RouteAttribute.Hills -> copy(hills = preference)
            RouteAttribute.Steps -> copy(steps = preference)
            RouteAttribute.TrailDifficulty -> copy(trailDifficulty = preference)
            RouteAttribute.BusyRoads -> copy(busyRoads = preference)
            RouteAttribute.RoughSurfaces -> copy(roughSurfaces = preference)
        }
}

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
