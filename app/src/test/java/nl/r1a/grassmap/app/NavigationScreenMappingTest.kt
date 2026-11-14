package nl.r1a.grassmap.app

import nl.r1a.grassmap.alerts.AlertBanner
import nl.r1a.grassmap.alerts.AlertKind
import nl.r1a.grassmap.alerts.AlertSettings
import nl.r1a.grassmap.alerts.AlertSeverity
import nl.r1a.grassmap.navigation.NavigationCameraSettings
import nl.r1a.grassmap.navigation.NavigationPhase
import nl.r1a.grassmap.navigation.NavigationState
import nl.r1a.grassmap.navigation.RouteRelation
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.WayAlertFeatureKind
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteInstruction
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteSegment
import nl.r1a.grassmap.plugin.api.service.Strings
import nl.r1a.grassmap.plugin.ui.api.NavigationAlertGlyph
import nl.r1a.grassmap.plugin.ui.api.NavigationAlertTone
import nl.r1a.grassmap.plugin.ui.api.NavigationScreenStatus
import nl.r1a.grassmap.plugin.ui.api.NavigationTurnArrow
import nl.r1a.grassmap.plugins.guidance.ui.GuidanceInput
import nl.r1a.grassmap.plugins.guidance.ui.guidancePanel
import nl.r1a.grassmap.routing.RouteJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/**
 * The screen state mapping (T-0320).
 *
 * Each test starts from one published navigation state and the guidance panel
 * the app derives from it, then asserts the exact copy and numbers the round 5
 * screen draws. The mapping is pure, so a failure names the state that produced
 * it.
 */
class NavigationScreenMappingTest {
    @Test
    fun followingMapsTheTurnStopSpeedTripAndArrival() {
        val state = state()
        val screen = screen(state = state, panel = panel(state, along = 200.0))

        assertEquals(NavigationScreenStatus.Following, screen.status)
        assertEquals("180 m", screen.turn.distance)
        assertEquals("Pidvalna St", screen.turn.street)
        assertEquals(NavigationTurnArrow.Left, screen.turn.arrow)
        assertEquals("Ruska St", screen.turn.thenStreet)
        assertEquals(NavigationTurnArrow.Right, screen.turn.thenArrow)
        assertFalse(screen.turn.dimmed)
        assertEquals("200 m", screen.readout.stopDistance)
        assertEquals("Svit Kavy", screen.readout.stopName)
        assertEquals("4.8", screen.readout.speedKmh)
        assertEquals("800 m", screen.trip.remainingDistance)
        assertEquals("14:21", screen.trip.arrivalTime)
        assertEquals("bike", screen.trip.profile)
        assertEquals("4.8", screen.trip.speedCurrent)
        assertEquals("4.6", screen.trip.speedAverage)
        assertEquals("6.1", screen.trip.speedMax)
    }

    @Test
    fun theScreenRouteFixtureShowsSixMinutesToTheStopThroughTheMapping() {
        val route = decodeFixture("screen-route.json")
        val panel = requireNotNull(guidancePanel(GuidanceInput(route, 0.0), TestStrings, Locale.US))
        val state =
            NavigationState(
                phase = NavigationPhase.Following,
                remainingDistanceMeters = route.distanceMeters,
                remainingDurationSeconds = route.durationSeconds,
                activeRoute = route,
                profile = RouteProfile.Walk,
            )

        val screen =
            navigationScreenState(
                NavigationScreenInput(
                    state = state,
                    panel = panel,
                    destination = "Lviv Opera",
                    nextStopName = "Svit Kavy",
                ),
                TestStrings,
                Locale.US,
                ZoneId.of("UTC"),
            )

        assertEquals("180 m", screen.turn.distance)
        assertEquals("450 m", screen.readout.stopDistance)
        assertEquals("6 min", screen.readout.stopTime)
        assertEquals("16 min", screen.trip.remainingTime)
    }

    @Test
    fun pausedUsesThePausedCardAndDashesTheCurrentSpeed() {
        val state = state(phase = NavigationPhase.Paused)
        val screen = screen(state = state, panel = panel(state, along = 200.0))

        assertEquals(NavigationScreenStatus.Paused, screen.status)
        assertEquals("Paused", screen.turn.title)
        assertEquals("Navigation paused", screen.turn.detail)
        assertEquals("No guidance, alerts or rerouting until you resume", screen.turn.strip)
        assertEquals(NavigationTurnArrow.Pause, screen.turn.arrow)
        assertNull(screen.readout.speedKmh)
        assertNull(screen.trip.speedCurrent)
        assertEquals("4.6", screen.trip.speedAverage)
    }

