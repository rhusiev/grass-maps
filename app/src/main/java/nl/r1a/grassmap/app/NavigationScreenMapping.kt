package nl.r1a.grassmap.app

import nl.r1a.grassmap.alerts.AlertBanner
import nl.r1a.grassmap.alerts.AlertKind
import nl.r1a.grassmap.alerts.AlertSettings
import nl.r1a.grassmap.alerts.AlertSeverity
import nl.r1a.grassmap.navigation.NavigationCameraSettings
import nl.r1a.grassmap.navigation.NavigationPhase
import nl.r1a.grassmap.navigation.NavigationState
import nl.r1a.grassmap.navigation.RouteRelation
import nl.r1a.grassmap.plugin.api.model.WayAlertFeatureKind
import nl.r1a.grassmap.plugin.api.service.Strings
import nl.r1a.grassmap.plugin.ui.api.NavigationAlertGlyph
import nl.r1a.grassmap.plugin.ui.api.NavigationAlertSwitches
import nl.r1a.grassmap.plugin.ui.api.NavigationAlertTone
import nl.r1a.grassmap.plugin.ui.api.NavigationCameraSwitches
import nl.r1a.grassmap.plugin.ui.api.NavigationReadout
import nl.r1a.grassmap.plugin.ui.api.NavigationScreenAlert
import nl.r1a.grassmap.plugin.ui.api.NavigationScreenState
import nl.r1a.grassmap.plugin.ui.api.NavigationScreenStatus
import nl.r1a.grassmap.plugin.ui.api.NavigationTripSummary
import nl.r1a.grassmap.plugin.ui.api.NavigationTurnArrow
import nl.r1a.grassmap.plugin.ui.api.NavigationTurnCard
import nl.r1a.grassmap.plugins.guidance.ui.GuidancePanel
import nl.r1a.grassmap.plugins.guidance.ui.GuidancePanelEntry
import nl.r1a.grassmap.plugins.guidance.ui.GuidancePanelField
import nl.r1a.grassmap.plugins.guidance.ui.guidanceDistanceText
import nl.r1a.grassmap.plugins.guidance.ui.guidanceDurationText
import nl.r1a.grassmap.plugins.guidance.ui.turnArrow
import nl.r1a.grassmap.ui.shell.NavigationScreenStrings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

/** Everything one navigation screen derivation reads, in plain values. */
internal data class NavigationScreenInput(
    val state: NavigationState,
    val panel: GuidancePanel?,
    val speedCurrentMetersPerSecond: Double? = null,
    val speedAverageMetersPerSecond: Double? = null,
    val speedMaxMetersPerSecond: Double? = null,
    val alerts: List<AlertBanner> = emptyList(),
    val destination: String? = null,
    val nextStopName: String? = null,
    val signalLost: Boolean = false,
    val camera: NavigationCameraSettings = NavigationCameraSettings(),
    val alertSettings: AlertSettings = AlertSettings.ALL_OFF,
)

/**
 * Maps one published navigation state and the values the screen already holds
 * onto the round 5 navigation screen.
 *
 * The function is pure: no camera, no location stream and no clock. The caller
 * derives it once per published state, so a camera or gesture frame cannot
 * recompute guidance. A missing route still yields a screen only when a state
 * is arriving or paused; an idle or stopped session is the caller's reason to
 * remove the screen.
 */
