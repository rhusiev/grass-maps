package nl.r1a.grassmap.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.alerts.BeepHost
import nl.r1a.grassmap.navigation.GeometryRouteFollower
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.RegionAccess
import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.model.ProximityPlaceKind
import nl.r1a.grassmap.plugin.api.model.WayAlertElementType
import nl.r1a.grassmap.plugin.api.model.WayAlertFeature
import nl.r1a.grassmap.plugin.api.model.WayAlertFeatureKind
import nl.r1a.grassmap.plugin.api.model.WayAlertHit
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugins.proximity.alerts.ProximityAlertCue
import nl.r1a.grassmap.plugins.proximity.alerts.ProximityAlertHost
import nl.r1a.grassmap.plugins.proximity.alerts.ProximityPlace
import nl.r1a.grassmap.plugins.proximity.alerts.ProximityPlaceSource
import nl.r1a.grassmap.plugins.proximity.alerts.ProximityPlaces
import nl.r1a.grassmap.plugins.proximity.alerts.ProximityState
import nl.r1a.grassmap.plugins.way.alerts.WayAlertCue
import nl.r1a.grassmap.plugins.way.alerts.WayAlertHost
import nl.r1a.grassmap.plugins.way.alerts.WayAlertPhase
import nl.r1a.grassmap.plugins.way.alerts.WayAlertState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The one app-level start path (T-0413).
 *
 * Guidance is only audible when the tone source and both alert hosts are
 * prepared for the session. The planner's Start and the follow-track Start
 * live in Compose callbacks that a JVM test cannot reach, so the first test
 * reads their call sites, the way `AppLinkWiringTest` does. The other tests
 * drive [GuidanceStarter] with the real hosts and a fake region and place
 * source, which is the level at which "prepared" is observable.
 */
class GuidanceStartTest {
    @Test
    fun everyStartPathUsesTheOneGuidanceEntryPoint() {
        val mapScreen = File(MAP_SCREEN_SOURCE).readText()
        val navCommands = File(NAV_COMMANDS_SOURCE).readText()
        val container = File(APP_CONTAINER_SOURCE).readText()

        assertEquals(
            "the planner and the follow-track sheet start through the entry point",
            2,
            Regex("""container\.guidance\.start\(""").findAll(mapScreen).count(),
        )
        assertTrue(
            "the followed track keeps its own reroute choice",
            "container.guidance.start(route, RouteProfile.Walk, reroute = false)" in mapScreen,
        )
        assertFalse(
            "no UI path starts the controller on its own",
            "container.navigation.start(" in mapScreen,
        )
        assertTrue(
            "the debug command starts through the entry point",
            "guidance.start(selection.route, selection.profile)" in navCommands,
        )
        assertFalse(
            "the debug command no longer prepares the hosts itself",
            "beeps().setProfile" in navCommands,
        )
        assertTrue(
            "the container wires the one entry point",
            "GuidanceStarter(navigation, { beeps }, { wayAlerts }, { proximityAlerts })" in container,
        )
    }

    @Test
    fun aStartPointsTheToneSourceAndBothAlertHostsAtTheProfile() =
        runTest {
            val harness = Harness(backgroundScope)
            harness.wayAlerts.setProfile(RouteProfile.Hike)
            harness.wayAlerts.onState(FOLLOWING_STATE)
            harness.proximityAlerts.onState(PROXIMITY_STATE)
            assertEquals(1, harness.region.queries)
            assertEquals(1, harness.source.lookups)
            assertEquals(1, harness.wayCues.size)
            assertEquals(1, harness.placeCues.size)

            val started = harness.starter.start(ROUTE, RouteProfile.Bike)

            assertTrue(started.valueOrFail())
            assertEquals(RouteProfile.Bike, harness.beeps.profile)

            harness.wayAlerts.onState(FOLLOWING_STATE)
            harness.proximityAlerts.onState(PROXIMITY_STATE)
            assertEquals("the way-alert cache is cleared", 2, harness.region.queries)
            assertEquals("the place cache is cleared", 2, harness.source.lookups)
            assertEquals("the way alert is armed again", 2, harness.wayCues.size)
            assertEquals("the place is latched again", 2, harness.placeCues.size)

            harness.controller.stop()
            runCurrent()
            advanceUntilIdle()
        }

    @Test
    fun aStartWhileASessionRunsLeavesItsLatchesAndProfileAlone() =
        runTest {
            val harness = Harness(backgroundScope)
            harness.starter.start(ROUTE, RouteProfile.Bike).valueOrFail()
            harness.wayAlerts.onState(FOLLOWING_STATE)
            assertEquals(1, harness.region.queries)
            assertEquals(1, harness.wayCues.size)

            val second = harness.starter.start(OTHER_ROUTE, RouteProfile.Walk)

            assertFalse("the controller reports no new session", second.valueOrFail())
            assertEquals(1, harness.service.starts)
            assertEquals("the profile stays the running session's", RouteProfile.Bike, harness.beeps.profile)
            assertEquals("the route cache stays", 1, harness.region.queries)
            assertEquals("the running route stays", ROUTE, harness.controller.state.value.activeRoute)

            harness.wayAlerts.onState(FOLLOWING_STATE)
            assertEquals("the announced feature stays announced", 1, harness.wayCues.size)

            harness.controller.stop()
            runCurrent()
            advanceUntilIdle()
        }

