package nl.r1a.grassmaps.plugins.way.alerts

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmaps.alerts.AlertEvent
import nl.r1a.grassmaps.alerts.AlertHost
import nl.r1a.grassmaps.alerts.AlertKind
import nl.r1a.grassmaps.alerts.AlertSettings
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.model.WayAlertFeatureKind
import nl.r1a.grassmaps.plugin.api.model.WayAlertHit
import nl.r1a.grassmaps.plugin.api.provider.Route
import nl.r1a.grassmaps.plugin.api.provider.RouteProfile
import nl.r1a.grassmaps.plugin.api.testing.fixture.TestRegions
import nl.r1a.grassmaps.regions.RegionReader
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
        assertEquals(100.0, WayAlertDistance.metersFor(RouteProfile.Walk, WayAlertFeatureKind.RailwayCrossing)!!, 0.0)
        assertEquals(
            listOf(100.0, 50.0),
            WayAlertDistance.stagesFor(RouteProfile.Walk, WayAlertFeatureKind.RailwayCrossing),
        )
        assertEquals(listOf(150.0), WayAlertDistance.stagesFor(RouteProfile.Bike, WayAlertFeatureKind.RailwayCrossing))
        assertEquals(listOf(50.0), WayAlertDistance.stagesFor(RouteProfile.Hike, WayAlertFeatureKind.RailwayCrossing))
        assertNull(WayAlertDistance.metersFor(RouteProfile.Walk, WayAlertFeatureKind.Hazard))
        assertNull(WayAlertDistance.metersFor(RouteProfile.Walk, WayAlertFeatureKind.Tunnel))
        assertNull(WayAlertDistance.metersFor(RouteProfile.Car, WayAlertFeatureKind.RailwayCrossing))
        assertEquals(
            "the re-arm distance is chosen and named, not a literal inside the rule",
            50.0,
            WayAlertDistance.RELEASE_METERS,
            0.0,
        )
    }

    @Test
    fun aWalkerIsAlertedTwiceAtACrossingAt100mAnd50m() =
        runTest {
            val hit = hit(route, CROSSING_ID)
            val schedule = scheduleOf(RouteProfile.Walk)

            assertTrue(schedule.onState(state(hit.alongRouteMeters - 101.0)).isEmpty())
            val first = schedule.onState(state(hit.alongRouteMeters - 100.0)).single()
            assertEquals(100.0, first.distanceAheadMeters, 0.01)
            assertTrue(schedule.onState(state(hit.alongRouteMeters - 51.0)).isEmpty())
            val second = schedule.onState(state(hit.alongRouteMeters - 50.0)).single()
            assertEquals(50.0, second.distanceAheadMeters, 0.01)
            assertEquals(CROSSING_ID, second.feature.osmId)
            assertTrue(schedule.onState(state(hit.alongRouteMeters - 20.0)).isEmpty())
        }

    @Test
    fun eachWalkStageAnnouncesOnceWhenTheWalkerLingersOrJitters() =
        runTest {
            val hit = hit(route, CROSSING_ID)
            val schedule = scheduleOf(RouteProfile.Walk)
            val aheadSequence = listOf(100.0, 100.0, 101.0, 99.0, 100.0, 60.0, 50.0, 51.0, 49.0, 50.0, 50.0, 70.0, 49.5, 30.0)

            val cues = aheadSequence.flatMap { schedule.onState(state(hit.alongRouteMeters - it)) }

            assertEquals(listOf(100.0, 50.0), cues.map { it.distanceAheadMeters })
        }

    @Test
    fun aWalkerFirstSeenInsideTheSecondStageGetsOneCue() =
        runTest {
            val hit = hit(route, CROSSING_ID)
            val schedule = scheduleOf(RouteProfile.Walk)

            assertEquals(1, schedule.onState(state(hit.alongRouteMeters - 40.0)).size)
            assertTrue(schedule.onState(state(hit.alongRouteMeters - 30.0)).isEmpty())
        }

    @Test
    fun aWalkerStartingInsideTheFirstStageStillGetsTheSecond() =
        runTest {
            val hit = hit(route, CROSSING_ID)
            val schedule = scheduleOf(RouteProfile.Walk)

            assertEquals(80.0, schedule.onState(state(hit.alongRouteMeters - 80.0)).single().distanceAheadMeters, 0.01)
            assertEquals(50.0, schedule.onState(state(hit.alongRouteMeters - 50.0)).single().distanceAheadMeters, 0.01)
        }

    @Test
    fun aRerouteDoesNotRepeatEitherWalkStage() =
        runTest {
            val hit = hit(route, CROSSING_ID)
            val schedule = scheduleOf(RouteProfile.Walk)

            assertEquals(1, schedule.onState(state(hit.alongRouteMeters - 100.0, revision = 0L)).size)
            assertTrue(schedule.onState(state(hit.alongRouteMeters - 80.0, revision = 1L)).isEmpty())
            assertEquals(1, schedule.onState(state(hit.alongRouteMeters - 50.0, revision = 1L)).size)
            assertTrue(schedule.onState(state(hit.alongRouteMeters - 40.0, revision = 2L)).isEmpty())
        }

    @Test
    fun aNewTripReArmsBothWalkStages() =
        runTest {
            val hit = hit(route, CROSSING_ID)
            val schedule = scheduleOf(RouteProfile.Walk)
            val trip = listOf(100.0, 50.0)

            assertEquals(2, trip.sumOf { schedule.onState(state(hit.alongRouteMeters - it)).size })
            assertTrue(schedule.onState(state(0.0, phase = WayAlertPhase.Stopped)).isEmpty())
            assertEquals(2, trip.sumOf { schedule.onState(state(hit.alongRouteMeters - it)).size })
        }

    @Test
    fun aWalkReplayOverTheTestRegionAlertsTwiceAtTheCrossingOnly() =
        runTest {
            val schedule = scheduleOf(RouteProfile.Walk)
            val cues = mutableListOf<WayAlertCue>()
            var along = 0.0
            while (along <= ROUTE_LENGTH_METERS) {
                cues += schedule.onState(state(along))
                along += STEP_METERS
            }

            assertEquals(
                "walking gets the crossing alone, not the tunnel or the hazard",
                listOf(CROSSING_ID, CROSSING_ID),
                cues.map { it.feature.osmId },
            )
            val (first, second) = cues.map { it.distanceAheadMeters }
            assertTrue("first alert within one step of 100 m: $first", first <= 100.0 && first > 100.0 - STEP_METERS)
            assertTrue("second alert within one step of 50 m: $second", second <= 50.0 && second > 50.0 - STEP_METERS)
        }

    @Test
    fun aBikeAndAHikeKeepTheirSingleCrossingAlert() =
        runTest {
            val hit = hit(route, CROSSING_ID)
            for ((profile, meters) in listOf(RouteProfile.Bike to 150.0, RouteProfile.Hike to 50.0)) {
                val schedule = scheduleOf(profile)
                val cues = mutableListOf<WayAlertCue>()
                var ahead = 200.0
                while (ahead >= 0.0) {
                    cues += schedule.onState(state(hit.alongRouteMeters - ahead)).crossings()
                    ahead -= 1.0
                }
                assertEquals("$profile alerts once", 1, cues.size)
                assertEquals(meters, cues.single().distanceAheadMeters, 0.01)
            }
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
    fun aFeatureIsAnnouncedOncePerApproach() =
        runTest {
            val hit = hit(route, CROSSING_ID)
            val schedule = scheduleOf(RouteProfile.Bike)

            assertEquals(1, schedule.onState(state(hit.alongRouteMeters - 150.0)).size)

            repeat(3) {
                assertTrue(schedule.onState(state(hit.alongRouteMeters - 149.0)).isEmpty())
            }
        }

    @Test
    fun aRerouteDoesNotRepeatAFeatureStillOnTheApproach() =
        runTest {
            val hit = hit(route, CROSSING_ID)
            val schedule = scheduleOf(RouteProfile.Bike)

            assertEquals(1, schedule.onState(state(hit.alongRouteMeters - 150.0, revision = 0L)).size)

            assertTrue(
                "the replacement route still passes the crossing 120 m ahead",
                schedule.onState(state(hit.alongRouteMeters - 120.0, revision = 1L)).isEmpty(),
            )
            assertTrue(schedule.onState(state(hit.alongRouteMeters - 90.0, revision = 2L)).isEmpty())
        }

    @Test
    fun aRerouteAwayAndBackAnnouncesTheCrossingAgainOnce() =
        runTest {
            val detour = detourRoute()
            val crossingOnDetour = hit(detour, CROSSING_ID).alongRouteMeters
            val schedule = scheduleOf(RouteProfile.Bike)

            assertEquals(
                "the first approach announces",
                1,
                schedule.onState(state(hit(route, CROSSING_ID).alongRouteMeters - 150.0, revision = 0L)).size,
            )

            assertTrue(
                "the detour puts the crossing a kilometre ahead, beyond the release distance",
                schedule
                    .onState(state(crossingOnDetour - 1_000.0, revision = 1L, route = detour))
                    .crossings()
                    .isEmpty(),
            )

            val returned =
                schedule
                    .onState(state(crossingOnDetour - 120.0, revision = 1L, route = detour))
                    .crossings()

            assertEquals("the route comes back to the crossing, which announces once", 1, returned.size)
            assertEquals(CROSSING_ID, returned.single().feature.osmId)
            assertTrue(
                "the next reroute on the same approach does not repeat it",
                schedule
                    .onState(state(crossingOnDetour - 100.0, revision = 2L, route = detour))
                    .crossings()
                    .isEmpty(),
            )
        }

    @Test
    fun aPassedCrossingReArmsOnlyBeyondTheReleaseDistance() =
        runTest {
            val hit = hit(route, CROSSING_ID)
            val schedule = scheduleOf(RouteProfile.Bike)

            assertEquals(1, schedule.onState(state(hit.alongRouteMeters - 150.0)).crossings().size)

            assertTrue(
                "49 m past the crossing is inside the release distance",
                schedule
                    .onState(state(hit.alongRouteMeters + WayAlertDistance.RELEASE_METERS - 1.0))
                    .crossings()
                    .isEmpty(),
            )
            assertTrue(
                "the release itself emits nothing, because the crossing is behind",
                schedule
                    .onState(state(hit.alongRouteMeters + WayAlertDistance.RELEASE_METERS + 1.0))
                    .crossings()
                    .isEmpty(),
            )
            assertEquals(
                "the same crossing on the way back is a new approach",
                1,
                schedule.onState(state(hit.alongRouteMeters - 150.0)).crossings().size,
            )
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
                        featureKind = cue.feature.kind,
                    )
                },
        )

    private fun scheduleOf(profile: RouteProfile): WayAlertSchedule = WayAlertSchedule(reader).also { it.setProfile(profile) }

    /** The crossing cues alone, so another feature's cue cannot decide these tests. */
    private fun List<WayAlertCue>.crossings(): List<WayAlertCue> = filter { it.feature.osmId == CROSSING_ID }

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

    /**
     * A replacement route that leaves the crossing and comes back to it: 500 m
     * north, east along the ridge, then south to the line 72 m west of the
     * crossing and east over it. The crossing is therefore about 1.3 km ahead
     * at the route's start, well beyond the bike distance plus the release.
     */
    private fun detourRoute(): Route =
        Route(
            geometry =
                listOf(
                    START,
                    GeoPoint(49.9535, 23.9430),
                    GeoPoint(49.9535, 23.9460),
                    GeoPoint(49.9490, 23.9460),
                    END,
                ),
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
