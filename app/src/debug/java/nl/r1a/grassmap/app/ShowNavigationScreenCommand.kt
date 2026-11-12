package nl.r1a.grassmap.app

import android.util.Log
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import nl.r1a.grassmap.alerts.AlertBanner
import nl.r1a.grassmap.alerts.AlertKind
import nl.r1a.grassmap.alerts.AlertSeverity
import nl.r1a.grassmap.navigation.NavigationInstruction
import nl.r1a.grassmap.navigation.NavigationPhase
import nl.r1a.grassmap.navigation.NavigationState
import nl.r1a.grassmap.navigation.RouteRelation
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.command.CommandPolicy
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.error.onFailure
import nl.r1a.grassmap.plugin.api.model.WayAlertFeatureKind
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.routing.RouteJson
import nl.r1a.grassmap.ui.shell.NavigationSheet
import java.time.ZoneId
import java.time.ZonedDateTime

/** The debug-only seam that shows a deterministic navigation screen (T-0320). */
internal val NAV_SHOW_SCREEN_COMMAND: CommandId = CommandId("core.nav.show_screen")

/**
 * Fills [NavigationScreenFixtureStore] with one fixed navigation state.
 *
 * The golden harness and the emulator acceptance use it because a live session
 * cannot be stopped at an exact turn: the fixture carries the same values a
 * published state does, so the real navigation screen renders it without a
 * static screenshot. The command also draws the route on the map. It exists in
 * the debug source set only, and clearing it returns the screen to the live
 * navigation state.
 */
