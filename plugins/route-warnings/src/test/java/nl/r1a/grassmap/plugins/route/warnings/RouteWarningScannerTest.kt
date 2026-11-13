package nl.r1a.grassmap.plugins.route.warnings

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.NoRegionAccess
import nl.r1a.grassmap.plugin.api.host.RegionAccess
import nl.r1a.grassmap.plugin.api.model.AttributeValue
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.WayAlertElementType
import nl.r1a.grassmap.plugin.api.model.WayAlertFeature
import nl.r1a.grassmap.plugin.api.model.WayAlertFeatureKind
import nl.r1a.grassmap.plugin.api.model.WayAlertHit
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteAttributes
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteSegment
import nl.r1a.grassmap.plugin.api.testing.fixture.TestRegions
import nl.r1a.grassmap.regions.RegionReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Route warnings against the committed test region (T-0409). The route starts
 * west of the fixture's ford, turns over the barrier and the shared railway
 * crossing, then gets a steps segment and a private-access segment. The three
 * region features are read through the real [RegionReader]; the two route
 * attributes come from the route's own annotated segments.
 */
class RouteWarningScannerTest {
    private val reader = RegionReader(TestRegions.load().root.toFile())
    private val route = testRoute()

    @Test
    fun aRouteOverTheFiveKindsListsThemInDistanceOrder() =
        runTest {
            val warnings = warnings()

            assertEquals(
                listOf(
                    RouteWarningKind.Ford,
                    RouteWarningKind.Barrier,
                    RouteWarningKind.RailwayCrossing,
                    RouteWarningKind.Steps,
                    RouteWarningKind.PrivateWay,
                ),
                warnings.map { it.kind },
            )
            assertTrue(
                "the warnings must get farther along the route",
                warnings.zipWithNext().all { (first, second) ->
                    first.distanceAlongRouteMeters < second.distanceAlongRouteMeters
                },
            )
            assertTrue(warnings.all { it.distanceAlongRouteMeters > 0.0 })
        }

    @Test
    fun theRegionWarningsCarryTheirOsmIdentity() =
        runTest {
            val warnings = warnings()

            assertEquals(WayAlertElementType.Node, warnings[0].osmElementType)
            assertEquals(9010L, warnings[0].osmId)
            assertEquals(WayAlertElementType.Node, warnings[1].osmElementType)
            assertEquals(9005L, warnings[1].osmId)
            assertEquals(WayAlertElementType.Node, warnings[2].osmElementType)
            assertEquals(9007L, warnings[2].osmId)
            assertNull(warnings[3].osmElementType)
            assertNull(warnings[3].osmId)
            assertNull(warnings[4].osmElementType)
            assertNull(warnings[4].osmId)
        }

    @Test
    fun eachWarningCarriesTheProfilesItAppliesTo() =
        runTest {
            val warnings = warnings()

            assertEquals(
                setOf(RouteProfile.Walk, RouteProfile.Hike, RouteProfile.Bike),
                warnings[0].profiles,
            )
            assertEquals(setOf(RouteProfile.Walk, RouteProfile.Hike), warnings[1].profiles)
            assertEquals(
                setOf(RouteProfile.Walk, RouteProfile.Hike, RouteProfile.Bike),
                warnings[2].profiles,
            )
            assertEquals(
                setOf(RouteProfile.Walk, RouteProfile.Hike, RouteProfile.Bike),
                warnings[3].profiles,
            )
            assertEquals(
                setOf(RouteProfile.Walk, RouteProfile.Hike, RouteProfile.Bike),
                warnings[4].profiles,
            )
        }

    @Test
    fun theWalkProfileListsTheRailwayCrossing() =
        runTest {
            val warnings = warnings(profile = RouteProfile.Walk)

            assertEquals(
                listOf(
                    RouteWarningKind.Ford,
                    RouteWarningKind.Barrier,
                    RouteWarningKind.RailwayCrossing,
                    RouteWarningKind.Steps,
                    RouteWarningKind.PrivateWay,
                ),
                warnings.map { it.kind },
            )
        }

    @Test
    fun theBikeProfileDropsTheBarrier() =
        runTest {
            val warnings = warnings(profile = RouteProfile.Bike)

            assertEquals(
                listOf(
                    RouteWarningKind.Ford,
                    RouteWarningKind.RailwayCrossing,
                    RouteWarningKind.Steps,
                    RouteWarningKind.PrivateWay,
                ),
                warnings.map { it.kind },
            )
        }

    @Test
    fun aRepeatedNodeHitIsListedOnce() =
        runTest {
            val feature =
                WayAlertFeature(
                    kind = WayAlertFeatureKind.Barrier,
                    elementType = WayAlertElementType.Node,
                    osmId = 9005L,
                    geometry = listOf(GeoPoint(49.9510, 23.9460)),
                )
            val duplicated =
                FixedRegion(
                    Outcome.Success(
                        listOf(
                            WayAlertHit(feature, alongRouteMeters = 40.0, distanceFromRouteMeters = 0.0),
                            WayAlertHit(feature, alongRouteMeters = 10.0, distanceFromRouteMeters = 0.0),
                        ),
                    ),
                )

            val warnings =
                warnings(
                    route =
                        Route(
                            geometry = listOf(GeoPoint(49.9510, 23.9430), GeoPoint(49.9510, 23.9520)),
                            distanceMeters = 0.0,
                            durationSeconds = 0.0,
                        ),
                    region = duplicated,
                )

            assertEquals(1, warnings.size)
            assertEquals(10.0, warnings.single().distanceAlongRouteMeters, 0.0)
        }

