package nl.r1a.grassmap.app

import android.util.Log
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
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
import nl.r1a.grassmap.plugin.api.provider.CostModifierRequest
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteModifier
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.plugin.api.provider.RouteRequest
import nl.r1a.grassmap.plugin.api.provider.RoutingEngine

/** The debug trigger the control channel invokes to prove the routing seam. */
internal val ROUTE_TEST_COMMAND: CommandId = CommandId("core.route.test_walk")

private const val TAG = "GrassMapsRoute"
private const val AREA_FIXTURE = "fixture"
private const val AREA_LVIV = "lviv"
private const val SAME_GEOMETRY_METRES = 5.0
private const val MAX_LOGGED_INSTRUCTIONS = 3

/**
 * Routes a short walk and draws it, either around the synthetic DataSource
 * fixture or in Lviv. With `{"modifiers": true}` it asks the synthetic
 * DataSource for cost modifiers, routes again and reports whether the line
 * moved.
 *
 * The command is a host command with no UI binding; only the debug control
 * channel can invoke it, because only a debug build carries that channel.
 */
internal class TestWalkCommand(
    private val session: MapSessionHolder,
    private val engine: () -> RoutingEngine?,
    private val dataSources: () -> DataSourceAccess,
    private val log: (String) -> Unit = { message -> Log.i(TAG, message) },
) : Command<JsonElement, String>(
        id = ROUTE_TEST_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Routes a short walk in the fixture or Lviv area and draws it.",
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
        val request = RouteRequest(waypoints = selection.waypoints, profile = RouteProfile.Walk)
        val primary =
            when (val outcome = active.route(request, CancellationHandle.None)) {
                is Outcome.Failure -> {
                    log("route failed: ${outcome.error}")
                    return outcome
                }

                is Outcome.Success -> {
                    outcome.value.firstOrNull() ?: return Outcome.Failure(AppError.NotFound)
                }
            }
        draw(host, primary)
        val baseSummary = logRoute(selection.area, "base", primary, modifiers = 0)
        if (!selection.withModifiers) {
            return Outcome.Success(CommandResult(baseSummary))
        }

        val sources = dataSources()
        val modifiers =
            when (
                val outcome =
                    sources.costModifiers(
                        CostModifierRequest(
                            waypoints = selection.waypoints,
                            profile = "walk",
                        ),
                        CancellationHandle.None,
                    )
            ) {
                is Outcome.Failure -> {
                    log("cost modifiers failed: ${outcome.error}")
                    return outcome
                }

                is Outcome.Success -> {
                    outcome.value
                }
            }
        if (modifiers.isEmpty()) {
            return Outcome.Success(
                CommandResult(
                    logRoute(selection.area, "modified", primary, modifiers = 0) +
                        " base_m=${primary.distanceMeters} changed=false",
                ),
            )
        }
        log(
            "cost modifiers from ${sources.pluginId}: ${modifiers.size} " +
                modifiers.joinToString { "way=${it.osmWayId} mult=${it.multiplier} add=${it.addedSeconds}" },
        )
        val requested = request.copy(modifiers = modifiers.map(RouteModifier::fromCostModifier))
        val modified =
            when (val outcome = active.route(requested, CancellationHandle.None)) {
                is Outcome.Failure -> {
                    log("modified route failed: ${outcome.error}")
                    return outcome
                }

                is Outcome.Success -> {
                    outcome.value.firstOrNull() ?: return Outcome.Failure(AppError.NotFound)
                }
            }
        draw(host, modified)
        val changed = geometryChanged(primary, modified)
        log(
            "route-changed area=${selection.area} modifiers=${modifiers.size} changed=$changed " +
                "base_m=${primary.distanceMeters} modified_m=${modified.distanceMeters}",
        )
        return Outcome.Success(
            CommandResult(
                logRoute(selection.area, "modified", modified, modifiers = modifiers.size) +
                    " base_m=${primary.distanceMeters} changed=$changed",
            ),
        )
    }

    private suspend fun draw(
        host: MapHost,
        route: Route,
    ) {
        host.setRoute(route).onFailure { error -> log("route not drawn: $error") }
        val bounds = boundsOf(route.geometry)
        if (bounds != null) {
            host.fitBounds(bounds).onFailure { error -> log("route not fitted: $error") }
        }
    }

    private fun logRoute(
        area: String,
        phase: String,
        route: Route,
        modifiers: Int,
    ): String {
        val routeSummary =
            "engine=valhalla area=$area phase=$phase modifiers=$modifiers " +
                "distance_m=${route.distanceMeters} " +
                "duration_s=${route.durationSeconds} " +
                "points=${route.geometry.size}"
        log(routeSummary)
        route.instructions.take(MAX_LOGGED_INSTRUCTIONS).forEach { instruction ->
            log("route-instruction ${instruction.distanceAlongRouteMeters.toInt()}m ${instruction.text}")
        }
        return routeSummary
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
        val withModifiers =
            (objectValue["modifiers"] as? JsonPrimitive)?.booleanOrNull ?: false
        val waypoints =
            when (area) {
                AREA_FIXTURE -> FIXTURE_WAYPOINTS
                AREA_LVIV -> LVIV_WAYPOINTS
                else -> return null
            }
        return Selection(area, waypoints, withModifiers)
    }

    private fun geometryChanged(
        first: Route,
        second: Route,
    ): Boolean {
        if (first.geometry.size != second.geometry.size) {
            return true
        }
        return first.geometry
            .zip(second.geometry)
            .any { (left, right) -> distanceMetres(left, right) > SAME_GEOMETRY_METRES }
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

    private fun distanceMetres(
        from: GeoPoint,
        to: GeoPoint,
    ): Double {
        val latitudeDelta = Math.toRadians(to.latitude - from.latitude)
        val longitudeDelta = Math.toRadians(to.longitude - from.longitude)
        val meanLatitude = Math.toRadians((from.latitude + to.latitude) / 2.0)
        val east = longitudeDelta * kotlin.math.cos(meanLatitude)
        return kotlin.math.hypot(latitudeDelta, east) * EARTH_RADIUS_METRES
    }

    private data class Selection(
        val area: String,
        val waypoints: List<GeoPoint>,
        val withModifiers: Boolean,
    )

    private companion object {
        const val EARTH_RADIUS_METRES = 6_371_000.0

        /** A short walk past both synthetic modifier lines. */
        val FIXTURE_WAYPOINTS =
            listOf(
                GeoPoint(49.93968, 23.94142),
                GeoPoint(49.9455, 23.94985),
            )

        /** Rynok Square south towards the opera house. */
        val LVIV_WAYPOINTS =
            listOf(
                GeoPoint(49.8419, 24.0316),
                GeoPoint(49.8365, 24.0250),
            )
    }
}

internal fun routeCommands(
    session: MapSessionHolder,
    engine: () -> RoutingEngine?,
    dataSources: () -> DataSourceAccess,
): List<Command<*, *>> = listOf(TestWalkCommand(session, engine, dataSources))
