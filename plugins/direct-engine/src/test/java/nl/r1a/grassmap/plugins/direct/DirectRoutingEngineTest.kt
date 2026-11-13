package nl.r1a.grassmap.plugins.direct

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteModifier
import nl.r1a.grassmap.plugin.api.provider.RouteModifierKind
import nl.r1a.grassmap.plugin.api.provider.RouteOptions
import nl.r1a.grassmap.plugin.api.provider.RoutePreference
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteRequest
import nl.r1a.grassmap.plugin.api.provider.RoutingEngineContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

class DirectRoutingEngineTest {
    private val engine = DirectRoutingEngine()

    @Test
    fun theLengthOfAKnownPairIsTheHaversineOfItsEnds() =
        runTest {
            val route = engine.routeTo(listOf(LVIV, TERNOPIL)).single()

            assertEquals(haversineMetres(LVIV, TERNOPIL), route.distanceMeters, 1e-6)
            assertEquals(LVIV, route.geometry.first())
            assertEquals(TERNOPIL, route.geometry.last())
            assertEquals(route.distanceMeters / WALK_SPEED_METRES_PER_SECOND, route.durationSeconds, 1e-9)
        }

    @Test
    fun theLineSamplesTheGeodesicAtOneVertexPerKilometre() =
        runTest {
            val route = engine.routeTo(listOf(LVIV, TERNOPIL)).single()

            val expectedPoints = ceil(route.distanceMeters / MAX_VERTEX_SPACING_METRES).toInt() + 1
            assertEquals(expectedPoints, route.geometry.size)

            val sampled = route.geometry.zipWithNext { from, to -> haversineMetres(from, to) }.sum()
            assertEquals(route.distanceMeters, sampled, SAMPLED_TOLERANCE_METRES)
        }

    @Test
    fun viasBecomeLegsInVisitOrder() =
        runTest {
            val start = GeoPoint(49.80, 24.00)
            val via = GeoPoint(49.90, 24.20)
            val end = GeoPoint(49.80, 24.40)

            val route = engine.routeTo(listOf(start, via, end)).single()

            assertEquals(2, route.segments.size)
            val first = route.segments[0]
            val second = route.segments[1]
            assertEquals(start, route.geometry.subList(first.startIndex, first.endIndex).first())
            assertEquals(via, route.geometry.subList(first.startIndex, first.endIndex).last())
            assertEquals(via, route.geometry.subList(second.startIndex, second.endIndex).first())
            assertEquals(end, route.geometry.subList(second.startIndex, second.endIndex).last())
            assertEquals(haversineMetres(start, via), first.distanceMeters, 1e-9)
            assertEquals(haversineMetres(via, end), second.distanceMeters, 1e-9)
            assertEquals(haversineMetres(start, via) + haversineMetres(via, end), route.distanceMeters, 1e-9)
            assertEquals(1, route.geometry.count { it == via })

            // The via has not been straightened away: the ordered legs are much
            // longer than the straight line from the first point to the last.
            assertTrue(route.distanceMeters > haversineMetres(start, end) + 1_000.0)
        }

    @Test
    fun aGeodesicCarriesNoInstructions() =
        runTest {
            val route = engine.routeTo(listOf(LVIV, TERNOPIL)).single()

            assertTrue(route.instructions.isEmpty())
            assertEquals(route.distanceMeters, route.segments.single().distanceMeters, 1e-9)
            assertEquals(route.durationSeconds, route.segments.single().durationSeconds, 1e-9)
        }

    @Test
    fun anAlternativeHasNoAnswerToReturn() =
        runTest {
            val request = requestOf(listOf(LVIV, TERNOPIL)).copy(alternatives = 1)

            assertEquals(Outcome.Failure(AppError.InvalidInput), engine.route(request, CancellationHandle.None))
        }

    @Test
    fun aCostOverlayIsOutsideWhatTheEngineDeclares() =
        runTest {
            val modifier = RouteModifier(geometry = listOf(LVIV, TERNOPIL), multiplier = 0.5)
            val request = requestOf(listOf(LVIV, TERNOPIL)).copy(modifiers = listOf(modifier))

            assertEquals(Outcome.Failure(AppError.Unsupported), engine.route(request, CancellationHandle.None))
        }

    @Test
    fun preferencesDoNotChangeAGeodesicBecauseThereIsNoCosting() =
        runTest {
            val plain = engine.routeTo(listOf(LVIV, TERNOPIL)).single()
            val request =
                requestOf(listOf(LVIV, TERNOPIL)).copy(
                    options =
                        RouteOptions(
                            hills = RoutePreference.Avoid,
                            busyRoads = RoutePreference.DontMind,
                            roughSurfaces = RoutePreference.Avoid,
                        ),
                )

            val preferred =
                when (val outcome = engine.route(request, CancellationHandle.None)) {
                    is Outcome.Success -> outcome.value.single()
                    is Outcome.Failure -> throw AssertionError("expected a route, got ${outcome.error}")
                }

            assertEquals(plain, preferred)
        }

