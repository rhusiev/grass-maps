package nl.r1a.grassmap.plugins.route.planner

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.NoRouteHistoryAccess
import nl.r1a.grassmap.plugin.api.host.RoutingEngineAccess
import nl.r1a.grassmap.plugin.api.model.AttributeValue
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.RoutePlanRole
import nl.r1a.grassmap.plugin.api.model.RoutePlanStatus
import nl.r1a.grassmap.plugin.api.provider.ProviderContract
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteAttributes
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteRequest
import nl.r1a.grassmap.plugin.api.provider.RouteSegment
import nl.r1a.grassmap.plugin.api.provider.RoutingCapabilities
import nl.r1a.grassmap.plugin.api.provider.RoutingEngine
import nl.r1a.grassmap.plugin.api.provider.RoutingEngineContract
import nl.r1a.grassmap.plugin.api.service.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session's annotate pass (T-0409): the route the planner produced is
 * handed back to the engine the host resolved, so the per-edge attributes come
 * from the engine that planned the line.
 */
class RoutePlannerSessionAnnotationTest {
    @Test
    fun theResolvedEngineAnnotatesTheProducedRoute() =
        runTest {
            val engine = AnnotatingEngine()
            val session =
                RoutePlannerSession(
                    engines = RoutingEngineAccess { Outcome.Success(engine) },
                    clock = Clock { 1_700_000_000_000L },
                    history = NoRouteHistoryAccess,
                    annotationScope = this,
                )

            plan(session)
            advanceUntilIdle()

            assertEquals(1, engine.annotateCalls)
            assertEquals(1, engine.annotated.size)
            assertEquals(UNANNOTATED_SEGMENTS, engine.annotated.single().segments)
            assertEquals(RouteProfile.Walk, engine.annotatedProfiles.single())
            assertEquals(
                ANNOTATED_SEGMENTS,
                session.state.value.selected!!
                    .segments,
            )
            assertEquals(RoutePlanStatus.Ready, session.state.value.status)
        }

    @Test
    fun anEngineFailureLeavesThePublishedRouteAndLogs() =
        runTest {
            val engine = AnnotatingEngine(failure = true)
            val logged = mutableListOf<String>()
            val session =
                RoutePlannerSession(
                    engines = RoutingEngineAccess { Outcome.Success(engine) },
                    clock = Clock { 1_700_000_000_000L },
                    history = NoRouteHistoryAccess,
                    annotationScope = this,
                    log = { message -> logged += message },
                )

            plan(session)
            advanceUntilIdle()

            assertEquals(1, engine.annotateCalls)
            assertEquals(
                UNANNOTATED_SEGMENTS,
                session.state.value.selected!!
                    .segments,
            )
            assertTrue(logged.single().startsWith("route annotation failed:"))
        }

    private suspend fun plan(session: RoutePlannerSession) {
        session.begin()
        session.setPoint(RoutePlanRole.Start, START, "start")
        session.setPoint(RoutePlanRole.Destination, END, "destination")
    }

    private class AnnotatingEngine(
        private val failure: Boolean = false,
    ) : RoutingEngine {
        var annotateCalls = 0
        val annotated = mutableListOf<Route>()
        val annotatedProfiles = mutableListOf<RouteProfile>()

        override val contract: ProviderContract = RoutingEngineContract.REFERENCE

        override fun capabilities(): RoutingCapabilities =
            RoutingCapabilities(
                profiles = setOf(RouteProfile.Walk),
                modifierKinds = emptySet(),
                maxAlternatives = 0,
                maxDistanceMeters = 250_000.0,
            )

        override suspend fun route(
            request: RouteRequest,
            cancellation: CancellationHandle,
        ): Outcome<List<Route>> = Outcome.Success(listOf(ROUTE))

        override suspend fun annotate(
            route: Route,
            profile: RouteProfile,
            cancellation: CancellationHandle,
        ): Outcome<Route> {
            annotateCalls += 1
            annotated += route
            annotatedProfiles += profile
            return if (failure) {
                Outcome.Failure(AppError.CorruptData)
            } else {
                Outcome.Success(route.copy(segments = ANNOTATED_SEGMENTS))
            }
        }
    }

    private companion object {
        val START = GeoPoint(49.9505, 23.9430)
        val END = GeoPoint(49.9515, 23.9520)

        val UNANNOTATED_SEGMENTS =
            listOf(RouteSegment(startIndex = 0, endIndex = 2, distanceMeters = 600.0, durationSeconds = 360.0))

        val ANNOTATED_SEGMENTS =
            listOf(
                RouteSegment(
                    startIndex = 0,
                    endIndex = 1,
                    distanceMeters = 300.0,
                    durationSeconds = 180.0,
                    attributes = mapOf(RouteAttributes.HIGHWAY to AttributeValue.TextValue("steps")),
                ),
                RouteSegment(
                    startIndex = 1,
                    endIndex = 2,
                    distanceMeters = 300.0,
                    durationSeconds = 180.0,
                    attributes = mapOf(RouteAttributes.ACCESS to AttributeValue.TextValue("private")),
                ),
            )

        val ROUTE =
            Route(
                geometry = listOf(START, GeoPoint(49.9510, 23.9475), END),
                distanceMeters = 600.0,
                durationSeconds = 360.0,
                segments = UNANNOTATED_SEGMENTS,
            )
    }
}
