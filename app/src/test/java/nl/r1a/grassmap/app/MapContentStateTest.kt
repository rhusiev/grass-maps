package nl.r1a.grassmap.app

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.navigation.NavigationPhase
import nl.r1a.grassmap.navigation.NavigationState
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two decisions the map content makes without reading the per-fix
 * navigation state: whether the navigation screen owns the map, and which
 * route the map draws. Both are read in composition, so the map screen sees
 * only their result and a fix does not recompose it.
 *
 * The recomposition count itself needs a device or an emulator and is owned by
 * the frame bench; this holds the values that count would guard.
 */
class MapContentStateTest {
    @Test
    fun anIdleSessionWithoutAFixtureLeavesTheMapToTheMapScreen() {
        assertFalse(isNavigationActive(fixture = null, state = NavigationState()))
    }

    @Test
    fun everyLivePhaseOfATripKeepsTheScreenUp() {
        for (phase in ACTIVE_PHASES) {
            val state = NavigationState(phase = phase)
            assertTrue(phase.name, isNavigationActive(fixture = null, state = state))
        }
    }

    @Test
    fun aShownFixtureOwnsTheMapWhateverTheLivePhase() {
        val fixture = NavigationScreenFixture(state = NavigationState())

        assertTrue(isNavigationActive(fixture, NavigationState()))
    }

    @Test
    fun aTripDrawsItsOwnRouteEvenWhenAPlanExists() {
        val route =
            mapRouteToDraw(
                navigationActive = true,
                activeRoute = TRIP_ROUTE,
                plannedRoute = PLAN_ROUTE,
            )

        assertEquals(TRIP_ROUTE, route)
    }

    @Test
    fun aTripWithNoRouteYetDrawsNoPlanEither() {
        assertNull(
            mapRouteToDraw(
                navigationActive = true,
                activeRoute = null,
                plannedRoute = PLAN_ROUTE,
            ),
        )
    }

    @Test
    fun afterTheTripTheMapDrawsThePlanAgain() {
        val route =
            mapRouteToDraw(
                navigationActive = false,
                activeRoute = TRIP_ROUTE,
                plannedRoute = PLAN_ROUTE,
            )

        assertEquals(PLAN_ROUTE, route)
    }

    @Test
    fun noTripAndNoPlanDrawsNothing() {
        assertNull(mapRouteToDraw(navigationActive = false, activeRoute = null, plannedRoute = null))
    }

    @Test
    fun aShownFixtureDrawsItsOwnRouteWhileTheLiveStateHasNone() =
        runTest {
            val fixture = NavigationScreenFixture(state = NavigationState(activeRoute = TRIP_ROUTE))

            val routes = navigationRoutes(flowOf(fixture), flowOf(NavigationState())).toList()

            assertEquals(listOf<Route?>(TRIP_ROUTE), routes)
        }

    @Test
    fun withoutAFixtureTheLiveRouteIsDrawn() =
        runTest {
            val live = NavigationState(activeRoute = PLAN_ROUTE)

            val routes = navigationRoutes(flowOf(null), flowOf(live)).toList()

            assertEquals(listOf<Route?>(PLAN_ROUTE), routes)
        }

    @Test
    fun clearingTheFixtureFallsBackToTheLiveRoute() =
        runTest {
            val fixture = NavigationScreenFixture(state = NavigationState(activeRoute = TRIP_ROUTE))
            val live = NavigationState(activeRoute = PLAN_ROUTE)

            val routes = navigationRoutes(flowOf(fixture, null), flowOf(live)).toList()

            assertEquals(PLAN_ROUTE, routes.last())
        }

    @Test
    fun theFitWaitsUntilTheSheetHasHeldItsHeight() =
        runTest {
            val growing =
                flow {
                    emit(0)
                    delay(40)
                    emit(300)
                    delay(40)
                    emit(520)
                    awaitCancellation()
                }

            val settled = settledBottomInsetPx(growing)

            assertEquals(520, settled)
            assertEquals(40L + 40L + 150L, testScheduler.currentTime)
        }

    @Test
    fun aCameraMovedDuringTheWaitIsNotFittedOver() =
        runTest {
            var camera = CAMERA
            val fits = mutableListOf<Int>()
            val sheet =
                flow {
                    emit(520)
                    awaitCancellation()
                }

            fitAfterSheetSettles(sheet, { camera }) { sheetPx -> fits += sheetPx }
            assertEquals(listOf(520), fits)

            fits.clear()
            val panning =
                flow {
                    emit(520)
                    camera = CAMERA.copy(zoom = CAMERA.zoom + 1.0)
                    awaitCancellation()
                }
            fitAfterSheetSettles(panning, { camera }) { sheetPx -> fits += sheetPx }

            assertEquals(emptyList<Int>(), fits)
        }

    @Test
    fun aMapWithNoSheetFitsTheWholeViewportAfterTheTimeout() =
        runTest {
            val never =
                flow {
                    emit(0)
                    awaitCancellation()
                }

            assertEquals(0, settledBottomInsetPx(never))
        }

    private companion object {
        val CAMERA = Camera(center = GeoPoint(49.84, 24.03), zoom = 14.0)
        val TRIP_ROUTE = route(60.0)
        val PLAN_ROUTE = route(120.0)
        val ACTIVE_PHASES =
            listOf(
                NavigationPhase.Following,
                NavigationPhase.Rerouting,
                NavigationPhase.Paused,
                NavigationPhase.Arrived,
            )

        /** Two routes that differ, so a wrong pick is visible in the assertion. */
        fun route(distanceMeters: Double): Route =
            Route(
                geometry = listOf(GeoPoint(49.8400, 24.0300), GeoPoint(49.8401, 24.0301)),
                distanceMeters = distanceMeters,
                durationSeconds = 43.0,
            )
    }
}
