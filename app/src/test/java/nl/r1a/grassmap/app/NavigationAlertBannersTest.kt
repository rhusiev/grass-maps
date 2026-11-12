package nl.r1a.grassmap.app

import nl.r1a.grassmap.alerts.AlertBanner
import nl.r1a.grassmap.alerts.AlertEvent
import nl.r1a.grassmap.alerts.AlertKind
import nl.r1a.grassmap.alerts.AlertSeverity
import nl.r1a.grassmap.navigation.NavigationPhase
import nl.r1a.grassmap.navigation.NavigationState
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The round 5 banner rules (T-0320): at most two, a way alert clears once its
 * feature is behind, info and warning have their own lifetimes, and a lost
 * signal keeps the no-fix relation until the regained event.
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
    fun theSignalRelationFollowsLostAndRegained() {
        val banners = NavigationAlertBanners(clockMillis = { 0L })
        banners.onEvent(event(1, AlertKind.SignalLost, AlertSeverity.Warning), state(remaining = 700.0))
        assertTrue(banners.state.value.signalLost)
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
    fun aTerminalPhaseClearsEverything() {
        val banners = NavigationAlertBanners(clockMillis = { 0L })
        banners.onEvent(event(1, AlertKind.SignalLost, AlertSeverity.Warning), state(remaining = 700.0))

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
    ): NavigationState =
        NavigationState(
            phase = phase,
            remainingDistanceMeters = remaining,
            activeRoute = ROUTE,
        )

    private class Clock {
        var time: Long = 0L

        fun millis(): Long = time
    }

    private companion object {
        val ROUTE =
            Route(
                geometry = listOf(GeoPoint(49.8400, 24.0300), GeoPoint(49.8420, 24.0300)),
                distanceMeters = 1_000.0,
                durationSeconds = 600.0,
            )
    }
}