internal class ShowNavigationScreenCommand(
    private val session: MapSessionHolder,
    private val fixtures: NavigationScreenFixtureStore,
    private val log: (String) -> Unit = { message -> Log.i(TAG, message) },
) : Command<JsonElement, String>(
        id = NAV_SHOW_SCREEN_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc =
            "Shows a deterministic navigation screen fixture. Parameters: route (stable route JSON), " +
                "optional phase (following, offroute, paused, arrived), sheet (collapsed, trip, settings), " +
                "speed_kmh, along_m, destination, next_stop, alerts (list of crossing, tunnel, ford, barrier, " +
                "hazard, recalc, gps_lost), signal_lost, dark; clear=true removes the fixture.",
        policy =
            CommandPolicy(
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.IO,
            ),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val objectValue = params as? JsonObject ?: return Outcome.Failure(AppError.InvalidInput)
        if ((objectValue["clear"] as? JsonPrimitive)?.booleanOrNull == true) {
            fixtures.clear()
            session.hostOrNull()?.setRoute(null)?.onFailure { error -> log("screen-route-clear failed: $error") }
            log("navigation-screen-fixture cleared")
            return Outcome.Success(CommandResult("cleared=true"))
        }
        val routeElement = objectValue["route"] ?: return Outcome.Failure(AppError.InvalidInput)
        val routeJson =
            when (routeElement) {
                is JsonObject -> routeElement.toString()
                is JsonPrimitive -> routeElement.contentOrNull?.takeIf { it.isNotBlank() }
                else -> null
            } ?: return Outcome.Failure(AppError.InvalidInput)
        val route =
            when (val decoded = RouteJson.decode(routeJson)) {
                is Outcome.Success -> decoded.value
                is Outcome.Failure -> return decoded
            }
        val phase = (objectValue["phase"] as? JsonPrimitive)?.contentOrNull?.lowercase() ?: PHASE_FOLLOWING
        val sheetName = (objectValue["sheet"] as? JsonPrimitive)?.contentOrNull?.lowercase() ?: SHEET_COLLAPSED
        val sheet = sheetOf(sheetName) ?: return Outcome.Failure(AppError.InvalidInput)
        val speed = (objectValue["speed_kmh"] as? JsonPrimitive)?.doubleOrNull
        val along =
            (objectValue["along_m"] as? JsonPrimitive)
                ?.doubleOrNull
                ?.coerceIn(0.0, route.distanceMeters)
                ?: route.distanceMeters * DEFAULT_ALONG_FRACTION
        val destination =
            (objectValue["destination"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val nextStop =
            (objectValue["next_stop"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val signalLost = (objectValue["signal_lost"] as? JsonPrimitive)?.booleanOrNull == true
        val dark = (objectValue["dark"] as? JsonPrimitive)?.booleanOrNull ?: false
        val state =
            when (phase) {
                PHASE_OFFROUTE -> {
                    fixtureState(
                        route = route,
                        phase = NavigationPhase.Following,
                        along = along,
                        relation = RouteRelation.OffRoute,
                    )
                }

                PHASE_PAUSED -> {
                    fixtureState(route = route, phase = NavigationPhase.Paused, along = along)
                }

                PHASE_ARRIVED -> {
                    fixtureState(route = route, phase = NavigationPhase.Arrived, along = route.distanceMeters)
                }

                PHASE_FOLLOWING -> {
                    fixtureState(route = route, phase = NavigationPhase.Following, along = along)
                }

                else -> {
                    return Outcome.Failure(AppError.InvalidInput)
                }
            }
        val alerts = alertsOf(objectValue["alerts"])
        fixtures.show(
            NavigationScreenFixture(
                state = state,
                speedCurrentMetersPerSecond = speed?.let { it / KMH_PER_METERS_PER_SECOND },
                speedAverageMetersPerSecond = speed?.let { it * AVERAGE_FRACTION / KMH_PER_METERS_PER_SECOND },
                speedMaxMetersPerSecond = speed?.let { it * MAX_FRACTION / KMH_PER_METERS_PER_SECOND },
                alerts = alerts,
                destination = destination,
                nextStopName = nextStop,
                signalLost = signalLost,
                sheet = sheet,
                dark = dark,
            ),
        )
        session.hostOrNull()?.setRoute(route)?.onFailure { error -> log("screen-route-failed: $error") }
        log("navigation-screen-fixture phase=$phase sheet=$sheetName along_m=$along alerts=${alerts.size}")
        return Outcome.Success(CommandResult("phase=$phase sheet=$sheetName alerts=${alerts.size}"))
    }

    private fun fixtureState(
        route: Route,
        phase: NavigationPhase,
        along: Double,
        relation: RouteRelation = RouteRelation.OnRoute,
    ): NavigationState {
        val total = route.distanceMeters
        val remainingMeters = (total - along).coerceAtLeast(0.0)
        val remainingFraction = if (total > 0.0) remainingMeters / total else 0.0
        val remainingSeconds = route.durationSeconds * remainingFraction
        val ahead = route.instructions.filter { it.distanceAlongRouteMeters > along }.minByOrNull { it.distanceAlongRouteMeters }
        return NavigationState(
            phase = phase,
            relation = relation,
            remainingDistanceMeters = remainingMeters,
            remainingDurationSeconds = remainingSeconds,
            etaEpochMillis = FIXTURE_EPOCH_MILLIS + (remainingSeconds * MILLIS_PER_SECOND).toLong(),
            nextInstruction =
                ahead?.let { instruction ->
                    NavigationInstruction(
                        text = instruction.text,
                        streetName = instruction.streetName,
                        distanceToInstructionMeters = instruction.distanceAlongRouteMeters - along,
                    )
                },
            activeRoute = route,
            profile = RouteProfile.Walk,
        )
    }

    private fun alertsOf(element: JsonElement?): List<AlertBanner> {
        val names =
            when (element) {
                is kotlinx.serialization.json.JsonArray -> {
                    element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                }

                is JsonPrimitive -> {
                    listOfNotNull(element.contentOrNull)
                }

                else -> {
                    emptyList()
                }
            }
        return names.mapNotNull { name -> alertOf(name.lowercase()) }
    }

    private fun alertOf(name: String): AlertBanner? =
        when (name) {
            "crossing" -> {
                AlertBanner(
                    kind = AlertKind.RailwayCrossing,
                    severity = AlertSeverity.Warning,
                    featureDistanceMeters = CROSSING_METERS,
                    featureKind = WayAlertFeatureKind.RailwayCrossing,
                )
            }

            "tunnel" -> {
                AlertBanner(
                    kind = AlertKind.Tunnel,
                    severity = AlertSeverity.Info,
                    featureDistanceMeters = TUNNEL_METERS,
                    featureKind = WayAlertFeatureKind.Tunnel,
                )
            }

            "ford" -> {
                AlertBanner(
                    kind = AlertKind.Hazard,
                    severity = AlertSeverity.Warning,
                    featureDistanceMeters = HAZARD_METERS,
                    featureKind = WayAlertFeatureKind.Ford,
                )
            }

            "barrier" -> {
                AlertBanner(
                    kind = AlertKind.Hazard,
                    severity = AlertSeverity.Warning,
                    featureDistanceMeters = HAZARD_METERS,
                    featureKind = WayAlertFeatureKind.Barrier,
                )
            }

            "hazard" -> {
                AlertBanner(
                    kind = AlertKind.Hazard,
                    severity = AlertSeverity.Warning,
                    featureDistanceMeters = HAZARD_METERS,
                    featureKind = WayAlertFeatureKind.Hazard,
                )
            }

            "recalc" -> {
                AlertBanner(
                    kind = AlertKind.Rerouted,
                    severity = AlertSeverity.Info,
                    routeDistanceMeters = 321.5,
                )
            }

            "gps_lost" -> {
                AlertBanner(
                    kind = AlertKind.SignalLost,
                    severity = AlertSeverity.Warning,
                    gapMillis = SIGNAL_GAP_MILLIS,
                )
            }

            else -> {
                null
            }
        }

    private fun sheetOf(name: String): NavigationSheet? =
        when (name) {
            SHEET_COLLAPSED -> NavigationSheet.Collapsed
            SHEET_TRIP -> NavigationSheet.Trip
            SHEET_SETTINGS -> NavigationSheet.Settings
            else -> null
        }

    private companion object {
        const val TAG = "GrassMapsNav"
        const val PHASE_FOLLOWING = "following"
        const val PHASE_OFFROUTE = "offroute"
        const val PHASE_PAUSED = "paused"
        const val PHASE_ARRIVED = "arrived"
        const val SHEET_COLLAPSED = "collapsed"
        const val SHEET_TRIP = "trip"
        const val SHEET_SETTINGS = "settings"
        const val DEFAULT_ALONG_FRACTION = 0.4
        const val KMH_PER_METERS_PER_SECOND = 3.6
        const val AVERAGE_FRACTION = 0.95
        const val MAX_FRACTION = 1.25
        const val MILLIS_PER_SECOND = 1_000.0
        const val CROSSING_METERS = 150.0
        const val TUNNEL_METERS = 320.0
        const val HAZARD_METERS = 100.0
        const val SIGNAL_GAP_MILLIS = 12_000L

        /** 2026-11-12 14:05 in the device's own zone, the round 5 screen's clock. */
        val FIXTURE_EPOCH_MILLIS: Long =
            ZonedDateTime.of(2026, 11, 12, 14, 5, 0, 0, ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