    @Test
    fun offRouteUsesTheAmberCardAndHidesTheStop() {
        val state = state(relation = RouteRelation.OffRoute)
        val screen = screen(state = state, panel = panel(state, along = 200.0))

        assertEquals(NavigationScreenStatus.OffRoute, screen.status)
        assertEquals("Off route", screen.turn.title)
        assertEquals("Finding a new route…", screen.turn.detail)
        assertEquals("Keep going - the new route starts where you are", screen.turn.strip)
        assertEquals(NavigationTurnArrow.Reroute, screen.turn.arrow)
        assertNull(screen.readout.stopDistance)
        assertNull(screen.readout.stopName)
        assertEquals("4.8", screen.readout.speedKmh)
    }

    @Test
    fun arrivalShowsTheArrivalCardAndDestination() {
        val state = state(phase = NavigationPhase.Arrived, remainingDistance = 0.0, remainingDuration = 0.0)
        val screen = screen(state = state, panel = panel(state, along = 1000.0))

        assertEquals(NavigationScreenStatus.Arrived, screen.status)
        assertEquals("Arrived", screen.turn.title)
        assertEquals("Lviv Opera", screen.turn.detail)
        assertEquals(NavigationTurnArrow.Arrive, screen.turn.arrow)
    }

    @Test
    fun aLostGpsFixDimsTheLastTurnAndDashesTheSpeed() {
        val state = state()
        val screen = screen(state = state, panel = panel(state, along = 200.0), signalLost = true)

        assertTrue(screen.turn.dimmed)
        assertEquals("Last known turn - waiting for GPS", screen.turn.strip)
        assertNull(screen.readout.speedKmh)
        assertNull(screen.trip.speedCurrent)
        assertTrue(screen.signalLost)
    }

    @Test
    fun wayAlertsNameTheFeatureAndItsDistance() {
        val crossing =
            screenAlert(
                AlertBanner(
                    AlertKind.RailwayCrossing,
                    AlertSeverity.Warning,
                    featureDistanceMeters = 150.0,
                    featureKind = WayAlertFeatureKind.RailwayCrossing,
                ),
            )
        assertEquals("Railway crossing", crossing.title)
        assertEquals("in 150 m", crossing.detail)
        assertEquals(NavigationAlertTone.Warning, crossing.tone)
        assertEquals(NavigationAlertGlyph.Crossing, crossing.glyph)

        val ford =
            screenAlert(
                AlertBanner(AlertKind.Hazard, AlertSeverity.Warning, featureDistanceMeters = 100.0, featureKind = WayAlertFeatureKind.Ford),
            )
        assertEquals("Ford ahead", ford.title)
        assertEquals("in 100 m", ford.detail)
        assertEquals(NavigationAlertGlyph.Ford, ford.glyph)

        val barrier = screenAlert(AlertBanner(AlertKind.Hazard, AlertSeverity.Warning, featureKind = WayAlertFeatureKind.Barrier))
        assertEquals("Barrier ahead", barrier.title)

        val hazard = screenAlert(AlertBanner(AlertKind.Hazard, AlertSeverity.Warning, featureKind = WayAlertFeatureKind.Hazard))
        assertEquals("Hazard ahead", hazard.title)

        val tunnel =
            screenAlert(
                AlertBanner(AlertKind.Tunnel, AlertSeverity.Info, featureDistanceMeters = 320.0, featureKind = WayAlertFeatureKind.Tunnel),
            )
        assertEquals("Tunnel", tunnel.title)
        assertEquals(NavigationAlertTone.Info, tunnel.tone)
        assertEquals("in 320 m", tunnel.detail)
    }

    @Test
    fun proximityAlertsNameTheCategoryAndThePlace() {
        val favourite =
            screenAlert(
                AlertBanner(
                    AlertKind.Favourite,
                    AlertSeverity.Info,
                    placeDistanceMeters = 180.0,
                    placeName = "Home",
                ),
            )
        assertEquals("Favourite nearby", favourite.title)
        assertEquals("Home · in 180 m", favourite.detail)
        assertEquals(NavigationAlertTone.Info, favourite.tone)
        assertEquals(NavigationAlertGlyph.Place, favourite.glyph)

        val waypoint =
            screenAlert(
                AlertBanner(
                    AlertKind.TrackWaypoint,
                    AlertSeverity.Info,
                    placeDistanceMeters = 90.0,
                    placeName = "Spring",
                ),
            )
        assertEquals("Waypoint nearby", waypoint.title)
        assertEquals("Spring · in 90 m", waypoint.detail)

        val unnamed = screenAlert(AlertBanner(AlertKind.Poi, AlertSeverity.Info, placeDistanceMeters = 240.0))
        assertEquals("Place nearby", unnamed.title)
        assertEquals("in 240 m", unnamed.detail)
    }