    @Test
    fun carIsNotDeclared() =
        runTest {
            val request = requestOf(listOf(LVIV, TERNOPIL)).copy(profile = RouteProfile.Car)

            assertEquals(Outcome.Failure(AppError.Unsupported), engine.route(request, CancellationHandle.None))
        }

    @Test
    fun aChainBeyondTheDeclaredMaximumIsInvalidInput() =
        runTest {
            val request = requestOf(listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 3.0)))

            assertEquals(Outcome.Failure(AppError.InvalidInput), engine.route(request, CancellationHandle.None))
        }

    @Test
    fun aLineAcrossTheAntimeridianIsRefusedNotFolded() =
        runTest {
            val request = requestOf(listOf(GeoPoint(0.0, 179.9), GeoPoint(0.0, -179.9)))

            assertEquals(Outcome.Failure(AppError.Unsupported), engine.route(request, CancellationHandle.None))
        }

    @Test
    fun twoPointsCloserThanAMetreAreInvalidInput() =
        runTest {
            val request =
                requestOf(
                    listOf(
                        GeoPoint(49.84, 24.03),
                        GeoPoint(49.840001, 24.03),
                    ),
                )

            assertEquals(Outcome.Failure(AppError.InvalidInput), engine.route(request, CancellationHandle.None))
        }

    @Test
    fun anAlreadyCancelledCallDoesNoWork() =
        runTest {
            val result = engine.route(requestOf(listOf(LVIV, TERNOPIL)), CancellationHandle { true })

            assertEquals(Outcome.Failure(AppError.Cancelled), result)
        }

    @Test
    fun annotateReturnsTheRouteExactlyAsItCameIn() =
        runTest {
            val route = engine.routeTo(listOf(LVIV, TERNOPIL)).single()

            val annotated = engine.annotate(route, RouteProfile.Walk, CancellationHandle.None)

            assertEquals(Outcome.Success(route), annotated)
            assertEquals(emptySet<String>(), engine.capabilities().attributeKeys)
        }

    @Test
    fun annotateHonoursAnAlreadyCancelledCall() =
        runTest {
            val route = engine.routeTo(listOf(LVIV, TERNOPIL)).single()

            val annotated = engine.annotate(route, RouteProfile.Walk, CancellationHandle { true })

            assertEquals(Outcome.Failure(AppError.Cancelled), annotated)
        }

    @Test
    fun theCapabilitiesAnswerForAnEngineWithNoMapData() {
        val capabilities = engine.capabilities()

        assertEquals(RoutingEngineContract.REFERENCE, engine.contract)
        assertEquals(setOf(RouteProfile.Walk, RouteProfile.Bike, RouteProfile.Hike), capabilities.profiles)
        assertEquals(emptySet<RouteModifierKind>(), capabilities.modifierKinds)
        assertEquals(0, capabilities.maxAlternatives)
        assertEquals(MAX_DISTANCE_METRES, capabilities.maxDistanceMeters, 0.0)
        assertEquals(emptySet<String>(), capabilities.attributeKeys)
        assertFalse(capabilities.hasMatchTrack)
    }

    @Test
    fun theDurationFollowsTheDeclaredSpeedForEachAcceptedProfile() =
        runTest {
            val speeds =
                mapOf(
                    RouteProfile.Walk to WALK_SPEED_METRES_PER_SECOND,
                    RouteProfile.Bike to 4.2,
                    RouteProfile.Hike to 1.1,
                )

            for ((profile, speed) in speeds) {
                val route = engine.routeTo(listOf(LVIV, TERNOPIL), profile = profile).single()
                assertEquals(route.distanceMeters / speed, route.durationSeconds, 1e-9)
            }
        }

    private suspend fun DirectRoutingEngine.routeTo(
        waypoints: List<GeoPoint>,
        profile: RouteProfile = RouteProfile.Walk,
    ): List<Route> =
        when (val outcome = route(requestOf(waypoints).copy(profile = profile), CancellationHandle.None)) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("expected a route, got ${outcome.error}")
        }

    private fun requestOf(waypoints: List<GeoPoint>): RouteRequest = RouteRequest(waypoints = waypoints, profile = RouteProfile.Walk)

    private companion object {
        const val WALK_SPEED_METRES_PER_SECOND = 1.4
        const val MAX_DISTANCE_METRES = 250_000.0
        const val MAX_VERTEX_SPACING_METRES = 1_000.0

        /** The sampled line may differ from its own geodesic by at most 1 mm. */
        const val SAMPLED_TOLERANCE_METRES = 1e-3
    }
}
