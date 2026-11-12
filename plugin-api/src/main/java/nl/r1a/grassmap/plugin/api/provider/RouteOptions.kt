package nl.r1a.grassmap.plugin.api.provider

/**
 * The hilliness preference a person sets for a profile (T-0308).
 *
 * [Default] leaves the engine's tuned profile value alone. [AvoidHills] asks
 * for the strongest avoidance the engine offers, so a route may be longer or
 * slower when that lowers the climb.
 */
enum class Hilliness {
    Default,
    AvoidHills,
}

/** One profile's routing options, carried by [RouteRequest]. */
data class RouteOptions(
    val hilliness: Hilliness = Hilliness.Default,
)

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
                if (options == RouteOptions()) {
                    byProfile - profile
                } else {
                    byProfile + (profile to options)
                },
        )
}