internal fun navigationScreenState(
    input: NavigationScreenInput,
    strings: Strings,
    locale: Locale = Locale.getDefault(),
    zone: ZoneId = ZoneId.systemDefault(),
): NavigationScreenState {
    val state = input.state
    val status =
        when {
            state.phase == NavigationPhase.Arrived -> {
                NavigationScreenStatus.Arrived
            }

            state.phase == NavigationPhase.Paused -> {
                NavigationScreenStatus.Paused
            }

            state.relation == RouteRelation.OffRoute || state.phase == NavigationPhase.Rerouting -> {
                NavigationScreenStatus.OffRoute
            }

            else -> {
                NavigationScreenStatus.Following
            }
        }
    val destination = input.destination ?: strings.resolve(NavigationScreenStrings.DESTINATION)
    val next = input.panel?.entries?.firstOrNull { it.field == GuidancePanelField.NextTurn }
    val second = input.panel?.entries?.firstOrNull { it.field == GuidancePanelField.SecondTurn }
    val speed = if (status == NavigationScreenStatus.Paused || input.signalLost) null else input.speedCurrentMetersPerSecond
    return NavigationScreenState(
        status = status,
        turn = turnCard(input, status, next, second, destination, strings, locale),
        readout = readout(input, status, speed, strings, locale),
        trip = trip(input, status, destination, speed, strings, locale, zone),
        alerts = input.alerts.mapNotNull { banner -> screenAlert(banner, strings, locale) },
        camera =
            NavigationCameraSwitches(
                autoZoom = input.camera.autoZoom,
                autoCentre = input.camera.autoCentre,
                tilt = input.camera.tilt,
            ),
        alertSwitches =
            NavigationAlertSwitches(
                signal = input.alertSettings.signal,
                reroute = input.alertSettings.reroute,
                offRoute = input.alertSettings.offRoute,
                arrival = input.alertSettings.arrival,
                tunnel = input.alertSettings.tunnel,
                crossing = input.alertSettings.railwayCrossing,
                hazard = input.alertSettings.hazard,
            ),
        signalLost = input.signalLost,
    )
}

private fun turnCard(
    input: NavigationScreenInput,
    status: NavigationScreenStatus,
    next: GuidancePanelEntry?,
    second: GuidancePanelEntry?,
    destination: String,
    strings: Strings,
    locale: Locale,
): NavigationTurnCard {
    val fallback = input.state.nextInstruction
    val distance =
        next?.distanceMeters?.let { guidanceDistanceText(it, strings, locale) }
            ?: fallback?.distanceToInstructionMeters?.let { guidanceDistanceText(it, strings, locale) }
            ?: DASH
    val street = next?.let { it.streetName ?: it.title } ?: fallback?.text.orEmpty()
    val arrow = next?.let { turnArrow(it.maneuver, it.title) } ?: NavigationTurnArrow.Straight
    return when (status) {
        NavigationScreenStatus.OffRoute -> {
            NavigationTurnCard(
                status = status,
                distance = distance,
                street = street,
                arrow = NavigationTurnArrow.Reroute,
                title = strings.resolve(NavigationScreenStrings.OFF_ROUTE),
                detail = strings.resolve(NavigationScreenStrings.FINDING_ROUTE),
                strip = strings.resolve(NavigationScreenStrings.KEEP_GOING),
            )
        }

        NavigationScreenStatus.Paused -> {
            NavigationTurnCard(
                status = status,
                distance = distance,
                street = street,
                arrow = NavigationTurnArrow.Pause,
                title = strings.resolve(NavigationScreenStrings.PAUSED),
                detail = strings.resolve(NavigationScreenStrings.PAUSED_DETAIL),
                strip = strings.resolve(NavigationScreenStrings.PAUSED_STRIP),
            )
        }

        NavigationScreenStatus.Arrived -> {
            NavigationTurnCard(
                status = status,
                distance = distance,
                street = destination,
                arrow = NavigationTurnArrow.Arrive,
                title = strings.resolve(NavigationScreenStrings.ARRIVED),
                detail = destination,
            )
        }

        NavigationScreenStatus.Following -> {
            NavigationTurnCard(
                status = status,
                distance = distance,
                street = street,
                arrow = arrow,
                thenStreet = second?.let { it.streetName ?: it.title },
                thenArrow = second?.let { turnArrow(it.maneuver, it.title) },
                strip = if (input.signalLost) strings.resolve(NavigationScreenStrings.LAST_TURN) else null,
                dimmed = input.signalLost,
            )
        }
    }
}

