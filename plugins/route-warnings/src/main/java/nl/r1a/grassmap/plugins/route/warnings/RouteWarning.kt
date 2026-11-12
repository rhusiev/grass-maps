package nl.r1a.grassmap.plugins.route.warnings

import nl.r1a.grassmap.plugin.api.model.WayAlertElementType
import nl.r1a.grassmap.plugin.api.model.WayAlertFeatureKind
import nl.r1a.grassmap.plugin.api.provider.RouteProfile

/**
 * The five things a route warning names (T-0409).
 *
 * A ford, a barrier and a railway crossing are region features, the same ones
 * the way alerts read. Steps and a private way are route attributes, because
 * the region feature component does not carry them: the route engine's
 * annotated segments do.
 */
enum class RouteWarningKind {
    Ford,
    Steps,
    Barrier,
    RailwayCrossing,
    PrivateWay,
}

/**
 * One warning for a planned route, at the distance where the rider meets it.
 *
 * [osmElementType] and [osmId] are the OSM identity when the warning comes
 * from a region feature. Steps and private ways come from a route segment,
 * which carries no way id, so both are null there.
 *
 * [profiles] is the set of profiles the kind applies to. It is the policy's
 * answer, not the profile the list was requested for; a caller can show why a
 * warning is absent for another profile.
 */
data class RouteWarning(
    val kind: RouteWarningKind,
    val distanceAlongRouteMeters: Double,
    val osmElementType: WayAlertElementType? = null,
    val osmId: Long? = null,
    val profiles: Set<RouteProfile> = RouteWarningPolicy.profilesFor(kind),
) {
    init {
        require(distanceAlongRouteMeters.isFinite() && distanceAlongRouteMeters >= 0.0) {
            "the distance along the route must be finite and not negative"
        }
        require((osmElementType == null) == (osmId == null)) {
            "an OSM identity needs both the element type and the id, or neither"
        }
        require(osmId == null || osmId > 0L) {
            "the OSM id must be positive when present"
        }
        require(profiles.isNotEmpty() && profiles.all { it in RouteWarningPolicy.supportedProfiles }) {
            "a warning applies to at least one supported profile"
        }
    }
}

/**
 * Which warning kinds apply to which profile, and how wide the route corridor
 * is (T-0409).
 *
 * The corridor is 30 m, the same one the way alerts use: a route line and an
 * OSM node both carry metre-level noise, and a barrier at the verge still
 * matters. A parallel railway or a service road one street away stays out.
 *
 * The profile table is a product choice, not a measurement:
 *
 * | Kind | Walk | Hike | Bike |
 * |---|---|---|---|
 * | Ford | yes | yes | yes |
 * | Steps | yes | yes | yes |
 * | Barrier | yes | yes | no |
 * | Railway crossing | no | yes | yes |
 * | Private way | yes | yes | yes |
 *
 * A barrier is dropped for a bike because the region component keeps gates,
 * stiles and similar passable barriers but drops bollards and kerbs, and it
 * does not carry the barrier's own value. A bike can open a gate or lift the
 * bike over a stile, so the bike list does not warn on the whole kind rather
 * than warn on every openable gate. A railway crossing is not listed for walk
 * because the product scope gives crossing warnings to bike and hike, the same
 * split the way-alert distances use.
 */
object RouteWarningPolicy {
    /** The corridor every region feature must lie in to be a route warning. */
    const val CORRIDOR_METERS: Double = 30.0

    /** The profiles route warnings are computed for. Car is outside v1. */
    val supportedProfiles: Set<RouteProfile> = setOf(RouteProfile.Walk, RouteProfile.Hike, RouteProfile.Bike)

    private val FOOT_AND_BIKE = supportedProfiles
    private val FOOT = setOf(RouteProfile.Walk, RouteProfile.Hike)

    fun profilesFor(kind: RouteWarningKind): Set<RouteProfile> =
        when (kind) {
            RouteWarningKind.Ford,
            RouteWarningKind.Steps,
            RouteWarningKind.PrivateWay,
            -> FOOT_AND_BIKE

            RouteWarningKind.Barrier -> FOOT

            RouteWarningKind.RailwayCrossing -> setOf(RouteProfile.Hike, RouteProfile.Bike)
        }

    fun appliesTo(
        kind: RouteWarningKind,
        profile: RouteProfile,
    ): Boolean = profile in profilesFor(kind)

    /** The warning kind a region feature names, or null when it is not one of the five. */
    internal fun kindOf(kind: WayAlertFeatureKind): RouteWarningKind? =
        when (kind) {
            WayAlertFeatureKind.Ford -> RouteWarningKind.Ford
            WayAlertFeatureKind.Barrier -> RouteWarningKind.Barrier
            WayAlertFeatureKind.RailwayCrossing -> RouteWarningKind.RailwayCrossing
            WayAlertFeatureKind.Hazard, WayAlertFeatureKind.Tunnel -> null
        }
}
