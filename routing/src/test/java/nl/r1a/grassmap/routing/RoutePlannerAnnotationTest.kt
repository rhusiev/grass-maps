package nl.r1a.grassmap.routing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.AttributeValue
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.RoutePlanPoint
import nl.r1a.grassmap.plugin.api.model.RoutePlanRole
import nl.r1a.grassmap.plugin.api.model.RoutePlanState
import nl.r1a.grassmap.plugin.api.model.RoutePlanStatus
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteAttributes
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteSegment
import nl.r1a.grassmap.plugin.api.service.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The annotate pass after a route is produced (T-0409). The route is published
 * first and its segments are replaced when the engine answers, so the preview
 * never waits and the totals, selection and history stay as produced.
 */
class RoutePlannerAnnotationTest {
    @Test
    fun theRouteAppearsFirstAndIsAnnotatedAfterwards() =
        runTest {
            val annotated = mutableListOf<Route>()
            val planner =
                planner(annotate = { route ->
                    annotated += route
                    Outcome.Success(route.copy(segments = ANNOTATED_SEGMENTS))
                })

            val published = plan(planner)

            assertEquals(RoutePlanStatus.Ready, published.status)
            assertTrue(
                "the engine's route is published unannotated",
                published.selected!!.segments.none { segment -> segment.attributes.isNotEmpty() },
            )
            assertTrue("the annotate pass has not run yet", annotated.isEmpty())

            advanceUntilIdle()

            assertEquals(
                ANNOTATED_SEGMENTS,
                planner.state.value.selected!!
                    .segments,
            )
            assertEquals(listOf(UNANNOTATED_SEGMENTS), annotated.map { route -> route.segments })
        }

    @Test
    fun annotationChangesNothingButTheSegments() =
        runTest {
            val planner = planner(annotate = { route -> Outcome.Success(route.copy(segments = ANNOTATED_SEGMENTS)) })

            val published = plan(planner)
            advanceUntilIdle()

            val current = planner.state.value
            assertEquals(published.selected!!.geometry, current.selected!!.geometry)
            assertEquals(published.selected!!.distanceMeters, current.selected!!.distanceMeters, 0.0)
            assertEquals(published.selected!!.durationSeconds, current.selected!!.durationSeconds, 0.0)
            assertEquals(published.selected!!.arrivalEpochMillis, current.selected!!.arrivalEpochMillis)
            assertEquals(published.selected!!.instructions, current.selected!!.instructions)
            assertEquals(published.options.size, current.options.size)
            assertEquals(published.options.drop(1), current.options.drop(1))
            assertEquals(published.selectedIndex, current.selectedIndex)
            assertEquals(published.generation, current.generation)
            assertEquals(published.status, current.status)
            assertEquals(published.stops, current.stops)
            assertEquals(published.vias, current.vias)
            assertEquals(published.undoAvailable, current.undoAvailable)
            assertEquals(published.redoAvailable, current.redoAvailable)
        }

    @Test
    fun oneProducedRouteIsAnnotatedOnce() =
        runTest {
            var calls = 0
            val planner =
                planner(annotate = { route ->
                    calls += 1
                    Outcome.Success(route.copy(segments = ANNOTATED_SEGMENTS))
                })

            plan(planner)
            advanceUntilIdle()
            assertEquals(1, calls)

            planner.select(0)
            advanceUntilIdle()
            assertEquals("re-selecting the same option asks once", 1, calls)

            planner.select(0)
            advanceUntilIdle()
            assertEquals(1, calls)
        }

    @Test
    fun selectingAnAlternativeAnnotatesThatOption() =
        runTest {
            val annotated = mutableListOf<GeoPoint>()
            val planner =
                planner(annotate = { route ->
                    annotated += route.geometry.first()
                    Outcome.Success(route.copy(segments = ANNOTATED_SEGMENTS))
                })
            plan(planner)
            advanceUntilIdle()
            val primary =
                planner.state.value.selected!!
                    .segments

            planner.select(1)
            advanceUntilIdle()

            assertEquals(listOf(START, ALTERNATIVE_START), annotated)
            assertEquals(
                ANNOTATED_SEGMENTS,
                planner.state.value.selected!!
                    .segments,
            )
            assertEquals(
                "the first option keeps its own annotation",
                primary,
                planner.state.value.options[0]
                    .segments,
            )
            assertEquals(1, planner.state.value.selectedIndex)
        }

    @Test
    fun anAnnotateFailureKeepsTheRouteAndLogs() =
        runTest {
            val logged = mutableListOf<String>()
            val planner =
                planner(
                    annotate = { Outcome.Failure(AppError.CorruptData) },
                    log = { message -> logged += message },
                )

            val published = plan(planner)
            advanceUntilIdle()

            val current = planner.state.value
            assertEquals(UNANNOTATED_SEGMENTS, current.selected!!.segments)
            assertEquals(published.selected, current.selected)
            assertEquals(RoutePlanStatus.Ready, current.status)
            assertEquals(listOf("route annotation failed: ${AppError.CorruptData}"), logged)
        }

