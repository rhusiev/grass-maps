package nl.r1a.grassmap.navigation

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteSegment

/**
 * A replay that leaves a route and comes back, with timestamps that advance by
 * distance at the profile's gate speed.
 *
 * The timing under test is 20 s of travel, so one fix is one second of travel:
 * the next fix is the gate speed's worth of metres away. A change of relation
 * moves the fix sideways in one step, which the gate must treat as a new
 * reading rather than as travel.
 */
internal object RerouteReplay {
    const val ORIGIN_LATITUDE: Double = 49.84
    const val ORIGIN_LONGITUDE: Double = 24.03
    const val BASE_MILLIS: Long = 1_700_000_000_000L
    const val FIX_ACCURACY_METERS: Double = 5.0
    const val STEP_METERS: Double = 10.0
    const val OFFSET_METERS: Double = 20.0
    const val SIDEWAYS_BEARING: Double = 180.0

    /** One synthetic trip: the line the session follows and the fixes it receives. */
    data class Journey(
        val route: Route,
        val fixes: List<LocationFix>,
    )

    /** Builds a journey one phase at a time; each fix is one second of travel. */
    class Builder(
        private val profile: RouteProfile = RouteProfile.Walk,
        private val legMeters: List<Double> = listOf(400.0),
        startAlongMeters: Double = 50.0,
    ) {
        private val speed = NavigationConfig().speedMetersPerSecond(profile)
        private val route = straightRoute(legMeters, profile)
        private val fixes = mutableListOf<LocationFix>()
        private var along = startAlongMeters
        private var second = 0

        /** [count] fixes on the line, moving at the profile's gate speed. */
        fun onRoute(
            count: Int,
            accuracyMeters: Double = FIX_ACCURACY_METERS,
            stepMeters: Double = speed,
        ): Builder = append(count, sidewaysMeters = 0.0, accuracyMeters = accuracyMeters, stepMeters = stepMeters)

        /** [count] fixes on a parallel line, starting with one sideways step. */
        fun offRoute(
            count: Int,
            offsetMeters: Double = OFFSET_METERS,
            accuracyMeters: Double = FIX_ACCURACY_METERS,
            stepMeters: Double = speed,
        ): Builder = append(count, sidewaysMeters = offsetMeters, accuracyMeters = accuracyMeters, stepMeters = stepMeters)

        /** One fix on the line whose accuracy is worse than the configured limit. */
        fun inaccurateOnRouteFix(): Builder =
            append(
                count = 1,
                sidewaysMeters = 0.0,
                accuracyMeters = NavigationConfig().minimumHorizontalAccuracyMeters + 25.0,
                stepMeters = speed,
            )

        fun build(): Journey = Journey(route = route, fixes = fixes.toList())

        private fun append(
            count: Int,
            sidewaysMeters: Double,
            accuracyMeters: Double,
            stepMeters: Double,
        ): Builder {
            repeat(count.coerceAtLeast(0)) {
                fixes += fix(along, sidewaysMeters, second, accuracyMeters)
                second += 1
                along += stepMeters
            }
            return this
        }
    }

    /**
     * A straight east-heading route. [legMeters] are reached in order, and each
     * boundary becomes a leg end, so a list of two entries is a route with one
     * intermediate stop.
     */
    fun straightRoute(
        legMeters: List<Double> = listOf(400.0),
        profile: RouteProfile = RouteProfile.Walk,
        stepMeters: Double = STEP_METERS,
    ): Route {
        val total = legMeters.sum()
        val points = mutableListOf<GeoPoint>()
        var along = 0.0
        while (along < total) {
            points += pointAt(along)
            along += stepMeters
        }
        points += pointAt(total)
        val speed = NavigationConfig().speedMetersPerSecond(profile)
        val segments = mutableListOf<RouteSegment>()
        var startIndex = 0
        var startMeters = 0.0
        for (leg in legMeters) {
            val endMeters = startMeters + leg
            val endIndex = points.indexOfLast { distanceAlong(it) <= endMeters + stepMeters / 2.0 } + 1
            segments +=
                RouteSegment(
                    startIndex = startIndex,
                    endIndex = endIndex,
                    distanceMeters = leg,
                    durationSeconds = leg / speed,
                )
            startIndex = endIndex
            startMeters = endMeters
        }
        return Route(
            geometry = points,
            distanceMeters = total,
            durationSeconds = total / speed,
            segments = segments,
        )
    }

    /** One fix at [along] metres and [sidewaysMeters] south of the line. */
    fun fix(
        along: Double,
        sidewaysMeters: Double,
        second: Int,
        accuracyMeters: Double = FIX_ACCURACY_METERS,
    ): LocationFix {
        val onLine = pointAt(along)
        val position =
            if (sidewaysMeters == 0.0) {
                onLine
            } else {
                GeoMath.offset(onLine, SIDEWAYS_BEARING, sidewaysMeters)
            }
        return LocationFix(
            position = position,
            accuracyMeters = accuracyMeters,
            timeMillis = BASE_MILLIS + second * MILLIS_PER_SECOND,
        )
    }

    private fun pointAt(alongMeters: Double): GeoPoint = GeoMath.offset(ORIGIN, EAST_BEARING, alongMeters)

    private fun distanceAlong(point: GeoPoint): Double = GeoMath.distanceMeters(ORIGIN, point)

    private val ORIGIN = GeoPoint(ORIGIN_LATITUDE, ORIGIN_LONGITUDE)

    private const val EAST_BEARING = 90.0
    private const val MILLIS_PER_SECOND = 1_000L
}
