package nl.r1a.grassmap.app

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import nl.r1a.grassmap.alerts.AlertHost
import nl.r1a.grassmap.alerts.AlertKind
import nl.r1a.grassmap.alerts.BeepHost
import nl.r1a.grassmap.core.services.MockGpxLocationSource
import nl.r1a.grassmap.navigation.FerrostarRouteFollower
import nl.r1a.grassmap.navigation.NavigationConfig
import nl.r1a.grassmap.navigation.NavigationPhase
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
import nl.r1a.grassmap.plugin.api.error.onFailure
import nl.r1a.grassmap.plugin.api.host.MapHost
import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteRequest
import nl.r1a.grassmap.plugin.api.provider.RoutingEngine
import nl.r1a.grassmap.routing.RouteJson

/** The debug trigger that proves the navigation seam on a replay. */
internal val NAV_TEST_COMMAND: CommandId = CommandId("core.nav.test_follow")

/** Starts live guidance on a route passed in the stable route JSON form. */
internal val NAV_START_COMMAND: CommandId = CommandId("core.nav.start")

/** Pauses live guidance: no fix reaches the follower and no reroute starts. */
internal val NAV_PAUSE_COMMAND: CommandId = CommandId("core.nav.pause")

/** Resumes live guidance from the next fix. */
internal val NAV_RESUME_COMMAND: CommandId = CommandId("core.nav.resume")

/** Ends live guidance and releases the navigation foreground service. */
internal val NAV_STOP_COMMAND: CommandId = CommandId("core.nav.stop")

/** Turns the T-0318 turn-beep mode on or off. */
internal val NAV_BEEPS_COMMAND: CommandId = CommandId("core.nav.beeps")

/** Turns the T-0319 way alerts on or off, all three or one kind. */
internal val NAV_WAY_ALERTS_COMMAND: CommandId = CommandId("core.nav.way_alerts")

private const val TAG = "GrassMapsNav"
private const val AREA_FIXTURE = "fixture"
private const val AREA_LVIV = "lviv"
private const val DEFAULT_TICK_MILLIS = 500L

/**
 * Routes a walk, builds a GPX replay around that exact line with a deliberate
 * off-route segment, and follows it with the real navigation session.
 *
 * The replay is generated from the returned geometry, not committed, so the
 * detour is guaranteed to leave the route whatever the routing pack says. It
 * still goes through [MockGpxLocationSource], so the T-0022 GPX parser and the
 * one-fix-per-tick replay are the ones exercised. The command logs on-route,
 * off-route, reroute and arrival lines and returns the measured milliseconds.
 */