    @Test
    fun infoAlertsAndTheSignalGapKeepTheirOwnCopy() {
        val recalculated = screenAlert(AlertBanner(AlertKind.Rerouted, AlertSeverity.Info, routeDistanceMeters = 321.5))
        assertEquals("Route recalculated", recalculated.title)
        assertEquals("From where you are", recalculated.detail)
        assertEquals(NavigationAlertGlyph.Reroute, recalculated.glyph)

        val lost = screenAlert(AlertBanner(AlertKind.SignalLost, AlertSeverity.Warning, gapMillis = 12_000L))
        assertEquals("GPS signal lost", lost.title)
        assertEquals("no fix for 12 s", lost.detail)

        val back = screenAlert(AlertBanner(AlertKind.SignalRegained, AlertSeverity.Info))
        assertEquals("GPS signal back", back.title)
        assertNull(back.detail)
    }

    @Test
    fun offRouteAndArrivalDoNotBecomeBanners() {
        val screen =
            screen(
                state = state(),
                panel = panel(state(), along = 200.0),
                alerts =
                    listOf(
                        AlertBanner(AlertKind.OffRoute, AlertSeverity.Warning),
                        AlertBanner(AlertKind.Arrived, AlertSeverity.Info),
                    ),
            )

        assertTrue(screen.alerts.isEmpty())
    }

    @Test
    fun speedIsWholeFromTenAndOneDecimalBelow() {
        assertEquals("4.8", formatSpeedKmh(1.3333, Locale.US))
        assertEquals("17", formatSpeedKmh(4.7222, Locale.US))
        assertEquals("9.9", formatSpeedKmh(2.75, Locale.US))
        assertEquals("10", formatSpeedKmh(2.7778, Locale.US))
        assertNull(formatSpeedKmh(null, Locale.US))
    }

    @Test
    fun cameraAndAlertSwitchesComeFromTheLiveSettings() {
        val screen =
            screen(
                state = state(),
                panel = panel(state(), along = 200.0),
                camera = NavigationCameraSettings(autoZoom = false, autoCentre = true, tilt = true),
                alertSettings =
                    AlertSettings(
                        signal = true,
                        reroute = false,
                        offRoute = true,
                        arrival = false,
                        tunnel = true,
                        railwayCrossing = true,
                        hazard = false,
                    ),
            )

        assertFalse(screen.camera.autoZoom)
        assertTrue(screen.camera.autoCentre)
        assertTrue(screen.camera.tilt)
        assertTrue(screen.alertSwitches.signal)
        assertTrue(screen.alertSwitches.offRoute)
        assertTrue(screen.alertSwitches.tunnel)
        assertTrue(screen.alertSwitches.crossing)
        assertFalse(screen.alertSwitches.reroute)
        assertFalse(screen.alertSwitches.hazard)
        assertEquals(4, screen.alertSwitches.enabledCount)
    }

    private fun screen(
        state: NavigationState,
        panel: nl.r1a.grassmap.plugins.guidance.ui.GuidancePanel?,
        signalLost: Boolean = false,
        alerts: List<AlertBanner> = emptyList(),
        camera: NavigationCameraSettings = NavigationCameraSettings(),
        alertSettings: AlertSettings = AlertSettings.ALL_OFF,
    ) = navigationScreenState(
        NavigationScreenInput(
            state = state,
            panel = panel,
            speedCurrentMetersPerSecond = CURRENT_MPS,
            speedAverageMetersPerSecond = AVERAGE_MPS,
            speedMaxMetersPerSecond = MAX_MPS,
            alerts = alerts,
            destination = "Lviv Opera",
            nextStopName = "Svit Kavy",
            signalLost = signalLost,
            camera = camera,
            alertSettings = alertSettings,
        ),
        TestStrings,
        Locale.US,
        ZoneId.of("UTC"),
    )

