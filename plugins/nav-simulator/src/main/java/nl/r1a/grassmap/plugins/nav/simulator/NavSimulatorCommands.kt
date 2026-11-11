package nl.r1a.grassmap.plugins.nav.simulator

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.command.CommandPolicy
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.contract.StreamId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.StreamAccess
import nl.r1a.grassmap.plugin.api.model.RoutePlanState
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import nl.r1a.grassmap.routing.RouteJson

private val SIMULATOR_POLICY =
    CommandPolicy(
        undoable = false,
        allowlistedForControl = true,
        dispatcher = CommandDispatcher.IO,
    )

/**
 * Starts a simulated run at 5 km/h on the current route, or on the stable
 * route JSON passed as `route`. An optional `profile` picks the session's
 * travel mode; the speed does not change.
 */
class StartSimulationCommand(
    private val simulator: NavigationSimulator,
    private val streams: StreamAccess,
) : Command<JsonElement, SimulatorStatus>(
        id = NavSimulatorContract.start,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = SimulatorStatus.serializer(),
        doc = START_DOC,
        policy = SIMULATOR_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<SimulatorStatus>> {
        val objectParams = params.asObjectOrEmpty() ?: return Outcome.Failure(AppError.InvalidInput)
        val profile = profileOf(objectParams["profile"]) ?: return Outcome.Failure(AppError.InvalidInput)
        val route =
            when (val element = objectParams["route"]) {
                null, JsonNull -> currentRoute()
                else -> RouteJson.decode(element.toString())
            }
        return when (val resolved = route) {
            is Outcome.Failure -> {
                resolved
            }

            is Outcome.Success -> {
                when (val started = simulator.start(resolved.value, profile)) {
                    is Outcome.Success -> Outcome.Success(CommandResult(started.value))
                    is Outcome.Failure -> started
                }
            }
        }
    }

    /** The route planner's selected route, the app's current route on this build. */
    private suspend fun currentRoute(): Outcome<Route> {
        val state =
            try {
                streams
                    .subscribe<RoutePlanState>(StreamId(NavSimulatorContract.CURRENT_ROUTE_STREAM_ID))
                    .firstOrNull()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                null
            }
        val selected = state?.selected ?: return Outcome.Failure(AppError.NotFound)
        return Outcome.Success(
            Route(
                geometry = selected.geometry,
                distanceMeters = selected.distanceMeters,
                durationSeconds = selected.durationSeconds,
                segments = selected.segments,
                instructions = selected.instructions,
            ),
        )
    }

    private companion object {
        const val START_DOC =
            "Starts a simulated run at 5 km/h. Uses the current route, or the route in params. " +
                "Parameters: optional route (stable route JSON), optional profile (walk, bike, hike, car)."
    }
}

/** Stops the run by ending its fix flow; the session publishes `Stopped` itself. */
class StopSimulationCommand(
    private val simulator: NavigationSimulator,
) : Command<JsonElement, SimulatorStatus>(
        id = NavSimulatorContract.stop,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = SimulatorStatus.serializer(),
        doc = "Stops the simulated run and returns its phase, fix count and arrival.",
        policy = SIMULATOR_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<SimulatorStatus>> =
        Outcome.Success(CommandResult(simulator.stop()))
}

private fun JsonElement.asObjectOrEmpty(): JsonObject? =
    when (this) {
        JsonNull -> JsonObject(emptyMap())
        is JsonObject -> this
        else -> null
    }

private fun profileOf(element: JsonElement?): RouteProfile? {
    val text = (element as? JsonPrimitive)?.contentOrNull?.lowercase() ?: PROFILE_WALK
    return when (text) {
        PROFILE_WALK -> RouteProfile.Walk
        PROFILE_BIKE -> RouteProfile.Bike
        PROFILE_HIKE -> RouteProfile.Hike
        PROFILE_CAR -> RouteProfile.Car
        else -> null
    }
}

private const val PROFILE_WALK = "walk"
private const val PROFILE_BIKE = "bike"
private const val PROFILE_HIKE = "hike"
private const val PROFILE_CAR = "car"
