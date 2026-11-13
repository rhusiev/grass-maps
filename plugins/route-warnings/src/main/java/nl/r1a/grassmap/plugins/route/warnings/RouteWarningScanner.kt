package nl.r1a.grassmap.plugins.route.warnings

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.RegionAccess
import nl.r1a.grassmap.plugin.api.model.AttributeValue
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.WayAlertElementType
import nl.r1a.grassmap.plugin.api.model.WayAlertHit
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteAttributes
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteSegment
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The warning list for one planned route and one profile (T-0409).
 *
 * The ford, barrier and railway crossing come from a [RegionAccess] corridor
 * query over the region's way-alert component, not from a routing engine's
 * tiles: the engine's answer does not carry those nodes. Steps and a private
 * way come from the route's annotated segments, where the engine's
 * `trace_attributes` pass puts `highway=steps` and `access=private`.
 *
 * The result is ordered by distance along the route. A region node is listed
 * once even when the route meets it twice, so an out-and-back route does not
 * repeat the same crossing. Consecutive steps segments are one warning, since
 * one OSM way is cut into one segment per graph edge.
 *
 * A region failure is returned as a failure rather than silently dropping the
 * region half of the list: the caller must not show a complete-looking list
 * that is missing every ford and crossing.
 *
 * The segment attributes are the T-0301 `RoutingEngine.annotate` output, which
 * runs after the route is already returned. A route that is not annotated yet
 * is valid input: the region half is still listed and the steps and private-way
 * warnings are absent. A caller that needs the complete list waits for
 * annotation, or asks again after it.
 */
class RouteWarningScanner(
    private val region: RegionAccess,
) {
    suspend fun warnings(
        route: Route,
        profile: RouteProfile,
    ): Outcome<List<RouteWarning>> {
        if (profile !in RouteWarningPolicy.supportedProfiles) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        if (route.geometry.size < MIN_ROUTE_POINTS) {
            return Outcome.Success(emptyList())
        }
        val distances = alongRouteDistances(route) ?: return Outcome.Failure(AppError.InvalidInput)
        val hits =
            when (val outcome = region.wayAlertFeatures(route, RouteWarningPolicy.CORRIDOR_METERS)) {
                is Outcome.Failure -> return outcome
                is Outcome.Success -> outcome.value
            }
        val warnings =
            regionWarnings(hits).filter { profile in it.profiles } +
                segmentWarnings(route.segments, distances, profile)
        return Outcome.Success(sorted(warnings))
    }

    private fun regionWarnings(hits: List<WayAlertHit>): List<RouteWarning> {
        val byIdentity = linkedMapOf<WarningIdentity, RouteWarning>()
        for (hit in hits) {
            val kind = RouteWarningPolicy.kindOf(hit.feature.kind) ?: continue
            val identity = WarningIdentity(kind, hit.feature.elementType, hit.feature.osmId)
            val warning =
                RouteWarning(
                    kind = kind,
                    distanceAlongRouteMeters = hit.alongRouteMeters,
                    osmElementType = hit.feature.elementType,
                    osmId = hit.feature.osmId,
                )
            val existing = byIdentity[identity]
            if (existing == null || warning.distanceAlongRouteMeters < existing.distanceAlongRouteMeters) {
                byIdentity[identity] = warning
            }
        }
        return byIdentity.values.toList()
    }

    /**
     * The steps and private-way warnings. A run of consecutive segments of the
     * same kind is one warning at the run's first segment, because a graph edge
     * split does not make a second steps flight.
     */
    private fun segmentWarnings(
        segments: List<RouteSegment>,
        distances: DoubleArray,
        profile: RouteProfile,
    ): List<RouteWarning> {
        val result = mutableListOf<RouteWarning>()
        val lastEndByKind = mutableMapOf<RouteWarningKind, Int>()
        for (segment in segments.sortedBy { it.startIndex }) {
            val start = segment.startIndex
            if (start !in distances.indices) {
                continue
            }
            for (kind in segmentWarningKinds(segment.attributes)) {
                if (!RouteWarningPolicy.appliesTo(kind, profile)) {
                    continue
                }
                val previousEnd = lastEndByKind.put(kind, segment.endIndex)
                if (previousEnd == start) {
                    continue
                }
                result +=
                    RouteWarning(
                        kind = kind,
                        distanceAlongRouteMeters = distances[start],
                        profiles = RouteWarningPolicy.profilesFor(kind),
                    )
            }
        }
        return result
    }

    private fun segmentWarningKinds(attributes: Map<String, AttributeValue>): List<RouteWarningKind> =
        buildList {
            if (text(attributes, RouteAttributes.HIGHWAY) == STEPS) {
                add(RouteWarningKind.Steps)
            }
            if (text(attributes, RouteAttributes.ACCESS) == PRIVATE) {
                add(RouteWarningKind.PrivateWay)
            }
        }

    private fun text(
        attributes: Map<String, AttributeValue>,
        key: String,
    ): String? = (attributes[key] as? AttributeValue.TextValue)?.value

    /**
     * The cumulative distance to each shape point, in the route's own metre
     * scale, or null when a coordinate is not finite.
     */
    private fun alongRouteDistances(route: Route): DoubleArray? {
        val cumulative = DoubleArray(route.geometry.size)
        for (index in 1 until route.geometry.size) {
            cumulative[index] = cumulative[index - 1] + distanceMeters(route.geometry[index - 1], route.geometry[index])
        }
        val measured = cumulative.last()
        if (!measured.isFinite()) {
            return null
        }
        val scale =
            if (route.distanceMeters.isFinite() && route.distanceMeters > 0.0 && measured > 0.0) {
                route.distanceMeters / measured
            } else {
                1.0
            }
        for (index in cumulative.indices) {
            cumulative[index] *= scale
        }
        return cumulative
    }

    private fun sorted(warnings: List<RouteWarning>): List<RouteWarning> =
        warnings.sortedWith(
            compareBy(
                { it.distanceAlongRouteMeters },
                { it.kind.ordinal },
                { it.osmId ?: Long.MAX_VALUE },
                { it.osmElementType?.ordinal ?: Int.MAX_VALUE },
            ),
        )

    private data class WarningIdentity(
        val kind: RouteWarningKind,
        val elementType: WayAlertElementType,
        val osmId: Long,
    )

    private companion object {
        const val MIN_ROUTE_POINTS = 2
        const val STEPS = "steps"
        const val PRIVATE = "private"
        const val EARTH_RADIUS_METERS = 6_371_000.0

        fun distanceMeters(
            first: GeoPoint,
            second: GeoPoint,
        ): Double {
            val firstLatitude = Math.toRadians(first.latitude)
            val secondLatitude = Math.toRadians(second.latitude)
            val latitudeDelta = secondLatitude - firstLatitude
            val longitudeDelta = Math.toRadians(second.longitude - first.longitude)
            val haversine =
                sin(latitudeDelta / 2.0) * sin(latitudeDelta / 2.0) +
                    cos(firstLatitude) * cos(secondLatitude) *
                    sin(longitudeDelta / 2.0) * sin(longitudeDelta / 2.0)
            return 2.0 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(haversine)))
        }
    }
}