internal class TestFollowCommand(
    private val session: MapSessionHolder,
    private val engine: suspend () -> RoutingEngine?,
    private val log: (String) -> Unit = { message -> Log.i(TAG, message) },
    private val followerFactory: () -> RouteFollowerEngine = { FerrostarRouteFollower(log = log) },
    private val config: NavigationConfig = NavigationConfig(),
    private val tickMillis: Long = DEFAULT_TICK_MILLIS,
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
) : Command<JsonElement, String>(
        id = NAV_TEST_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Routes a walk, replays a GPX that leaves it, and reports the reroute.",
        policy =
            CommandPolicy(
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.IO,
            ),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val host = session.hostOrNull() ?: return Outcome.Failure(AppError.NotReady)
        val active = engine() ?: return Outcome.Failure(AppError.NotReady)
        val selection = parse(params) ?: return Outcome.Failure(AppError.InvalidInput)
        val request = RouteRequest(waypoints = selection.waypoints, profile = selection.profile)
        val route =
            when (val outcome = active.route(request, CancellationHandle.None)) {
                is Outcome.Failure -> {
                    log("follow route failed: ${outcome.error}")
                    return outcome
                }

                is Outcome.Success -> {
                    outcome.value.firstOrNull() ?: return Outcome.Failure(AppError.NotFound)
                }
            }
        draw(host, route)
        val replay =
            NavReplay.gpx(
                route = route,
                detourMeters = selection.detourMeters,
                speedMetersPerSecond = config.speedMetersPerSecond(selection.profile),
            ) ?: return Outcome.Failure(AppError.InvalidInput)
        val source =
            when (
                val outcome =
                    MockGpxLocationSource.fromGpx(
                        replay,
                        selection.tickMillis,
                        delayMillis = delayMillis,
                    )
            ) {
                is Outcome.Success -> {
                    outcome.value
                }

                is Outcome.Failure -> {
                    return outcome
                }
            }
        log(
            "follow-start area=${selection.area} profile=${selection.profile} points=${route.geometry.size} " +
                "base_m=${route.distanceMeters} tick_ms=${selection.tickMillis} detour_m=${selection.detourMeters} " +
                "gate_m=${config.travelMeters(selection.profile)}",
        )
        val navigation =
            NavigationSession(
                follower = followerFactory(),
                routing = active,
                profile = selection.profile,
                config = config.copy(reroute = selection.reroute),
                clockMillis = { System.nanoTime() / 1_000_000L },
                log = log,
            )
        val run =
            when (val outcome = navigation.run(route, source.fixes.filterNotNull())) {
                is Outcome.Failure -> {
                    log("follow failed: ${outcome.error}")
                    return outcome
                }

                is Outcome.Success -> {
                    outcome.value
                }
            }
        val finalRoute = run.finalRoute ?: route
        draw(host, finalRoute)
        val summary =
            "engine=valhalla area=${selection.area} profile=${selection.profile} fixes=${run.fixCount} " +
                "inaccurate_fixes=${run.inaccurateFixCount} off_route_fixes=${run.offRouteFixCount} " +
                "reroutes=${run.rerouteCount} " +
                "arrived=${run.arrived} detection_ms=${run.detectionMillis ?: -1} " +
                "reroute_ms=${run.lastRerouteMillis ?: -1} " +
                "base_m=${route.distanceMeters} final_m=${finalRoute.distanceMeters} " +
                "points=${finalRoute.geometry.size}"
        log("follow-done $summary")
        return Outcome.Success(CommandResult(summary))
    }

    private suspend fun draw(
        host: MapHost,
        route: Route,
    ) {
        host.setRoute(route).onFailure { error -> log("nav route not drawn: $error") }
        val bounds = boundsOf(route.geometry)
        if (bounds != null) {
            host.fitBounds(bounds).onFailure { error -> log("nav route not fitted: $error") }
        }
    }

    private fun parse(params: JsonElement): Selection? {
        val objectValue =
            when (params) {
                is JsonNull -> JsonObject(emptyMap())
                is JsonObject -> params
                else -> return null
            }
        val area =
            (objectValue["area"] as? JsonPrimitive)?.contentOrNull?.lowercase() ?: AREA_FIXTURE
        val waypoints =
            when (area) {
                AREA_FIXTURE -> FIXTURE_WAYPOINTS
                AREA_LVIV -> LVIV_BENCH_WAYPOINTS
                else -> return null
            }
        val tick =
            (objectValue["tick_ms"] as? JsonPrimitive)?.intOrNull?.takeIf { it in MIN_TICK_MILLIS..MAX_TICK_MILLIS }
                ?: tickMillis.toInt()
        val detour =
            (objectValue["detour_m"] as? JsonPrimitive)
                ?.doubleOrNull
                ?.takeIf { it in NavReplay.MIN_DETOUR_METERS..NavReplay.MAX_DETOUR_METERS }
                ?: NavReplay.DEFAULT_DETOUR_METERS
        val reroute = (objectValue["reroute"] as? JsonPrimitive)?.booleanOrNull ?: true
        val profile =
            when ((objectValue["profile"] as? JsonPrimitive)?.contentOrNull?.lowercase() ?: PROFILE_WALK) {
                PROFILE_WALK -> RouteProfile.Walk
                PROFILE_BIKE -> RouteProfile.Bike
                PROFILE_HIKE -> RouteProfile.Hike
                else -> return null
            }
        return Selection(area, waypoints, tick.toLong(), detour, reroute, profile)
    }

    private fun boundsOf(points: List<GeoPoint>): BoundingBox? {
        if (points.isEmpty()) {
            return null
        }
        return BoundingBox(
            southWest = GeoPoint(points.minOf { it.latitude }, points.minOf { it.longitude }),
            northEast = GeoPoint(points.maxOf { it.latitude }, points.maxOf { it.longitude }),
        )
    }

    private data class Selection(
        val area: String,
        val waypoints: List<GeoPoint>,
        val tickMillis: Long,
        val detourMeters: Double,
        val reroute: Boolean,
        val profile: RouteProfile,
    )

    private companion object {
        const val PROFILE_WALK = "walk"
        const val PROFILE_BIKE = "bike"
        const val PROFILE_HIKE = "hike"
        const val MIN_TICK_MILLIS = 50
        const val MAX_TICK_MILLIS = 5_000

        /** A short walk past both synthetic modifier lines in the fixture area. */
        val FIXTURE_WAYPOINTS =
            listOf(
                GeoPoint(49.93968, 23.94142),
                GeoPoint(49.9455, 23.94985),
            )
    }
}