    private fun decodeFixture(name: String): Route {
        val json =
            requireNotNull(javaClass.getResourceAsStream("/navigation/$name")) {
                "$name is missing from the test resources"
            }.bufferedReader().use { it.readText() }
        return when (val outcome = RouteJson.decode(json)) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("$name did not decode: ${outcome.error}")
        }
    }

    private fun screenAlert(banner: AlertBanner) =
        screen(state = state(), panel = panel(state(), along = 200.0), alerts = listOf(banner)).alerts.single()

    private fun panel(
        state: NavigationState,
        along: Double,
    ) = requireNotNull(guidancePanel(GuidanceInput(ROUTE, along), TestStrings, Locale.US))

    private fun state(
        phase: NavigationPhase = NavigationPhase.Following,
        relation: RouteRelation = RouteRelation.OnRoute,
        remainingDistance: Double = 800.0,
        remainingDuration: Double = 600.0,
    ) = NavigationState(
        phase = phase,
        relation = relation,
        remainingDistanceMeters = remainingDistance,
        remainingDurationSeconds = remainingDuration,
        etaEpochMillis = ETA_EPOCH_MILLIS,
        activeRoute = ROUTE,
        profile = RouteProfile.Bike,
    )

    private object TestStrings : Strings {
        private val values =
            mapOf(
                "guidance_panel_next_stop" to "Next stop",
                "guidance_panel_arrive" to "Arrive at the destination",
                "guidance_panel_arrived" to "Arrived",
                "guidance_panel_off_route" to "Off route",
                "guidance_panel_in" to "in %1\$s, %2\$s",
                "guidance_panel_then_in" to "then in %1\$s, %2\$s",
                "guidance_panel_metres" to "%1\$s m",
                "guidance_panel_kilometres" to "%1\$s km",
                "guidance_panel_minutes" to "%1\$s min",
                "guidance_panel_hours" to "%1\$s h",
                "guidance_panel_hours_minutes" to "%1\$s h %2\$s min",
                "nav_screen_destination" to "Destination",
                "nav_screen_next_stop_name" to "Next stop",
                "nav_screen_off_route" to "Off route",
                "nav_screen_finding_route" to "Finding a new route…",
                "nav_screen_keep_going" to "Keep going - the new route starts where you are",
                "nav_screen_paused" to "Paused",
                "nav_screen_paused_detail" to "Navigation paused",
                "nav_screen_paused_strip" to "No guidance, alerts or rerouting until you resume",
                "nav_screen_arrived" to "Arrived",
                "nav_screen_last_turn" to "Last known turn - waiting for GPS",
                "nav_screen_alert_in" to "in %1\$s",
                "nav_screen_alert_no_fix" to "no fix for %1\$s",
                "nav_screen_alert_from_here" to "From where you are",
                "nav_screen_alert_crossing" to "Railway crossing",
                "nav_screen_alert_tunnel" to "Tunnel",
                "nav_screen_alert_ford" to "Ford ahead",
                "nav_screen_alert_barrier" to "Barrier ahead",
                "nav_screen_alert_hazard" to "Hazard ahead",
                "nav_screen_alert_gps_lost" to "GPS signal lost",
                "nav_screen_alert_gps_back" to "GPS signal back",
                "nav_screen_alert_recalculated" to "Route recalculated",
                "nav_screen_alert_arrived" to "You have arrived",
                "nav_screen_alert_favourite" to "Favourite nearby",
                "nav_screen_alert_waypoint" to "Waypoint nearby",
                "nav_screen_alert_place" to "Place nearby",
                "nav_screen_alert_place_detail" to "%1\$s · %2\$s",
                "nav_screen_seconds" to "%1\$s s",
            )

        override fun resolve(
            key: String,
            arguments: List<String>,
        ): String {
            val template = values[key] ?: return key
            return if (arguments.isEmpty()) {
                template
            } else {
                String.format(Locale.US, template, *arguments.toTypedArray())
            }
        }

        override fun contains(key: String): Boolean = key in values
    }

    private companion object {
        const val CURRENT_MPS = 1.3333
        const val AVERAGE_MPS = 1.2778
        const val MAX_MPS = 1.6944
        val ETA_EPOCH_MILLIS: Long =
            java.time.Instant
                .parse("2026-11-12T14:21:00Z")
                .toEpochMilli()

        val ROUTE =
            Route(
                geometry =
                    listOf(
                        GeoPoint(49.8419, 24.0316),
                        GeoPoint(49.8400, 24.0300),
                        GeoPoint(49.8380, 24.0280),
                    ),
                distanceMeters = 1_000.0,
                durationSeconds = 600.0,
                segments =
                    listOf(
                        RouteSegment(startIndex = 0, endIndex = 1, distanceMeters = 400.0, durationSeconds = 240.0),
                        RouteSegment(startIndex = 1, endIndex = 2, distanceMeters = 600.0, durationSeconds = 360.0),
                    ),
                instructions =
                    listOf(
                        RouteInstruction(
                            text = "Depart",
                            distanceAlongRouteMeters = 0.0,
                            durationSeconds = 60.0,
                        ),
                        RouteInstruction(
                            text = "Turn left onto Pidvalna St",
                            streetName = "Pidvalna St",
                            distanceAlongRouteMeters = 380.0,
                            durationSeconds = 120.0,
                            maneuver = "15",
                        ),
                        RouteInstruction(
                            text = "Turn right onto Ruska St",
                            streetName = "Ruska St",
                            distanceAlongRouteMeters = 700.0,
                            durationSeconds = 180.0,
                            maneuver = "10",
                        ),
                        RouteInstruction(
                            text = "Arrive at the destination",
                            distanceAlongRouteMeters = 1_000.0,
                            durationSeconds = 240.0,
                        ),
                    ),
            )
    }
}