    @Test
    fun aRefusedStartLeavesTheHostsResetForTheNextAttempt() =
        runTest {
            val harness = Harness(backgroundScope, hasPermission = false, requiresPermission = true)
            harness.wayAlerts.setProfile(RouteProfile.Hike)
            harness.wayAlerts.onState(FOLLOWING_STATE)
            harness.proximityAlerts.onState(PROXIMITY_STATE)

            val refused = harness.starter.start(ROUTE, RouteProfile.Bike)

            assertEquals(Outcome.Failure(AppError.PermissionDenied), refused)
            assertFalse(harness.controller.isActive)
            assertEquals("the attempted profile is kept", RouteProfile.Bike, harness.beeps.profile)

            harness.wayAlerts.onState(FOLLOWING_STATE)
            harness.proximityAlerts.onState(PROXIMITY_STATE)
            assertEquals("the way-alert cache is reset", 2, harness.region.queries)
            assertEquals("the place cache is reset", 2, harness.source.lookups)
        }

    private class Harness(
        scope: CoroutineScope,
        hasPermission: Boolean = true,
        requiresPermission: Boolean = false,
    ) {
        private val fixes = MutableSharedFlow<LocationFix?>(extraBufferCapacity = FIX_BUFFER)
        val service = RecordingServiceHandle()
        val controller =
            NavigationController(
                scope = scope,
                fixes = fixes,
                hasLocationPermission = { hasPermission },
                requiresLocationPermission = { requiresPermission },
                routing = { null },
                service = service,
                followerFactory = { GeometryRouteFollower() },
            )
        val beeps = BeepHost()
        val region = RecordingRegion()
        val wayCues = mutableListOf<WayAlertCue>()
        val wayAlerts = WayAlertHost(region, sink = { cue -> wayCues += cue })
        val source = RecordingPlaces()
        val placeCues = mutableListOf<ProximityAlertCue>()
        val proximityAlerts =
            ProximityAlertHost(
                source,
                canAlert = { true },
                sink = { cue -> placeCues += cue },
            )
        val starter = GuidanceStarter(controller, { beeps }, { wayAlerts }, { proximityAlerts })
    }

    private class RecordingServiceHandle : NavigationServiceHandle {
        var starts: Int = 0
            private set

        override fun start() {
            starts += 1
        }

        override fun stop() = Unit
    }

    /** One railway crossing 10 m along the route, inside the bike distance. */
    private class RecordingRegion : RegionAccess {
        var queries: Int = 0
            private set

        override suspend fun wayAlertFeatures(
            route: Route,
            corridorMeters: Double,
        ): Outcome<List<WayAlertHit>> {
            queries += 1
            return Outcome.Success(listOf(HIT))
        }
    }

    private class RecordingPlaces : ProximityPlaceSource {
        var lookups: Int = 0
            private set

        override suspend fun places(
            box: BoundingBox,
            poiKinds: Set<String>,
        ): ProximityPlaces {
            lookups += 1
            return ProximityPlaces(listOf(PLACE))
        }
    }

    private fun <T> Outcome<T>.valueOrFail(): T =
        when (this) {
            is Outcome.Success -> value
            is Outcome.Failure -> throw AssertionError("expected success, got $error")
        }

    private companion object {
        const val FIX_BUFFER = 8

        const val MAP_SCREEN_SOURCE = "src/main/java/nl/r1a/grassmap/app/MapScreen.kt"
        const val NAV_COMMANDS_SOURCE = "src/main/java/nl/r1a/grassmap/app/NavCommands.kt"
        const val APP_CONTAINER_SOURCE = "src/main/java/nl/r1a/grassmap/app/AppContainer.kt"

        val ROUTE =
            Route(
                geometry = listOf(GeoPoint(49.949, 23.943), GeoPoint(49.949, 23.953)),
                distanceMeters = 720.0,
                durationSeconds = 120.0,
            )
        val OTHER_ROUTE =
            Route(
                geometry = listOf(GeoPoint(49.939, 23.941), GeoPoint(49.9455, 23.94985)),
                distanceMeters = 800.0,
                durationSeconds = 700.0,
            )
        val CENTRE = GeoPoint(49.949, 23.949)

        val HIT =
            WayAlertHit(
                feature =
                    WayAlertFeature(
                        kind = WayAlertFeatureKind.RailwayCrossing,
                        elementType = WayAlertElementType.Way,
                        osmId = 1L,
                        geometry = listOf(GeoPoint(49.949, 23.947), GeoPoint(49.949, 23.948)),
                    ),
                alongRouteMeters = 10.0,
                distanceFromRouteMeters = 2.0,
            )
        val PLACE =
            ProximityPlace(
                kind = ProximityPlaceKind.Favourite,
                id = "home",
                position = CENTRE,
                name = "Home",
            )
        val FOLLOWING_STATE =
            WayAlertState(
                phase = WayAlertPhase.Following,
                routeRevision = 0L,
                route = ROUTE,
                alongRouteMeters = 0.0,
                fixTimeMillis = FIX_TIME_MILLIS,
            )
        val PROXIMITY_STATE =
            ProximityState(
                active = true,
                fixTimeMillis = FIX_TIME_MILLIS,
                position = CENTRE,
            )
        const val FIX_TIME_MILLIS = 1_700_000_000_000L
    }
}
