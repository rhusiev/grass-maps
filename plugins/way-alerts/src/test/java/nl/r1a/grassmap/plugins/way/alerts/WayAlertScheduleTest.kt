package nl.r1a.grassmap.plugins.way.alerts

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.alerts.AlertEvent
import nl.r1a.grassmap.alerts.AlertHost
import nl.r1a.grassmap.alerts.AlertKind
import nl.r1a.grassmap.alerts.AlertSettings
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.WayAlertFeatureKind
import nl.r1a.grassmap.plugin.api.model.WayAlertHit
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.testing.fixture.TestRegions
import nl.r1a.grassmap.regions.RegionReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Way alerts against the committed test region (T-0319). The route is the
 * straight synthetic line from `49.949, 23.943` to `49.949, 23.953`; the
 * fixture's crossing, tunnel and hazard lie on it, and a second hazard lies
 * 167 m north of it. Every query goes through the real [RegionReader], so the
 * corridor and the component parsing are exercised, not mocked.
 */
class WayAlertScheduleTest {
    private val route =
        Route(
            geometry = listOf(START, END),
            distanceMeters = 0.0,
            durationSeconds = 0.0,
        )
    private val reader = RegionReader(TestRegions.load().root.toFile())
    private val tunnelRoute = routeFrom(TUNNEL_START_LONGITUDE)
    private val hazardRoute = routeFrom(HAZARD_START_LONGITUDE)

    @Test
    fun theDistancesAreTheDocumentedOnes() {
        assertEquals(150.0, WayAlertDistance.metersFor(RouteProfile.Bike, WayAlertFeatureKind.RailwayCrossing)!!, 0.0)
        assertEquals(200.0, WayAlertDistance.metersFor(RouteProfile.Bike, WayAlertFeatureKind.Tunnel)!!, 0.0)
        assertEquals(100.0, WayAlertDistance.metersFor(RouteProfile.Bike, WayAlertFeatureKind.Hazard)!!, 0.0)
        assertEquals(100.0, WayAlertDistance.metersFor(RouteProfile.Bike, WayAlertFeatureKind.Ford)!!, 0.0)
        assertEquals(100.0, WayAlertDistance.metersFor(RouteProfile.Bike, WayAlertFeatureKind.Barrier)!!, 0.0)
        assertEquals(50.0, WayAlertDistance.metersFor(RouteProfile.Hike, WayAlertFeatureKind.RailwayCrossing)!!, 0.0)
        assertNull(WayAlertDistance.metersFor(RouteProfile.Hike, WayAlertFeatureKind.Tunnel))
        assertNull(WayAlertDistance.metersFor(RouteProfile.Walk, WayAlertFeatureKind.Hazard))
        assertNull(WayAlertDistance.metersFor(RouteProfile.Car, WayAlertFeatureKind.RailwayCrossing))
    }

    @Test
    fun aRailwayCrossingAlertsAtTheSetDistance() =
        runTest {
            val hit = hit(route, CROSSING_ID)
            val schedule = scheduleOf(RouteProfile.Bike)

            assertTrue(schedule.onState(state(hit.alongRouteMeters - 151.0)).isEmpty())

            val cue = schedule.onState(state(hit.alongRouteMeters - 150.0)).single()

            assertEquals(CROSSING_ID, cue.feature.osmId)
            assertEquals(150.0, cue.distanceAheadMeters, 0.01)
        }

    @Test
    fun aTunnelAlertsAtTheSetDistance() =
        runTest {
            val hit = hit(tunnelRoute, TUNNEL_ID)
            val schedule = scheduleOf(RouteProfile.Bike)

            assertTrue(schedule.onState(state(hit.alongRouteMeters - 201.0, route = tunnelRoute)).isEmpty())

            val cue = schedule.onState(state(hit.alongRouteMeters - 200.0, route = tunnelRoute)).single()

            assertEquals(TUNNEL_ID, cue.feature.osmId)
            assertEquals(WayAlertFeatureKind.Tunnel, cue.feature.kind)
            assertEquals(200.0, cue.distanceAheadMeters, 0.01)
        }

