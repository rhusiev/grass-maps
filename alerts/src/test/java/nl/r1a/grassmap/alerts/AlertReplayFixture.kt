package nl.r1a.grassmap.alerts

import nl.r1a.grassmap.navigation.NavigationConfig
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The deterministic fix timeline the alert tests replay (T-0317).
 *
 * It is the same kind of fixture as the T-0315 simulator: one fix per simulated
 * second, placed by distance along a line, with epoch timestamps and no clock
 * reading or sleeping. The trip goes on route, leaves the line far enough for
 * the session's five-metre test threshold, spends the walk reroute budget,
 * receives the replacement route, loses the signal for [GAP_MILLIS], and ends
 * at the destination.
 *
 * The replacement starts at the fix that installs it, so it is built from that
 * fix's position to the original destination. The reroute index is the one the
 * walk budget produces: 21 m of off-route travel after the sideways step,
 * which the gate refuses to credit, arrives at the 22 m threshold on the 22nd
 * off-route fix.
 */
internal class AlertReplayFixture {
    /** One replay: the line, the replacement the router returns, and the fixes. */
    data class Journey(
        val route: Route,
        val replacement: Route,
        val fixes: List<LocationFix>,
        val gapFixIndex: Int,
        val gapMillis: Long,
    )

    companion object {
        const val BASE_MILLIS: Long = 1_700_000_000_000L
        const val CADENCE_MILLIS: Long = 1_000L
        const val GAP_MILLIS: Long = 15_000L
        const val FIX_ACCURACY_METERS: Double = 5.0
        const val ROUTE_METERS: Double = 300.0

        private const val SAMPLE_METERS = 10.0
        private const val SIDEWAYS_METERS = 20.0
        private const val ON_ROUTE_FIXES = 10
        private const val OFF_ROUTE_FIXES = 22
        private const val GAP_AFTER_REPLACEMENT_FIXES = 30
        private const val ARRIVAL_MARGIN_METERS = 5.0
        private const val EAST = 90.0
        private const val SOUTH = 180.0
        private const val EARTH_RADIUS_METERS = 6_371_000.0

        /** The session threshold that makes the 20 m detour off route. */
        val navigationConfig: NavigationConfig = NavigationConfig(maxDeviationMeters = 5.0)

        private val origin = GeoPoint(latitude = 49.84, longitude = 24.03)
        private val destination = offset(origin, EAST, ROUTE_METERS)
        private val stepMeters = navigationConfig.speedMetersPerSecond(RouteProfile.Walk)

        fun build(): Journey {
            val route = lineRoute(origin, destination)
            val fixes = mutableListOf<LocationFix>()
            var timeMillis = BASE_MILLIS
            var gapFixIndex = -1

            fun add(position: GeoPoint) {
                fixes +=
                    LocationFix(
                        position = position,
                        accuracyMeters = FIX_ACCURACY_METERS,
                        timeMillis = timeMillis,
                    )
                timeMillis += CADENCE_MILLIS
            }

            repeat(ON_ROUTE_FIXES) { index ->
                add(offset(origin, EAST, index * stepMeters))
            }
            repeat(OFF_ROUTE_FIXES) { index ->
                val along = (ON_ROUTE_FIXES + index) * stepMeters
                add(offset(offset(origin, EAST, along), SOUTH, SIDEWAYS_METERS))
            }

            val replacementStart = fixes.last().position
            val replacement = lineRoute(replacementStart, destination)
            var along = stepMeters
            var replacementFixes = 0
            while (along <= replacement.distanceMeters - ARRIVAL_MARGIN_METERS) {
                if (replacementFixes == GAP_AFTER_REPLACEMENT_FIXES) {
                    timeMillis = fixes.last().timeMillis + GAP_MILLIS
                    gapFixIndex = fixes.size
                }
                add(offsetAlong(replacementStart, destination, along))
                replacementFixes += 1
                along += stepMeters
            }
            add(destination)

            require(gapFixIndex > 0) { "the timeline must contain the signal gap" }

            return Journey(
                route = route,
                replacement = replacement,
                fixes = fixes,
                gapFixIndex = gapFixIndex,
                gapMillis = GAP_MILLIS,
            )
        }

        /** The trip's gap is real: the fix before it and after it differ by [GAP_MILLIS]. */
        fun gapOf(journey: Journey): Long {
            val after = journey.fixes[journey.gapFixIndex].timeMillis
            val before = journey.fixes[journey.gapFixIndex - 1].timeMillis
            return after - before
        }

        private fun lineRoute(
            start: GeoPoint,
            end: GeoPoint,
        ): Route {
            val total = distanceMeters(start, end)
            val geometry = mutableListOf<GeoPoint>()
            var along = 0.0
            while (along < total) {
                geometry += offsetAlong(start, end, along)
                along += SAMPLE_METERS
            }
            geometry += end
            return Route(
                geometry = geometry,
                distanceMeters = total,
                durationSeconds = total / stepMeters,
            )
        }

        private fun offsetAlong(
            start: GeoPoint,
            end: GeoPoint,
            distanceMeters: Double,
        ): GeoPoint = offset(start, bearingDegrees(start, end), distanceMeters)

        private fun distanceMeters(
            from: GeoPoint,
            to: GeoPoint,
        ): Double {
            val fromLat = Math.toRadians(from.latitude)
            val toLat = Math.toRadians(to.latitude)
            val deltaLat = toLat - fromLat
            val deltaLon = Math.toRadians(to.longitude - from.longitude)
            val a =
                sin(deltaLat / 2) * sin(deltaLat / 2) +
                    cos(fromLat) * cos(toLat) * sin(deltaLon / 2) * sin(deltaLon / 2)
            return 2 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
        }

        private fun bearingDegrees(
            from: GeoPoint,
            to: GeoPoint,
        ): Double {
            val fromLat = Math.toRadians(from.latitude)
            val toLat = Math.toRadians(to.latitude)
            val deltaLon = Math.toRadians(to.longitude - from.longitude)
            val y = sin(deltaLon) * cos(toLat)
            val x = cos(fromLat) * sin(toLat) - sin(fromLat) * cos(toLat) * cos(deltaLon)
            return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
        }

        private fun offset(
            origin: GeoPoint,
            bearing: Double,
            distanceMeters: Double,
        ): GeoPoint {
            val angular = distanceMeters / EARTH_RADIUS_METERS
            val radians = Math.toRadians(bearing)
            val latitude = Math.toRadians(origin.latitude)
            val longitude = Math.toRadians(origin.longitude)
            val targetLat = asin(sin(latitude) * cos(angular) + cos(latitude) * sin(angular) * cos(radians))
            val targetLon =
                longitude +
                    atan2(
                        sin(radians) * sin(angular) * cos(latitude),
                        cos(angular) - sin(latitude) * sin(targetLat),
                    )
            val wrapped = ((Math.toDegrees(targetLon) + 540.0) % 360.0) - 180.0
            return GeoPoint(latitude = Math.toDegrees(targetLat), longitude = wrapped)
        }
    }
}
