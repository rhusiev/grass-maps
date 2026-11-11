package nl.r1a.grassmap.app

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import nl.r1a.grassmap.alerts.BeepDistance
import nl.r1a.grassmap.alerts.BeepHost
import nl.r1a.grassmap.navigation.GeometryRouteFollower
import nl.r1a.grassmap.navigation.NavigationConfig
import nl.r1a.grassmap.navigation.NavigationSession
import nl.r1a.grassmap.navigation.RouteFollowerEngine
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.command.CommandPolicy
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.provider.ProviderContract
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteRequest
import nl.r1a.grassmap.plugin.api.provider.RoutingCapabilities
import nl.r1a.grassmap.plugin.api.provider.RoutingEngine
import nl.r1a.grassmap.plugin.api.provider.RoutingEngineContract
import nl.r1a.grassmap.routing.RouteJson
import kotlin.math.cos
import kotlin.math.hypot

/** The debug command that replays a route and logs its turn beeps (T-0318). */
internal val NAV_BEEP_PROBE_COMMAND: CommandId = CommandId("core.nav.beep_probe")

/**
 * Replays a route in the stable [RouteJson] form through the production
 * `NavigationSession` and feeds every published state into the turn-beep host.
 * The fixes are the route's own vertices, one per [tickMillis] of wall time,
 * with timestamps at the profile's nominal speed. A route whose vertices are
 * farther apart than the beep distance can therefore skip the crossing; the
 * committed `beep-route.json` is dense enough for a walking beep.
 *
 * It exists for the device probe: the logcat tag `GrassMapsBeep` then carries
 * one line per turn, and the audio focus is held for the beep window so
 * `dumpsys audio` can see it. It changes no navigation policy and its engine
 * never reroutes.
 */
internal class BeepProbeCommand(
    private val beeps: () -> BeepHost,
    private val followerFactory: () -> RouteFollowerEngine = { GeometryRouteFollower() },
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
) : Command<JsonElement, String>(
        id = NAV_BEEP_PROBE_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc =
            "Replays a route through the real session and logs turn beeps. " +
                "Parameters: route (stable route JSON), optional profile (walk, bike, hike, car), " +
                "optional tick_ms.",
        policy =
            CommandPolicy(
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.IO,
            ),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val objectValue = params as? JsonObject ?: return Outcome.Failure(AppError.InvalidInput)
        val route = decodeRoute(objectValue["route"]) ?: return Outcome.Failure(AppError.InvalidInput)
        val profile = profileOf(objectValue["profile"]) ?: return Outcome.Failure(AppError.InvalidInput)
        val tickMillis = tickOf(objectValue["tick_ms"]) ?: return Outcome.Failure(AppError.InvalidInput)
        val fixes = fixesOf(route, profile)
        if (fixes.size < MIN_FIXES) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        val host = beeps()
        host.setProfile(profile)
        val before = host.beepCount
        val session =
            NavigationSession(
                follower = followerFactory(),
                routing = ProbeNoRerouteEngine,
                profile = profile,
                config = NavigationConfig(reroute = false),
                clockMillis = { System.nanoTime() / 1_000_000L },
            )
        val run =
            coroutineScope {
                val collector =
                    launch {
                        session.state.collect { state -> host.onState(state) }
                    }
                try {
                    session.run(
                        route,
                        flow {
                            fixes.forEach { fix ->
                                emit(fix)
                                delayMillis(tickMillis)
                            }
                        },
                    )
                } finally {
                    collector.cancel()
                }
            }
        return when (run) {
            is Outcome.Failure -> {
                run
            }

            is Outcome.Success -> {
                Outcome.Success(
                    CommandResult(
                        "beeps=${host.beepCount - before} fixes=${run.value.fixCount} " +
                            "arrived=${run.value.arrived} profile=${profile.name.lowercase()} " +
                            "threshold_m=${BeepDistance.forProfile(profile)}",
                    ),
                )
            }
        }
    }

    private fun decodeRoute(element: JsonElement?): Route? {
        val json =
            when (element) {
                is JsonObject -> element.toString()
                is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() }
                else -> null
            } ?: return null
        return when (val decoded = RouteJson.decode(json)) {
            is Outcome.Success -> decoded.value
            is Outcome.Failure -> null
        }
    }

    private fun profileOf(element: JsonElement?): RouteProfile? {
        val name = (element as? JsonPrimitive)?.contentOrNull ?: return RouteProfile.Walk
        return RouteProfile.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    private fun tickOf(element: JsonElement?): Long? {
        val tick = (element as? JsonPrimitive)?.intOrNull?.toLong() ?: DEFAULT_TICK_MILLIS
        return tick.takeIf { it in MIN_TICK_MILLIS..MAX_TICK_MILLIS }
    }

    private fun fixesOf(
        route: Route,
        profile: RouteProfile,
    ): List<LocationFix> {
        val speed = NavigationConfig().speedMetersPerSecond(profile)
        var distance = 0.0
        return route.geometry.mapIndexed { index, point ->
            if (index > 0) {
                distance += distanceMeters(route.geometry[index - 1], point)
            }
            LocationFix(
                position = point,
                accuracyMeters = FIX_ACCURACY_METERS,
                timeMillis = BASE_MILLIS + (distance / speed * MILLIS_PER_SECOND).toLong(),
            )
        }
    }

    /** The equirectangular distance is accurate enough for a fixture at this scale. */
    private fun distanceMeters(
        from: GeoPoint,
        to: GeoPoint,
    ): Double {
        val latitude = Math.toRadians((from.latitude + to.latitude) / 2.0)
        val east = (to.longitude - from.longitude) * METERS_PER_DEGREE * cos(latitude)
        val north = (to.latitude - from.latitude) * METERS_PER_DEGREE
        return hypot(east, north)
    }

    private companion object {
        const val DEFAULT_TICK_MILLIS = 200L
        const val MIN_TICK_MILLIS = 20L
        const val MAX_TICK_MILLIS = 5_000L
        const val MIN_FIXES = 2
        const val FIX_ACCURACY_METERS = 5.0
        const val BASE_MILLIS = 1_700_000_000_000L
        const val METERS_PER_DEGREE = 111_320.0
        const val MILLIS_PER_SECOND = 1_000.0
    }
}

/**
 * The probe never leaves the route, so it needs a routing engine only because
 * the session's constructor takes one. It refuses every request instead of
 * inventing a replacement line.
 */
private object ProbeNoRerouteEngine : RoutingEngine {
    override val contract: ProviderContract = RoutingEngineContract.REFERENCE

    override fun capabilities(): RoutingCapabilities =
        RoutingCapabilities(
            profiles = RouteProfile.entries.toSet(),
            modifierKinds = emptySet(),
            maxAlternatives = 0,
            maxDistanceMeters = 250_000.0,
        )

    override suspend fun route(
        request: RouteRequest,
        cancellation: CancellationHandle,
    ): Outcome<List<Route>> = Outcome.Failure(AppError.Unsupported)
}