    @Test
    fun adjacentStepsSegmentsAreOneWarning() =
        runTest {
            val stepsRoute =
                Route(
                    geometry =
                        listOf(
                            GeoPoint(49.9400, 23.9400),
                            GeoPoint(49.9400, 23.9410),
                            GeoPoint(49.9400, 23.9420),
                        ),
                    distanceMeters = 0.0,
                    durationSeconds = 0.0,
                    segments = listOf(segment(0, 1, highway = "steps"), segment(1, 2, highway = "steps")),
                )

            val warnings = warnings(route = stepsRoute, region = NoRegionAccess)

            assertEquals(listOf(RouteWarningKind.Steps), warnings.map { it.kind })
            assertEquals(0.0, warnings.single().distanceAlongRouteMeters, 0.0)
        }

    @Test
    fun aRouteWithNoneGivesAnEmptyList() =
        runTest {
            val quietRoute =
                Route(
                    geometry = listOf(GeoPoint(49.9400, 23.9400), GeoPoint(49.9405, 23.9405)),
                    distanceMeters = 0.0,
                    durationSeconds = 0.0,
                    segments = listOf(segment(0, 1, highway = "path")),
                )

            assertTrue(warnings(route = quietRoute).isEmpty())
        }

    @Test
    fun aNaNCoordinateIsInvalidInputNotAThrow() =
        runTest {
            val invalidRoute =
                Route(
                    geometry = listOf(GeoPoint(Double.NaN, 23.9430), GeoPoint(49.9510, 23.9440)),
                    distanceMeters = 0.0,
                    durationSeconds = 0.0,
                )

            val outcome = RouteWarningScanner(reader).warnings(invalidRoute, RouteProfile.Hike)

            assertEquals(Outcome.Failure(AppError.InvalidInput), outcome)
        }

    @Test
    fun theDeclaredRouteDistanceScalesBothWarningSources() =
        runTest {
            val unscaled = warnings()
            val scaledRoute = route.copy(distanceMeters = 2.0 * geometryLengthMeters(route.geometry))
            val scaled = warnings(route = scaledRoute)

            assertEquals(
                listOf(
                    RouteWarningKind.Ford,
                    RouteWarningKind.Barrier,
                    RouteWarningKind.RailwayCrossing,
                    RouteWarningKind.Steps,
                    RouteWarningKind.PrivateWay,
                ),
                scaled.map { it.kind },
            )
            for (index in unscaled.indices) {
                assertEquals(
                    "warning $index",
                    2.0 * unscaled[index].distanceAlongRouteMeters,
                    scaled[index].distanceAlongRouteMeters,
                    SCALE_DISTANCE_TOLERANCE_METERS,
                )
            }
        }

    @Test
    fun aRegionFailureIsReported() =
        runTest {
            val outcome =
                RouteWarningScanner(FixedRegion(Outcome.Failure(AppError.CorruptData)))
                    .warnings(route, RouteProfile.Hike)

            assertEquals(Outcome.Failure(AppError.CorruptData), outcome)
        }

    @Test
    fun theCarProfileIsInvalidInput() =
        runTest {
            val outcome = RouteWarningScanner(reader).warnings(route, RouteProfile.Car)

            assertEquals(Outcome.Failure(AppError.InvalidInput), outcome)
        }

    private suspend fun warnings(
        route: Route = this.route,
        profile: RouteProfile = RouteProfile.Hike,
        region: RegionAccess = reader,
    ): List<RouteWarning> {
        val outcome = RouteWarningScanner(region).warnings(route, profile)
        return (outcome as? Outcome.Success)?.value ?: error("the warning query failed: $outcome")
    }

    private fun testRoute(): Route =
        Route(
            geometry =
                listOf(
                    GeoPoint(49.9505, 23.9430),
                    GeoPoint(49.9510, 23.9440),
                    GeoPoint(49.9510, 23.9460),
                    GeoPoint(49.9515, 23.9460),
                    GeoPoint(49.9515, 23.9470),
                    GeoPoint(49.9515, 23.9490),
                    GeoPoint(49.9515, 23.9520),
                ),
            distanceMeters = 0.0,
            durationSeconds = 0.0,
            segments =
                listOf(
                    segment(0, 1, highway = "track"),
                    segment(1, 2, highway = "track"),
                    segment(2, 3, highway = "path"),
                    segment(3, 4, highway = "path"),
                    segment(4, 5, highway = "steps"),
                    segment(5, 6, highway = "path", access = "private"),
                ),
        )

    private fun segment(
        start: Int,
        end: Int,
        highway: String,
        access: String = "yes",
    ): RouteSegment =
        RouteSegment(
            startIndex = start,
            endIndex = end,
            distanceMeters = 0.0,
            durationSeconds = 0.0,
            attributes =
                mapOf(
                    RouteAttributes.SURFACE to AttributeValue.TextValue("ground"),
                    RouteAttributes.HIGHWAY to AttributeValue.TextValue(highway),
                    RouteAttributes.SAC_SCALE to AttributeValue.TextValue("hiking"),
                    RouteAttributes.ACCESS to AttributeValue.TextValue(access),
                ),
        )

    private class FixedRegion(
        private val outcome: Outcome<List<WayAlertHit>>,
    ) : RegionAccess {
        override suspend fun wayAlertFeatures(
            route: Route,
            corridorMeters: Double,
        ): Outcome<List<WayAlertHit>> = outcome
    }

    private fun geometryLengthMeters(points: List<GeoPoint>): Double =
        points.zipWithNext().sumOf { (first, second) -> distanceMeters(first, second) }

    private fun distanceMeters(
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

    private companion object {
        const val SCALE_DISTANCE_TOLERANCE_METERS = 5.0
        const val EARTH_RADIUS_METERS = 6_371_000.0
    }
}
