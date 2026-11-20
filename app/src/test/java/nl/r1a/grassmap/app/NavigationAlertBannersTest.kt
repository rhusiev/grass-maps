@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package nl.r1a.grassmap.app

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.alerts.AlertBanner
import nl.r1a.grassmap.alerts.AlertEvent
import nl.r1a.grassmap.alerts.AlertHost
import nl.r1a.grassmap.alerts.AlertKind
import nl.r1a.grassmap.alerts.AlertSettings
import nl.r1a.grassmap.alerts.AlertSeverity
import nl.r1a.grassmap.navigation.NavigationPhase
import nl.r1a.grassmap.navigation.NavigationState
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.provider.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The round 5 banner rules (T-0320) and the live no-fix relation (T-0317): at
 * most two banners, a way alert clears once its feature is behind, info and
 * warning have their own lifetimes, and the no-fix relation follows the last
 * fix's age rather than the retrospective lost/regained pair.
 */
class NavigationAlertBannersTest {
    @Test
    fun anInfoBannerClearsAfterFourSeconds() {
        val clock = Clock()
        val banners = NavigationAlertBanners(clockMillis = clock::millis)
        banners.onEvent(event(1, AlertKind.Rerouted, AlertSeverity.Info), state(remaining = 700.0))

        clock.time = 3_999L
        banners.onState(state(remaining = 700.0))
        assertEquals(1, banners.state.value.banners.size)

        clock.time = 4_000L
        banners.onState(state(remaining = 700.0))
        assertTrue(
            banners.state.value.banners
                .isEmpty(),
        )
    }

    @Test
    fun aTickClearsAnInfoBannerWhileTheSessionWaits() {
        val clock = Clock()
        val banners = NavigationAlertBanners(clockMillis = clock::millis)
        banners.onEvent(event(1, AlertKind.Rerouted, AlertSeverity.Info), state(remaining = 700.0))

        clock.time = 4_000L
        banners.onTick()

        assertTrue(
            banners.state.value.banners
                .isEmpty(),
        )
    }

    @Test
    fun aWarningBannerGetsEightSeconds() {
        val clock = Clock()
        val banners = NavigationAlertBanners(clockMillis = clock::millis)
        banners.onEvent(
            event(1, AlertKind.RailwayCrossing, AlertSeverity.Warning, featureDistance = 150.0),
            state(remaining = 700.0),
        )

        clock.time = 7_999L
        banners.onState(state(remaining = 700.0))
        assertEquals(1, banners.state.value.banners.size)

        clock.time = 8_000L
        banners.onState(state(remaining = 700.0))
        assertTrue(
            banners.state.value.banners
                .isEmpty(),
        )
    }

    @Test
    fun aWayAlertClearsOnceItsFeatureIsBehind() {
        val clock = Clock()
        val banners = NavigationAlertBanners(clockMillis = clock::millis)
        // 1000 m route, 700 m remaining: 300 m along, the feature is at 400 m.
        banners.onEvent(
            event(1, AlertKind.RailwayCrossing, AlertSeverity.Warning, featureDistance = 500.0),
            state(remaining = 700.0),
        )

        banners.onState(state(remaining = 550.0))
        assertEquals(1, banners.state.value.banners.size)

        banners.onState(state(remaining = 190.0))
        assertTrue(
            banners.state.value.banners
                .isEmpty(),
        )
    }

    @Test
    fun atMostTwoBannersStayAndTheNewestWins() {
        val clock = Clock()
        val banners = NavigationAlertBanners(clockMillis = clock::millis)
        banners.onEvent(event(1, AlertKind.Tunnel, AlertSeverity.Info), state(remaining = 700.0))
        banners.onEvent(event(2, AlertKind.Rerouted, AlertSeverity.Info), state(remaining = 700.0))
        banners.onEvent(event(3, AlertKind.SignalLost, AlertSeverity.Warning), state(remaining = 700.0))

        assertEquals(
            listOf(2L, 3L),
            banners.state.value.banners
                .map { it.sequence },
        )
    }