    @Test
    fun anAnswerForASupersededRouteIsDropped() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val planner =
                planner(annotate = { route ->
                    if (route.distanceMeters == PRIMARY.distanceMeters) {
                        gate.await()
                        Outcome.Success(route.copy(segments = ANNOTATED_SEGMENTS))
                    } else {
                        Outcome.Success(route.copy(segments = VIA_ANNOTATED_SEGMENTS))
                    }
                })

            plan(planner)
            runCurrent()
            planner.addVia(RoutePlanPoint(VIA, "via"))
            runCurrent()
            gate.complete(Unit)
            advanceUntilIdle()

            val current = planner.state.value
            assertEquals(1, current.vias.size)
            assertEquals(VIA_ANNOTATED_SEGMENTS, current.selected!!.segments)
        }

    @Test
    fun undoRestoresAnUnannotatedRouteAndAsksAgain() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            var first = true
            val planner =
                planner(annotate = { route ->
                    if (first) {
                        first = false
                        gate.await()
                    }
                    Outcome.Success(route.copy(segments = ANNOTATED_SEGMENTS))
                })

            plan(planner)
            runCurrent()
            planner.addVia(RoutePlanPoint(VIA, "via"))
            runCurrent()
            gate.complete(Unit)
            advanceUntilIdle()

            planner.undo()
            advanceUntilIdle()

            assertTrue(
                "the undo restored the plan before the via",
                planner.state.value.vias
                    .isEmpty(),
            )
            assertEquals(
                "the restored route is annotated on its own ticket",
                ANNOTATED_SEGMENTS,
                planner.state.value.selected!!
                    .segments,
            )
        }

    private fun TestScope.planner(
        annotate: suspend (Route) -> Outcome<Route>,
        log: (String) -> Unit = {},
    ): RoutePlanner =
        RoutePlanner(
            requestRoute = { request, _ ->
                if (request.waypoints.size > 2) {
                    Outcome.Success(listOf(VIAS_ROUTE))
                } else {
                    Outcome.Success(listOf(PRIMARY, ALTERNATIVE))
                }
            },
            annotateRoute = { route, _, _ -> annotate(route) },
            annotationScope = this,
            log = log,
            clock = Clock { NOW_MILLIS },
            profile = RouteProfile.Walk,
        )

    private suspend fun plan(planner: RoutePlanner): RoutePlanState {
        planner.setPoint(RoutePlanRole.Start, RoutePlanPoint(START, "start"))
        planner.setPoint(RoutePlanRole.Destination, RoutePlanPoint(END, "destination"))
        return planner.state.value
    }

    private companion object {
        const val NOW_MILLIS = 1_700_000_000_000L
        val START = GeoPoint(49.9505, 23.9430)
        val END = GeoPoint(49.9515, 23.9520)
        val VIA = GeoPoint(49.9510, 23.9460)
        val ALTERNATIVE_START = GeoPoint(49.9490, 23.9420)

        val STEPS_SEGMENT =
            RouteSegment(
                startIndex = 4,
                endIndex = 5,
                distanceMeters = 100.0,
                durationSeconds = 60.0,
                attributes = mapOf(RouteAttributes.HIGHWAY to AttributeValue.TextValue("steps")),
            )

        val PRIVATE_SEGMENT =
            RouteSegment(
                startIndex = 5,
                endIndex = 6,
                distanceMeters = 100.0,
                durationSeconds = 60.0,
                attributes = mapOf(RouteAttributes.ACCESS to AttributeValue.TextValue("private")),
            )

        val UNANNOTATED_SEGMENTS =
            listOf(RouteSegment(startIndex = 0, endIndex = 6, distanceMeters = 600.0, durationSeconds = 360.0))

        val ANNOTATED_SEGMENTS = UNANNOTATED_SEGMENTS + STEPS_SEGMENT + PRIVATE_SEGMENT

        val VIA_ANNOTATED_SEGMENTS =
            listOf(
                RouteSegment(
                    startIndex = 0,
                    endIndex = 2,
                    distanceMeters = 700.0,
                    durationSeconds = 420.0,
                    attributes = mapOf(RouteAttributes.HIGHWAY to AttributeValue.TextValue("track")),
                ),
            )

        val PRIMARY =
            Route(
                geometry = listOf(START, VIA, END),
                distanceMeters = 600.0,
                durationSeconds = 360.0,
                segments = UNANNOTATED_SEGMENTS,
            )

        val ALTERNATIVE =
            Route(
                geometry = listOf(ALTERNATIVE_START, GeoPoint(49.9510, 23.9480), END),
                distanceMeters = 640.0,
                durationSeconds = 380.0,
                segments = UNANNOTATED_SEGMENTS,
            )

        val VIAS_ROUTE =
            Route(
                geometry = listOf(START, VIA, GeoPoint(49.9512, 23.9500), END),
                distanceMeters = 700.0,
                durationSeconds = 420.0,
                segments =
                    listOf(
                        RouteSegment(
                            startIndex = 0,
                            endIndex = 3,
                            distanceMeters = 700.0,
                            durationSeconds = 420.0,
                        ),
                    ),
            )
    }
}
