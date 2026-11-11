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
