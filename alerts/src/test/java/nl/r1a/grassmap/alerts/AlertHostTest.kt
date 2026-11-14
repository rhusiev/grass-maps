package nl.r1a.grassmap.alerts

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nl.r1a.grassmap.navigation.NavigationPhase
import nl.r1a.grassmap.navigation.NavigationState
import nl.r1a.grassmap.navigation.RouteRelation
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.model.ProximityPlaceKind
import nl.r1a.grassmap.plugin.api.model.WayAlertElementType
import nl.r1a.grassmap.plugin.api.model.WayAlertFeatureKind
import nl.r1a.grassmap.plugin.api.model.WayAlertKey
import nl.r1a.grassmap.plugin.api.provider.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertHostTest {
    @Test
    fun everyAlertIsOffByDefault() {
        val settings = AlertSettings.ALL_OFF

        assertTrue(AlertKind.entries.none { settings.isEnabled(it) })
    }

    @Test
    fun aSwitchTurnsOnOnlyItsOwnAlert() {
        val settings = AlertSettings.ALL_OFF.with(AlertKind.Rerouted, true)

        assertTrue(settings.isEnabled(AlertKind.Rerouted))
        assertTrue(
            AlertKind.entries
                .filter { it != AlertKind.Rerouted }
                .none { settings.isEnabled(it) },
        )
    }

    @Test
    fun aTenSecondFixGapEmitsSignalLostThenSignalRegainedOncePerGap() =
        runTest {
            val sink = RecordingEffectSink()
            val host = AlertHost(AlertSettings(signal = true), sink)
            val events = collectAlerts(host)

            host.onFix(fix(0L))
            host.onFix(fix(AlertHost.SIGNAL_GAP_MILLIS))

            assertEquals(listOf(AlertKind.SignalLost, AlertKind.SignalRegained), events.map { it.kind })
            assertEquals(listOf(1L, 2L), events.map { it.sequence })
            assertEquals(AlertHost.SIGNAL_GAP_MILLIS, events.first().banner.gapMillis ?: -1L)
            assertEquals(
                listOf(
                    AlertEffect.Sound(AlertKind.SignalLost),
                    AlertEffect.Vibration(AlertKind.SignalLost),
                    AlertEffect.Sound(AlertKind.SignalRegained),
                    AlertEffect.Vibration(AlertKind.SignalRegained),
                ),
                sink.effects,
            )

            host.onFix(fix(AlertHost.SIGNAL_GAP_MILLIS + 1_000L))

            assertEquals(2, events.size)
        }

    @Test
    fun aGapShorterThanTenSecondsEmitsNothing() =
        runTest {
            val sink = RecordingEffectSink()
            val host = AlertHost(AlertSettings(signal = true), sink)
            val events = collectAlerts(host)

            host.onFix(fix(0L))
            host.onFix(fix(AlertHost.SIGNAL_GAP_MILLIS - 1L))

            assertTrue(events.isEmpty())
            assertTrue(sink.effects.isEmpty())
        }

    @Test
    fun anOffRouteAlertFiresOncePerEpisode() =
        runTest {
            val host = AlertHost(AlertSettings(offRoute = true))
            val events = collectAlerts(host)
            val fix = fix(0L)

            host.onFix(fix)
            host.onState(state(relation = RouteRelation.OffStepOnRoute, fix = fix))
            host.onState(state(relation = RouteRelation.OffRoute, fix = fix))
            host.onState(state(relation = RouteRelation.OffRoute, fix = fix))

            assertEquals(listOf(AlertKind.OffRoute), events.map { it.kind })

            host.onState(state(relation = RouteRelation.OnRoute, fix = fix))
            host.onState(state(relation = RouteRelation.OffRoute, fix = fix))

            assertEquals(listOf(AlertKind.OffRoute, AlertKind.OffRoute), events.map { it.kind })
        }

    @Test
    fun arrivalEmitsOnceAndASecondArrivalDoesNotDuplicateIt() =
        runTest {
            val host = AlertHost(AlertSettings(arrival = true))
            val events = collectAlerts(host)
            val fix = fix(0L)

            host.onFix(fix)
            host.onState(state(phase = NavigationPhase.Arrived, fix = fix))
            host.onState(state(phase = NavigationPhase.Arrived, fix = fix))

            assertEquals(listOf(AlertKind.Arrived), events.map { it.kind })
        }

    @Test
    fun leavingArrivedRearmsTheAlertForANewTrip() =
        runTest {
            val host = AlertHost(AlertSettings(arrival = true))
            val events = collectAlerts(host)
            val fix = fix(0L)

            host.onFix(fix)
            host.onState(state(phase = NavigationPhase.Arrived, fix = fix))
            host.onState(state(phase = NavigationPhase.Stopped, fix = fix))
            host.onState(state(phase = NavigationPhase.Arrived, fix = fix))

            assertEquals(listOf(AlertKind.Arrived, AlertKind.Arrived), events.map { it.kind })
        }

    @Test
    fun theFirstStateOnlyBaselinesTheRouteRevision() =
        runTest {
            val host = AlertHost(AlertSettings(reroute = true))
            val events = collectAlerts(host)
            val fix = fix(0L)
            val route =
                Route(
                    geometry = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.1)),
                    distanceMeters = 1.0,
                    durationSeconds = 1.0,
                )

            host.onFix(fix)
            host.onState(state(fix = fix, revision = 4L, route = route))

            assertTrue(events.isEmpty())

            host.onState(state(fix = fix, revision = 5L, route = route))

            assertEquals(listOf(AlertKind.Rerouted), events.map { it.kind })
            assertEquals(1.0, events.single().banner.routeDistanceMeters ?: -1.0, 1e-9)
        }

    @Test
    fun anAlertEventRoundTripsThroughItsSerializer() {
        val event =
            AlertEvent(
                sequence = 7L,
                kind = AlertKind.Rerouted,
                banner = AlertBanner(AlertKind.Rerouted, AlertSeverity.Info, routeDistanceMeters = 321.5),
                effects = listOf(AlertEffect.Sound(AlertKind.Rerouted), AlertEffect.Vibration(AlertKind.Rerouted)),
                fixTimeMillis = 1_700_000_000_000L,
            )

        val json = Json.encodeToString(AlertEvent.serializer(), event)

        assertEquals(event, Json.decodeFromString(AlertEvent.serializer(), json))
        assertFalse(json.isEmpty())
        assertTrue(json.contains("\"type\":\"sound\""))
        assertTrue(json.contains("\"kind\":\"Rerouted\""))
    }

    @Test
    fun eachWayAlertHasItsOwnOffByDefaultSwitch() {
        val settings = AlertSettings.ALL_OFF

        assertFalse(settings.isEnabled(AlertKind.Tunnel))
        assertFalse(settings.isEnabled(AlertKind.RailwayCrossing))
        assertFalse(settings.isEnabled(AlertKind.Hazard))
        assertTrue(AlertKind.entries.none { settings.isEnabled(it) })

        val hazardOnly = settings.with(AlertKind.Hazard, true)

        assertTrue(hazardOnly.isEnabled(AlertKind.Hazard))
        assertFalse(hazardOnly.isEnabled(AlertKind.Tunnel))
        assertFalse(hazardOnly.isEnabled(AlertKind.RailwayCrossing))
        assertFalse(hazardOnly.isEnabled(AlertKind.OffRoute))
    }

    @Test
    fun aDisabledWayAlertEmitsNoEventAndNoEffect() =
        runTest {
            val sink = RecordingEffectSink()
            val host = AlertHost(AlertSettings.ALL_OFF, sink)
            val events = collectAlerts(host)

            host.onWayAlert(
                kind = AlertKind.Hazard,
                fixTimeMillis = 1_000L,
                distanceToFeatureMeters = 80.0,
                key = WayAlertKey(0L, WayAlertElementType.Node, 9003L),
            )

            assertTrue(events.isEmpty())
            assertTrue(sink.effects.isEmpty())
        }

    @Test
    fun aWayAlertCarriesItsFeatureDistanceAndEffects() =
        runTest {
            val sink = RecordingEffectSink()
            val host = AlertHost(AlertSettings(hazard = true), sink)
            val events = collectAlerts(host)

            host.onWayAlert(
                kind = AlertKind.Hazard,
                fixTimeMillis = 1_000L,
                distanceToFeatureMeters = 79.5,
                key = WayAlertKey(3L, WayAlertElementType.Node, 9003L),
            )

            assertEquals(listOf(AlertKind.Hazard), events.map { it.kind })
            assertEquals(79.5, events.single().banner.featureDistanceMeters ?: -1.0, 0.001)
            assertEquals(AlertSeverity.Warning, events.single().banner.severity)
            assertEquals(
                listOf(
                    AlertEffect.Sound(AlertKind.Hazard),
                    AlertEffect.Vibration(AlertKind.Hazard),
                ),
                sink.effects,
            )
        }

    @Test
    fun eachProximityAlertHasItsOwnOffByDefaultSwitch() {
        val settings = AlertSettings.ALL_OFF

        assertFalse(settings.isEnabled(AlertKind.Favourite))
        assertFalse(settings.isEnabled(AlertKind.TrackWaypoint))
        assertFalse(settings.isEnabled(AlertKind.Poi))
        assertTrue(AlertKind.entries.none { settings.isEnabled(it) })

        val favouriteOnly = settings.with(AlertKind.Favourite, true)

        assertTrue(favouriteOnly.isEnabled(AlertKind.Favourite))
        assertFalse(favouriteOnly.isEnabled(AlertKind.TrackWaypoint))
        assertFalse(favouriteOnly.isEnabled(AlertKind.Poi))
        assertFalse(favouriteOnly.isEnabled(AlertKind.Hazard))
    }

    @Test
    fun aDisabledProximityAlertEmitsNoEventAndNoEffect() =
        runTest {
            val sink = RecordingEffectSink()
            val host = AlertHost(AlertSettings.ALL_OFF, sink)
            val events = collectAlerts(host)

            host.onPlaceAlert(
                placeKind = ProximityPlaceKind.Favourite,
                fixTimeMillis = 1_000L,
                distanceMeters = 180.0,
                placeName = "Home",
            )

            assertTrue(events.isEmpty())
            assertTrue(sink.effects.isEmpty())
        }

    @Test
    fun aProximityAlertCarriesItsPlaceNameAndDistance() =
        runTest {
            val sink = RecordingEffectSink()
            val host = AlertHost(AlertSettings(poi = true), sink)
            val events = collectAlerts(host)

            host.onPlaceAlert(
                placeKind = ProximityPlaceKind.Poi,
                fixTimeMillis = 1_000L,
                distanceMeters = 240.5,
                placeName = "Svit Kavy",
            )

            assertEquals(listOf(AlertKind.Poi), events.map { it.kind })
            assertEquals("Svit Kavy", events.single().banner.placeName)
            assertEquals(240.5, events.single().banner.placeDistanceMeters ?: -1.0, 0.001)
            assertEquals(AlertSeverity.Info, events.single().banner.severity)
            assertEquals(
                listOf(
                    AlertEffect.Sound(AlertKind.Poi),
                    AlertEffect.Vibration(AlertKind.Poi),
                ),
                sink.effects,
            )
        }

    @Test
    fun theSameWayAlertKeyIsAnnouncedOnce() =
        runTest {
            val host = AlertHost(AlertSettings(railwayCrossing = true))
            val events = collectAlerts(host)
            val key = WayAlertKey(0L, WayAlertElementType.Node, 9001L)

            host.onWayAlert(AlertKind.RailwayCrossing, 1_000L, 140.0, key)
            host.onWayAlert(AlertKind.RailwayCrossing, 1_000L, 120.0, key)

            assertEquals(listOf(AlertKind.RailwayCrossing), events.map { it.kind })
        }

    @Test
    fun aRerouteRearmsTheSameFeature() =
        runTest {
            val host = AlertHost(AlertSettings(hazard = true))
            val events = collectAlerts(host)
            val fix = fix(0L)
            val firstKey = WayAlertKey(0L, WayAlertElementType.Node, 9003L)

            host.onState(state(fix = fix, revision = 0L))
            host.onWayAlert(AlertKind.Hazard, 1_000L, 90.0, firstKey)
            host.onState(state(fix = fix, revision = 1L))
            host.onWayAlert(
                AlertKind.Hazard,
                2_000L,
                90.0,
                WayAlertKey(1L, WayAlertElementType.Node, 9003L),
            )

            assertEquals(listOf(AlertKind.Hazard, AlertKind.Hazard), events.map { it.kind })
        }

    @Test
    fun aTunnelIsAnInfoBanner() =
        runTest {
            val host = AlertHost(AlertSettings(tunnel = true))
            val events = collectAlerts(host)

            host.onWayAlert(
                AlertKind.Tunnel,
                1_000L,
                190.0,
                WayAlertKey(0L, WayAlertElementType.Way, 9002L),
            )

            assertEquals(AlertSeverity.Info, events.single().banner.severity)
        }

    @Test
    fun fordAndBarrierFeaturesUseTheHazardAlertKind() {
        assertEquals(AlertKind.Hazard, AlertKind.forWayFeature(WayAlertFeatureKind.Ford))
        assertEquals(AlertKind.Hazard, AlertKind.forWayFeature(WayAlertFeatureKind.Barrier))
        assertEquals(AlertKind.Tunnel, AlertKind.forWayFeature(WayAlertFeatureKind.Tunnel))
        assertEquals(AlertKind.RailwayCrossing, AlertKind.forWayFeature(WayAlertFeatureKind.RailwayCrossing))
    }

    private fun fix(timeMillis: Long): LocationFix =
        LocationFix(
            position = GeoPoint(latitude = 49.84, longitude = 24.03),
            accuracyMeters = 5.0,
            timeMillis = timeMillis,
        )

    private fun state(
        phase: NavigationPhase = NavigationPhase.Following,
        relation: RouteRelation = RouteRelation.OnRoute,
        fix: LocationFix? = null,
        revision: Long = 0L,
        route: Route? = null,
    ): NavigationState =
        NavigationState(
            phase = phase,
            fix = fix,
            relation = relation,
            activeRoute = route,
            routeRevision = revision,
        )
}