private fun readout(
    input: NavigationScreenInput,
    status: NavigationScreenStatus,
    speed: Double?,
    strings: Strings,
    locale: Locale,
): NavigationReadout {
    val showStop = status == NavigationScreenStatus.Following || status == NavigationScreenStatus.Paused
    val stopDistance =
        input.panel?.entries?.firstOrNull { it.field == GuidancePanelField.NextStopDistance }
    val stopTime =
        input.panel?.entries?.firstOrNull { it.field == GuidancePanelField.NextStopTime }
    return NavigationReadout(
        stopDistance = if (showStop) stopDistance?.distanceMeters?.let { guidanceDistanceText(it, strings, locale) } else null,
        stopTime = if (showStop) stopTime?.durationSeconds?.let { guidanceDurationText(it, strings) } else null,
        stopName =
            if (showStop && (stopDistance != null || stopTime != null)) {
                input.nextStopName ?: strings.resolve(NavigationScreenStrings.NEXT_STOP_NAME)
            } else {
                null
            },
        speedKmh = formatSpeedKmh(speed, locale),
    )
}

private fun trip(
    input: NavigationScreenInput,
    status: NavigationScreenStatus,
    destination: String,
    speed: Double?,
    strings: Strings,
    locale: Locale,
    zone: ZoneId,
): NavigationTripSummary =
    NavigationTripSummary(
        destination = destination,
        remainingDistance = guidanceDistanceText(input.state.remainingDistanceMeters, strings, locale),
        arrivalTime = input.state.etaEpochMillis?.let { formatArrival(it, zone) },
        remainingTime = guidanceDurationText(input.state.remainingDurationSeconds, strings),
        profile =
            input.state.profile
                ?.name
                ?.lowercase(Locale.ROOT) ?: DEFAULT_PROFILE,
        speedCurrent = if (status == NavigationScreenStatus.Paused) null else formatSpeedKmh(speed, locale),
        speedAverage = formatSpeedKmh(input.speedAverageMetersPerSecond, locale),
        speedMax = formatSpeedKmh(input.speedMaxMetersPerSecond, locale),
    )

private fun screenAlert(
    banner: AlertBanner,
    strings: Strings,
    locale: Locale,
): NavigationScreenAlert? {
    // Off route and arrival are turn-card states; a banner would say the same
    // thing twice.
    if (banner.kind == AlertKind.OffRoute || banner.kind == AlertKind.Arrived) {
        return null
    }
    val warning = banner.severity != AlertSeverity.Info
    val featureDistance = banner.featureDistanceMeters
    val detail =
        when {
            banner.kind == AlertKind.SignalLost -> {
                val seconds = ((banner.gapMillis ?: 0L) / MILLIS_PER_SECOND).coerceAtLeast(0L)
                strings.resolve(
                    NavigationScreenStrings.ALERT_NO_FIX,
                    listOf(strings.resolve(NavigationScreenStrings.SECONDS, listOf(seconds.toString()))),
                )
            }

            banner.kind == AlertKind.Rerouted -> {
                strings.resolve(NavigationScreenStrings.ALERT_FROM_HERE)
            }

            featureDistance != null && banner.kind in WAY_ALERT_KINDS -> {
                strings.resolve(
                    NavigationScreenStrings.ALERT_IN,
                    listOf(guidanceDistanceText(featureDistance, strings, locale)),
                )
            }

            banner.kind in PROXIMITY_ALERT_KINDS -> {
                placeDetail(banner, strings, locale)
            }

            else -> {
                null
            }
        }
    return NavigationScreenAlert(
        tone = if (warning) NavigationAlertTone.Warning else NavigationAlertTone.Info,
        glyph = alertGlyph(banner),
        title = alertTitle(banner, strings),
        detail = detail,
    )
}

private fun alertGlyph(banner: AlertBanner): NavigationAlertGlyph =
    when (banner.kind) {
        AlertKind.SignalLost, AlertKind.SignalRegained -> {
            NavigationAlertGlyph.Signal
        }

        AlertKind.Rerouted -> {
            NavigationAlertGlyph.Reroute
        }

        AlertKind.Tunnel -> {
            NavigationAlertGlyph.Tunnel
        }

        AlertKind.RailwayCrossing -> {
            NavigationAlertGlyph.Crossing
        }

        AlertKind.Hazard -> {
            when (banner.featureKind) {
                WayAlertFeatureKind.Ford -> NavigationAlertGlyph.Ford
                else -> NavigationAlertGlyph.Hazard
            }
        }

        AlertKind.Favourite, AlertKind.TrackWaypoint, AlertKind.Poi -> {
            NavigationAlertGlyph.Place
        }

        AlertKind.OffRoute, AlertKind.Arrived -> {
            NavigationAlertGlyph.Arrival
        }
    }