    @Test
    fun offRouteAndArrivalStayInTheTurnCard() {
        val banners = NavigationAlertBanners(clockMillis = { 0L })
        banners.onEvent(event(1, AlertKind.OffRoute, AlertSeverity.Warning), state(remaining = 700.0))
        banners.onEvent(event(2, AlertKind.Arrived, AlertSeverity.Info), state(remaining = 700.0))

        assertTrue(
            banners.state.value.banners
                .isEmpty(),
        )
    }

    @Test
    fun theLiveSignalTurnsOnAtTenSecondsWithoutAFix() {
        val clock = Clock(BASE_MILLIS)
        val banners = NavigationAlertBanners(clockMillis = clock::millis, fixClockMillis = clock::millis)
        banners.onState(state(remaining = 700.0, fix = fix(BASE_MILLIS)))

        clock.time = BASE_MILLIS + AlertHost.SIGNAL_GAP_MILLIS - 1L
        banners.onTick()
        assertFalse("9.999 s is still a live fix", banners.state.value.signalLost)

        clock.time = BASE_MILLIS + AlertHost.SIGNAL_GAP_MILLIS
        banners.onTick()
        assertTrue("10 s is the boundary of a lost fix", banners.state.value.signalLost)
    }

    @Test
    fun theNextFixClearsTheLiveSignal() {
        val clock = Clock(BASE_MILLIS)
        val banners = NavigationAlertBanners(clockMillis = clock::millis, fixClockMillis = clock::millis)
        banners.onState(state(remaining = 700.0, fix = fix(BASE_MILLIS)))

        clock.time = BASE_MILLIS + AlertHost.SIGNAL_GAP_MILLIS + 5_000L
        banners.onTick()
        assertTrue(banners.state.value.signalLost)

        banners.onState(state(remaining = 700.0, fix = fix(clock.time)))

        assertFalse("the next published fix is fresh again", banners.state.value.signalLost)
    }

    @Test
    fun theRetrospectivePairDrawsItsBannersWithoutSettingTheLiveSignal() {
        val clock = Clock(BASE_MILLIS)
        val banners = NavigationAlertBanners(clockMillis = clock::millis, fixClockMillis = clock::millis)
        banners.onState(state(remaining = 700.0, fix = fix(BASE_MILLIS)))

        banners.onEvent(event(1, AlertKind.SignalLost, AlertSeverity.Warning), state(remaining = 700.0))
        assertFalse("a gap that is already over is not a live no-fix state", banners.state.value.signalLost)
        assertEquals(
            listOf(AlertKind.SignalLost),
            banners.state.value.banners
                .map { it.banner.kind },
        )

        banners.onEvent(event(2, AlertKind.SignalRegained, AlertSeverity.Info), state(remaining = 700.0))
        assertFalse(banners.state.value.signalLost)
        assertEquals(
            listOf(AlertKind.SignalRegained),
            banners.state.value.banners
                .map { it.banner.kind },
        )
    }

    @Test
    fun aNewTripDoesNotBorrowTheFinishedTripsFixAge() {
        val clock = Clock(BASE_MILLIS)
        val banners = NavigationAlertBanners(clockMillis = clock::millis, fixClockMillis = clock::millis)
        banners.onState(state(remaining = 700.0, fix = fix(BASE_MILLIS)))
        clock.time = BASE_MILLIS + 60_000L
        banners.onState(state(remaining = 700.0, phase = NavigationPhase.Arrived, fix = fix(BASE_MILLIS)))

        banners.onState(state(remaining = 700.0, phase = NavigationPhase.Following))

        assertFalse("the new trip has no fix yet, so it is not a lost one", banners.state.value.signalLost)
    }