internal fun navCommands(
    session: MapSessionHolder,
    engine: suspend () -> RoutingEngine?,
    navigation: NavigationController,
    guidance: GuidanceStarter,
    beeps: () -> BeepHost,
    alerts: () -> AlertHost,
): List<Command<*, *>> =
    listOf(
        TestFollowCommand(session, engine),
        StartNavigationCommand(navigation, guidance),
        PauseNavigationCommand(navigation),
        ResumeNavigationCommand(navigation),
        StopNavigationCommand(navigation),
        BeepsCommand(beeps),
        BeepProbeCommand(beeps),
        WayAlertsCommand(alerts),
    )

/**
 * Starts live navigation on a route in the stable [RouteJson] form:
 * `{"route": {...}, "profile": "walk"}`. The route is passed in rather than
 * requested here, so the command works without a routing pack; that is also
 * how the emulator acceptance drives the foreground service.
 *
 * The session is started through [GuidanceStarter], the same entry point the
 * planner's Start and the follow-track Start use, so the tone source and both
 * alert hosts are prepared here exactly as they are for a trip the user starts
 * from the UI.
 *
 * A second start while a session runs is not an error: it reports
 * `started=false` and leaves the running session, its route and its alert
 * latches untouched.
 */
internal class StartNavigationCommand(
    private val navigation: NavigationController,
    private val guidance: GuidanceStarter,
) : Command<JsonElement, String>(
        id = NAV_START_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Starts guidance on a route in the stable route JSON form.",
        policy =
            CommandPolicy(
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.DEFAULT,
            ),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val selection = parse(params) ?: return Outcome.Failure(AppError.InvalidInput)
        return when (val outcome = guidance.start(selection.route, selection.profile)) {
            is Outcome.Failure -> {
                outcome
            }

            is Outcome.Success -> {
                val state = navigation.state.value
                Outcome.Success(
                    CommandResult(
                        "started=${outcome.value} phase=${state.phase} " +
                            "points=${selection.route.geometry.size} " +
                            "distance_m=${selection.route.distanceMeters} " +
                            "profile=${selection.profile}",
                    ),
                )
            }
        }
    }

    private fun parse(params: JsonElement): Selection? {
        val objectValue = params as? JsonObject ?: return null
        val routeElement = objectValue["route"] ?: return null
        val json =
            when (routeElement) {
                is JsonObject -> routeElement.toString()
                is JsonPrimitive -> routeElement.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
                else -> return null
            }
        val route =
            when (val decoded = RouteJson.decode(json)) {
                is Outcome.Success -> decoded.value
                is Outcome.Failure -> return null
            }
        val profile =
            (objectValue["profile"] as? JsonPrimitive)
                ?.contentOrNull
                ?.let { name -> RouteProfile.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
                ?: RouteProfile.Walk
        return Selection(route, profile)
    }

    private data class Selection(
        val route: Route,
        val profile: RouteProfile,
    )
}

/** Pauses guidance. Pausing without a session changes nothing. */
internal class PauseNavigationCommand(
    private val navigation: NavigationController,
) : Command<JsonElement, String>(
        id = NAV_PAUSE_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Pauses live guidance; fixes and reroutes stop until resume.",
        policy =
            CommandPolicy(
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.DEFAULT,
            ),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val changed = navigation.pause()
        val state = navigation.state.value
        return Outcome.Success(
            CommandResult("changed=$changed phase=${state.phase} paused=${state.phase == NavigationPhase.Paused}"),
        )
    }
}

