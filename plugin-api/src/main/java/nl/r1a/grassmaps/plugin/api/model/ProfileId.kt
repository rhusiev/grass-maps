package nl.r1a.grassmaps.plugin.api.model

import nl.r1a.grassmaps.plugin.api.provider.RouteProfile

/**
 * Identifies one settings profile. It is the outer key of every per-profile
 * store, never the built-in [RouteProfile]: a user profile is not a route
 * profile and may carry one of several.
 */
@JvmInline
value class ProfileId(
    val value: String,
) {
    override fun toString(): String = value
}

/**
 * The profiles every install starts with, and the bridge from a [RouteProfile]
 * to the profile that carries its settings until profiles can be chosen.
 */
object BuiltInProfiles {
    val WALK: ProfileId = ProfileId("walk")
    val BIKE: ProfileId = ProfileId("bike")
    val HIKE: ProfileId = ProfileId("hike")

    /** The legacy key of the car route profile. Car is not a built-in profile. */
    private val CAR: ProfileId = ProfileId("car")

    /** The profiles that exist on first start, in display order. */
    val ALL: List<ProfileId> = listOf(WALK, BIKE, HIKE)

    /** The profile whose settings a route of [profile] reads. */
    fun idOf(profile: RouteProfile): ProfileId =
        when (profile) {
            RouteProfile.Walk -> WALK
            RouteProfile.Bike -> BIKE
            RouteProfile.Hike -> HIKE
            RouteProfile.Car -> CAR
        }

    /** The route profile [id] carries, or null for a profile that carries none of the built-ins. */
    fun routeProfileOf(id: ProfileId): RouteProfile? = RouteProfile.entries.firstOrNull { idOf(it) == id }
}
