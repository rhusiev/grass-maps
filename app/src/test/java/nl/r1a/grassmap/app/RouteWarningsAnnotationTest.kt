package nl.r1a.grassmap.app

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.RoutePlanPoint
import nl.r1a.grassmap.plugin.api.model.RoutePlanRole
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.service.Clock
import nl.r1a.grassmap.plugin.api.testing.fixture.TestRegions
import nl.r1a.grassmap.plugins.route.warnings.RouteWarning
import nl.r1a.grassmap.plugins.route.warnings.RouteWarningScanner
import nl.r1a.grassmap.regions.RegionReader
import nl.r1a.grassmap.routing.RoutePlanner
import nl.r1a.grassmap.routing.selectedRoute
import nl.r1a.grassmap.ui.shell.RouteWarningGlyph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The annotate pass feeding the warning list (T-0409). The engine's route
 * answer carries no attributes, so only an annotated route can produce steps
 * and private-way warnings; the real scanner over the committed region is what
 * proves the mapping, and the cache's scan count proves the pass causes exactly
 * one rescan.
 */
class RouteWarningsAnnotationTest {
    private val scanner = RouteWarningScanner(RegionReader(TestRegions.load().root.toFile()))

    @Test
    fun theAnnotatedRouteAddsStepsAndThePrivateWay() =
        runTest {
            val planner = planner()
            plan(planner)
            val unannotated = planner.state.value.selectedRoute() ?: error("no route was published")

            advanceUntilIdle()

            val annotated = planner.state.value.selectedRoute() ?: error("the route disappeared")
            assertEquals(
                "the engine's answer lists the region features only",
                listOf(
                    RouteWarningGlyph.Ford,
                    RouteWarningGlyph.Barrier,
                    RouteWarningGlyph.RailwayCrossing,
                ),
                glyphs(scanner.warnings(unannotated, RouteProfile.Walk).success()),
            )
            assertEquals(
                listOf(
                    RouteWarningGlyph.Ford,
                    RouteWarningGlyph.Barrier,
                    RouteWarningGlyph.RailwayCrossing,
                    RouteWarningGlyph.Steps,
                    RouteWarningGlyph.PrivateWay,
                ),
                glyphs(scanner.warnings(annotated, RouteProfile.Walk).success()),
            )
            assertEquals(unannotated.geometry, annotated.geometry)
            assertEquals(unannotated.distanceMeters, annotated.distanceMeters, 0.0)
            assertEquals(unannotated.durationSeconds, annotated.durationSeconds, 0.0)
            assertEquals(unannotated.instructions, annotated.instructions)
        }

    @Test
    fun theAnnotatedRouteIsScannedExactlyOnceAndNoRowGoesStale() =
        runTest {
            val planner = planner()
            plan(planner)
            val unannotated = planner.state.value.selectedRoute() ?: error("no route was published")
            advanceUntilIdle()
            val annotated = planner.state.value.selectedRoute() ?: error("the route disappeared")
            assertNotEquals("the pass must change what the cache keys on", unannotated, annotated)

            var scans = 0
            val cache =
                RouteWarningCache(
                    scan = { route, profile ->
                        scans += 1
                        scanner.warnings(route, profile)
                    },
                    log = {},
                )

            val before = cache.rows(unannotated, RouteProfile.Walk)
            val after = cache.rows(annotated, RouteProfile.Walk)
            val repeated = cache.rows(annotated, RouteProfile.Walk)

            assertEquals("one scan per produced route", 2, scans)
            assertEquals(after, repeated)
            assertTrue(RouteWarningGlyph.Steps in after.map { row -> row.glyph })
            assertTrue(RouteWarningGlyph.Steps !in before.map { row -> row.glyph })
            assertTrue(
                "the unannotated snapshot must not show the annotated route's rows",
                RouteWarningsSnapshot(unannotated, RouteProfile.Walk, before)
                    .rowsFor(annotated, RouteProfile.Walk)
                    .isEmpty(),
            )
            assertEquals(
                after,
                RouteWarningsSnapshot(annotated, RouteProfile.Walk, after)
                    .rowsFor(annotated, RouteProfile.Walk),
            )
        }

    private fun TestScope.planner(): RoutePlanner {
        val engineRoute = RouteWarningFixtures.UNANNOTATED_ROUTE.copy(distanceMeters = ROUTE_DISTANCE_METERS)
        return RoutePlanner(
            requestRoute = { _, _ -> Outcome.Success(listOf(engineRoute)) },
            annotateRoute = { route, _, _ ->
                Outcome.Success(
                    route.copy(segments = RouteWarningFixtures.ANNOTATED_ROUTE.segments),
                )
            },
            annotationScope = this,
            clock = Clock { ROUTE_CLOCK_MILLIS },
            profile = RouteProfile.Walk,
        )
    }

    private suspend fun plan(planner: RoutePlanner) {
        planner.setPoint(RoutePlanRole.Start, RoutePlanPoint(RouteWarningFixtures.START, "start"))
        planner.setPoint(
            RoutePlanRole.Destination,
            RoutePlanPoint(RouteWarningFixtures.ANNOTATED_ROUTE.geometry.last(), "destination"),
        )
    }

    private fun glyphs(warnings: List<RouteWarning>): List<RouteWarningGlyph> = routeWarningRows(warnings).map { row -> row.glyph }

    private fun Outcome<List<RouteWarning>>.success(): List<RouteWarning> =
        (this as? Outcome.Success)?.value ?: error("the warning query failed: $this")

    private companion object {
        const val ROUTE_DISTANCE_METERS = 1_200.0
        const val ROUTE_CLOCK_MILLIS = 1_700_000_000_000L
    }
}