/** Resumes guidance from the next fix. Resuming a running session changes nothing. */
internal class ResumeNavigationCommand(
    private val navigation: NavigationController,
) : Command<JsonElement, String>(
        id = NAV_RESUME_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Resumes live guidance from the next fix.",
        policy =
            CommandPolicy(
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.DEFAULT,
            ),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val changed = navigation.resume()
        val state = navigation.state.value
        return Outcome.Success(CommandResult("changed=$changed phase=${state.phase}"))
    }
}

/** Ends guidance and its foreground service. Stopping without a session changes nothing. */
internal class StopNavigationCommand(
    private val navigation: NavigationController,
) : Command<JsonElement, String>(
        id = NAV_STOP_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Ends live guidance and removes the navigation notification.",
        policy =
            CommandPolicy(
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.DEFAULT,
            ),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val stopped = navigation.stop()
        return Outcome.Success(CommandResult("stopped=$stopped phase=${navigation.state.value.phase}"))
    }
}

/**
 * Turns the turn-beep mode on or off (T-0318). The profile picks the distance;
 * leaving it out keeps the current one, which `core.nav.start` sets for a new
 * session anyway.
 */
internal class BeepsCommand(
    private val beeps: () -> BeepHost,
) : Command<JsonElement, String>(
        id = NAV_BEEPS_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Turns turn beeps on or off. Parameters: enabled, optional profile (walk, bike, hike, car).",
        policy =
            CommandPolicy(
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.DEFAULT,
            ),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val objectValue = params as? JsonObject ?: return Outcome.Failure(AppError.InvalidInput)
        val enabled =
            (objectValue["enabled"] as? JsonPrimitive)?.booleanOrNull
                ?: return Outcome.Failure(AppError.InvalidInput)
        val profileName = (objectValue["profile"] as? JsonPrimitive)?.contentOrNull
        val profile =
            profileName?.let { name ->
                RouteProfile.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?: return Outcome.Failure(AppError.InvalidInput)
            }
        val host = beeps()
        profile?.let(host::setProfile)
        host.setEnabled(enabled)
        return Outcome.Success(
            CommandResult(
                "enabled=${host.enabled.value} profile=${host.profile.name.lowercase()} " +
                    "threshold_m=${host.thresholdMeters}",
            ),
        )
    }
}

/**
 * Turns the T-0319 way alerts on or off. `kind` is `all` (the default),
 * `tunnel`, `railway_crossing` or `hazard`; `all` touches the three switches
 * the way alerts own and leaves the T-0317 switches alone.
 */
internal class WayAlertsCommand(
    private val alerts: () -> AlertHost,
) : Command<JsonElement, String>(
        id = NAV_WAY_ALERTS_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Turns way alerts on or off. Parameters: enabled, optional kind (all, tunnel, railway_crossing, hazard).",
        policy =
            CommandPolicy(
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.DEFAULT,
            ),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val objectValue = params as? JsonObject ?: return Outcome.Failure(AppError.InvalidInput)
        val enabled =
            (objectValue["enabled"] as? JsonPrimitive)?.booleanOrNull
                ?: return Outcome.Failure(AppError.InvalidInput)
        val kindName = (objectValue["kind"] as? JsonPrimitive)?.contentOrNull?.lowercase() ?: KIND_ALL
        val kinds =
            when (kindName) {
                KIND_ALL -> listOf(AlertKind.Tunnel, AlertKind.RailwayCrossing, AlertKind.Hazard)
                KIND_TUNNEL -> listOf(AlertKind.Tunnel)
                KIND_CROSSING -> listOf(AlertKind.RailwayCrossing)
                KIND_HAZARD -> listOf(AlertKind.Hazard)
                else -> return Outcome.Failure(AppError.InvalidInput)
            }
        val host = alerts()
        kinds.forEach { kind -> host.setEnabled(kind, enabled) }
        return Outcome.Success(
            CommandResult("enabled=$enabled kinds=${kinds.joinToString(",") { it.name.lowercase() }}"),
        )
    }

    private companion object {
        const val KIND_ALL = "all"
        const val KIND_TUNNEL = "tunnel"
        const val KIND_CROSSING = "railway_crossing"
        const val KIND_HAZARD = "hazard"
    }
}