private fun alertTitle(
    banner: AlertBanner,
    strings: Strings,
): String =
    when (banner.kind) {
        AlertKind.SignalLost -> {
            strings.resolve(NavigationScreenStrings.ALERT_GPS_LOST)
        }

        AlertKind.SignalRegained -> {
            strings.resolve(NavigationScreenStrings.ALERT_GPS_BACK)
        }

        AlertKind.Rerouted -> {
            strings.resolve(NavigationScreenStrings.ALERT_RECALCULATED)
        }

        AlertKind.Tunnel -> {
            strings.resolve(NavigationScreenStrings.ALERT_TUNNEL)
        }

        AlertKind.RailwayCrossing -> {
            strings.resolve(NavigationScreenStrings.ALERT_CROSSING)
        }

        AlertKind.Hazard -> {
            when (banner.featureKind) {
                WayAlertFeatureKind.Ford -> strings.resolve(NavigationScreenStrings.ALERT_FORD)
                WayAlertFeatureKind.Barrier -> strings.resolve(NavigationScreenStrings.ALERT_BARRIER)
                else -> strings.resolve(NavigationScreenStrings.ALERT_HAZARD)
            }
        }

        AlertKind.OffRoute -> {
            strings.resolve(NavigationScreenStrings.OFF_ROUTE)
        }

        AlertKind.Arrived -> {
            strings.resolve(NavigationScreenStrings.ALERT_ARRIVED)
        }

        AlertKind.Favourite -> {
            strings.resolve(NavigationScreenStrings.ALERT_FAVOURITE)
        }

        AlertKind.TrackWaypoint -> {
            strings.resolve(NavigationScreenStrings.ALERT_WAYPOINT)
        }

        AlertKind.Poi -> {
            strings.resolve(NavigationScreenStrings.ALERT_PLACE)
        }
    }

/**
 * The second line of a proximity banner: the place's own name and how far it
 * was when the alert fired (T-0413). The name is data, so it is drawn as it is.
 * A place without a name shows the distance alone.
 */
private fun placeDetail(
    banner: AlertBanner,
    strings: Strings,
    locale: Locale,
): String? {
    val distance =
        banner.placeDistanceMeters?.let { meters ->
            strings.resolve(
                NavigationScreenStrings.ALERT_IN,
                listOf(guidanceDistanceText(meters, strings, locale)),
            )
        }
    val name = banner.placeName
    return when {
        name != null && distance != null -> {
            strings.resolve(NavigationScreenStrings.ALERT_PLACE_DETAIL, listOf(name, distance))
        }

        name != null -> {
            name
        }

        else -> {
            distance
        }
    }
}

/** Whole km/h from 10, one decimal below; a missing value draws a dash. */
internal fun formatSpeedKmh(
    metersPerSecond: Double?,
    locale: Locale = Locale.getDefault(),
): String? {
    if (metersPerSecond == null || !metersPerSecond.isFinite() || metersPerSecond < 0.0) {
        return null
    }
    val kmh = metersPerSecond * SECONDS_PER_HOUR / METERS_PER_KILOMETER
    return if (kmh >= WHOLE_KMH_FROM) {
        kmh.roundToLong().toString()
    } else {
        String.format(locale, "%.1f", kmh)
    }
}

private fun formatArrival(
    epochMillis: Long,
    zone: ZoneId,
): String = Instant.ofEpochMilli(epochMillis).atZone(zone).format(ARRIVAL_FORMAT)

private val WAY_ALERT_KINDS =
    setOf(AlertKind.Tunnel, AlertKind.RailwayCrossing, AlertKind.Hazard)
private val PROXIMITY_ALERT_KINDS =
    setOf(AlertKind.Favourite, AlertKind.TrackWaypoint, AlertKind.Poi)
private val ARRIVAL_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
private const val DASH = "–"
private const val DEFAULT_PROFILE = "walk"
private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_HOUR = 3_600.0
private const val METERS_PER_KILOMETER = 1_000.0
private const val WHOLE_KMH_FROM = 10.0