    @Test
    fun aHazardNodeAlertsAtTheSetDistance() =
        runTest {
            val hit = hit(hazardRoute, HAZARD_ID)
            val schedule = scheduleOf(RouteProfile.Bike)

            assertTrue(schedule.onState(state(hit.alongRouteMeters - 101.0, route = hazardRoute)).isEmpty())

            val cue = schedule.onState(state(hit.alongRouteMeters - 100.0, route = hazardRoute)).single()

            assertEquals(HAZARD_ID, cue.feature.osmId)
            assertEquals(100.0, cue.distanceAheadMeters, 0.01)
        }

    @Test
    fun aTunnelIsAnnouncedForBikeOnly() =
        runTest {
            val hit = hit(tunnelRoute, TUNNEL_ID)
            val along = state(hit.alongRouteMeters - 200.0, route = tunnelRoute)

            assertTrue(scheduleOf(RouteProfile.Walk).onState(along).isEmpty())
            assertTrue(scheduleOf(RouteProfile.Hike).onState(along).isEmpty())
            assertEquals(
                WayAlertFeatureKind.Tunnel,
                scheduleOf(RouteProfile.Bike)
                    .onState(along)
                    .single()
                    .feature.kind,
            )
        }

    @Test
    fun aFeatureOutsideTheCorridorDoesNotAlert() =
        runTest {
            val schedule = scheduleOf(RouteProfile.Bike)
            val cues = mutableListOf<WayAlertCue>()
            var along = 0.0
            while (along <= ROUTE_LENGTH_METERS) {
                cues += schedule.onState(state(along))
                along += STEP_METERS
            }

            assertTrue("the 167 m hazard is outside the 30 m corridor", cues.none { it.feature.osmId == OFF_CORRIDOR_ID })
            assertEquals(listOf(CROSSING_ID, TUNNEL_ID, HAZARD_ID), cues.map { it.feature.osmId })
        }

    @Test
    fun aFeatureIsAnnouncedOncePerRouteRevision() =
        runTest {
            val hit = hit(route, CROSSING_ID)
            val schedule = scheduleOf(RouteProfile.Bike)

            assertEquals(1, schedule.onState(state(hit.alongRouteMeters - 150.0)).size)

            repeat(3) {
                assertTrue(schedule.onState(state(hit.alongRouteMeters - 149.0)).isEmpty())
            }
        }

    @Test
    fun aRerouteRearmsTheFeature() =
        runTest {
            val hit = hit(route, CROSSING_ID)
            val schedule = scheduleOf(RouteProfile.Bike)
            val first = state(hit.alongRouteMeters - 150.0, revision = 0L)

            assertEquals(1, schedule.onState(first).size)
            assertEquals(1, schedule.onState(first.copy(routeRevision = 1L)).size)
        }

    @Test
    fun aNewTripWithTheSameRevisionReArmsTheFeature() =
        runTest {
            val hit = hit(route, CROSSING_ID)
            val schedule = scheduleOf(RouteProfile.Bike)
            val approach = state(hit.alongRouteMeters - 150.0, revision = 0L)

            assertEquals(1, schedule.onState(approach).size)
            assertTrue(schedule.onState(state(0.0, phase = WayAlertPhase.Stopped)).isEmpty())
            assertEquals(1, schedule.onState(approach).size)
        }

    @Test
    fun aFeatureFirstSeenInsideTheDistanceAlertsAtOnce() =
        runTest {
            val hit = hit(hazardRoute, HAZARD_ID)
            val schedule = scheduleOf(RouteProfile.Bike)

            val cue = schedule.onState(state(hit.alongRouteMeters - 20.0, route = hazardRoute)).single()

            assertEquals(HAZARD_ID, cue.feature.osmId)
            assertEquals(20.0, cue.distanceAheadMeters, 0.01)
        }

    @Test
    fun aPausedStateDoesNotAnnounceAndDoesNotLoseTheFeature() =
        runTest {
            val hit = hit(route, CROSSING_ID)
            val schedule = scheduleOf(RouteProfile.Bike)
            val along = hit.alongRouteMeters - 150.0

            assertTrue(schedule.onState(state(along, phase = WayAlertPhase.Paused)).isEmpty())
            assertEquals(1, schedule.onState(state(along)).size)
        }