    @Test
    fun oneGapEmitsOnePairAndNoExtraLiveAnnouncement() =
        runTest {
            val clock = Clock(BASE_MILLIS)
            val host = AlertHost(AlertSettings(signal = true))
            val events = mutableListOf<AlertEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler), start = CoroutineStart.UNDISPATCHED) {
                host.events.collect { events += it }
            }
            val banners = NavigationAlertBanners(clockMillis = clock::millis, fixClockMillis = clock::millis)
            val first = fix(BASE_MILLIS)
            host.onFix(first)
            banners.onState(state(remaining = 700.0, fix = first))

            clock.time = BASE_MILLIS + AlertHost.SIGNAL_GAP_MILLIS
            banners.onTick()
            assertTrue("the live relation ages while no fix arrives", banners.state.value.signalLost)
            assertTrue("the live relation is drawn, not announced", events.isEmpty())

            val recovered = fix(BASE_MILLIS + AlertHost.SIGNAL_GAP_MILLIS + 5_000L)
            host.onFix(recovered)
            assertEquals(listOf(AlertKind.SignalLost, AlertKind.SignalRegained), events.map { it.kind })
            events.forEach { event -> banners.onEvent(event, state(remaining = 700.0, fix = recovered)) }
            assertTrue("the latest published fix is still the old one", banners.state.value.signalLost)

            banners.onState(state(remaining = 700.0, fix = recovered))
            assertFalse("the recovering fix clears the live relation", banners.state.value.signalLost)

            host.onFix(fix(recovered.timeMillis + 1_000L))
            assertEquals("the same gap is not announced a second time", 2, events.size)
        }

    @Test
    fun anEventWithTheBannerChannelOffDrawsNothing() {
        val banners = NavigationAlertBanners(clockMillis = { 0L })

        banners.onEvent(
            event(1, AlertKind.SignalLost, AlertSeverity.Warning).copy(bannerVisible = false),
            state(remaining = 700.0),
        )

        assertTrue(
            banners.state.value.banners
                .isEmpty(),
        )
        assertFalse(banners.state.value.signalLost)
    }

    @Test
    fun aTerminalPhaseClearsTheBannersAndTheLiveSignal() {
        val clock = Clock(BASE_MILLIS)
        val banners = NavigationAlertBanners(clockMillis = clock::millis, fixClockMillis = clock::millis)
        banners.onState(state(remaining = 700.0, fix = fix(BASE_MILLIS)))
        banners.onEvent(event(1, AlertKind.SignalLost, AlertSeverity.Warning), state(remaining = 700.0))
        clock.time = BASE_MILLIS + AlertHost.SIGNAL_GAP_MILLIS
        banners.onTick()
        assertTrue(banners.state.value.signalLost)

        banners.onState(state(remaining = 0.0, phase = NavigationPhase.Stopped))

        assertTrue(
            banners.state.value.banners
                .isEmpty(),
        )
        assertFalse(banners.state.value.signalLost)
    }

    private fun event(
        sequence: Long,
        kind: AlertKind,
        severity: AlertSeverity,
        featureDistance: Double? = null,
    ): AlertEvent {
        val banner =
            AlertBanner(
                kind = kind,
                severity = severity,
                gapMillis = if (kind == AlertKind.SignalLost) 12_000L else null,
                featureDistanceMeters = featureDistance,
            )
        return AlertEvent(
            sequence = sequence,
            kind = kind,
            banner = banner,
            effects = emptyList(),
            fixTimeMillis = 0L,
        )
    }

    private fun state(
        remaining: Double,
        phase: NavigationPhase = NavigationPhase.Following,
        fix: LocationFix? = null,
    ): NavigationState =
        NavigationState(
            phase = phase,
            fix = fix,
            remainingDistanceMeters = remaining,
            activeRoute = ROUTE,
        )

    private fun fix(timeMillis: Long): LocationFix =
        LocationFix(
            position = GeoPoint(latitude = 49.8400, longitude = 24.0300),
            accuracyMeters = 5.0,
            timeMillis = timeMillis,
        )

    private class Clock(
        var time: Long = 0L,
    ) {
        fun millis(): Long = time
    }

    private companion object {
        const val BASE_MILLIS: Long = 1_700_000_000_000L

        val ROUTE =
            Route(
                geometry = listOf(GeoPoint(49.8400, 24.0300), GeoPoint(49.8420, 24.0300)),
                distanceMeters = 1_000.0,
                durationSeconds = 600.0,
            )
    }
}