    @Test
    fun allOffEmitsNothingThroughTheTypedAlertPath() =
        runTest {
            val alerts = AlertHost(AlertSettings.ALL_OFF)
            val events = collectEvents(alerts)
            val host = hostInto(alerts)
            host.setProfile(RouteProfile.Bike)

            forEachDueFeature { osmId, along ->
                host.onState(state(along))
                assertTrue("$osmId must not alert with every switch off", events.isEmpty())
            }

            assertTrue(events.isEmpty())
        }

    @Test
    fun withSwitchesOnTheTypedBannerCarriesTheFeatureDistance() =
        runTest {
            val alerts = AlertHost(AlertSettings(tunnel = true, railwayCrossing = true, hazard = true))
            val events = collectEvents(alerts)
            val host = hostInto(alerts)
            host.setProfile(RouteProfile.Bike)

            host.onState(state(hit(route, CROSSING_ID).alongRouteMeters - 150.0))
            host.onState(state(hit(route, TUNNEL_ID).alongRouteMeters - 200.0))
            host.onState(state(hit(route, HAZARD_ID).alongRouteMeters - 100.0))

            assertEquals(
                listOf(AlertKind.RailwayCrossing, AlertKind.Tunnel, AlertKind.Hazard),
                events.map { it.kind },
            )
            assertEquals(150.0, events[0].banner.featureDistanceMeters!!, 0.01)
            assertEquals(200.0, events[1].banner.featureDistanceMeters!!, 0.01)
            assertEquals(100.0, events[2].banner.featureDistanceMeters!!, 0.01)
            assertEquals(listOf(1L, 2L, 3L), events.map { it.sequence })
            assertTrue(events.all { it.effects.size == 2 })
        }

    private suspend fun forEachDueFeature(action: suspend (Long, Double) -> Unit) {
        action(CROSSING_ID, hit(route, CROSSING_ID).alongRouteMeters - 150.0)
        action(TUNNEL_ID, hit(route, TUNNEL_ID).alongRouteMeters - 200.0)
        action(HAZARD_ID, hit(route, HAZARD_ID).alongRouteMeters - 100.0)
    }

    private fun hostInto(alerts: AlertHost): WayAlertHost =
        WayAlertHost(
            region = reader,
            sink =
                WayAlertSink { cue ->
                    alerts.onWayAlert(
                        kind = AlertKind.forWayFeature(cue.feature.kind),
                        fixTimeMillis = cue.fixTimeMillis,
                        distanceToFeatureMeters = cue.distanceAheadMeters,
                        key = cue.key,
                    )
                },
        )

    private fun scheduleOf(profile: RouteProfile): WayAlertSchedule = WayAlertSchedule(reader).also { it.setProfile(profile) }

    private suspend fun hit(
        route: Route,
        osmId: Long,
    ): WayAlertHit {
        val outcome = reader.wayAlertFeatures(route, WayAlertDistance.CORRIDOR_METERS)
        val hits = (outcome as? Outcome.Success)?.value ?: error("the fixture query failed: $outcome")
        return hits.single { it.feature.osmId == osmId }
    }

    private fun routeFrom(longitude: Double): Route =
        Route(
            geometry = listOf(GeoPoint(49.9490, longitude), END),
            distanceMeters = 0.0,
            durationSeconds = 0.0,
        )

    private fun state(
        along: Double,
        revision: Long = 0L,
        phase: WayAlertPhase = WayAlertPhase.Following,
        route: Route = this.route,
    ): WayAlertState =
        WayAlertState(
            phase = phase,
            routeRevision = revision,
            route = route,
            alongRouteMeters = along,
            fixTimeMillis = 1_700_000_000_000L,
        )

    private fun TestScope.collectEvents(host: AlertHost): MutableList<AlertEvent> {
        val events = mutableListOf<AlertEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler), start = CoroutineStart.UNDISPATCHED) {
            host.events.collect { events += it }
        }
        return events
    }

    private companion object {
        const val CROSSING_ID = 9001L
        const val TUNNEL_ID = 9002L
        const val HAZARD_ID = 9003L
        const val OFF_CORRIDOR_ID = 9004L
        const val ROUTE_LENGTH_METERS = 720.0
        const val STEP_METERS = 5.0
        const val TUNNEL_START_LONGITUDE = 23.9480
        const val HAZARD_START_LONGITUDE = 23.9518
        val START = GeoPoint(49.9490, 23.9430)
        val END = GeoPoint(49.9490, 23.9530)
    }
}
