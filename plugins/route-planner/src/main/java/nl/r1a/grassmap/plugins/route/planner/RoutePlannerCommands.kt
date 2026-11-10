package nl.r1a.grassmap.plugins.route.planner

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.command.CommandPolicy
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.RoutePlanRole
import nl.r1a.grassmap.plugin.api.model.RoutePlanState
import nl.r1a.grassmap.plugin.api.model.RoutePlanStatus

/**
 * The planner commands (T-0302). Each returns the state it produced, so the
 * shell and the control channel read exactly what the planner published. The
 * route request runs on IO because the engine may touch tile files.
 */
private val PLANNER_POLICY: CommandPolicy =
    CommandPolicy(
        undoable = false,
        allowlistedForControl = true,
        dispatcher = CommandDispatcher.IO,
    )

/** Opens the planner and starts picking the start point. */
class BeginRoutePlanCommand(
    private val session: RoutePlannerSession,
) : Command<JsonElement, RoutePlanState>(
        id = RoutePlannerContract.begin,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = RoutePlanState.serializer(),
        doc = "Opens the route planner and starts picking the start point.",
        policy = PLANNER_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<RoutePlanState>> = result(session.begin())
}

/** Sets one end from a coordinate and an optional label. */
class SetRoutePlanPointCommand(
    private val session: RoutePlannerSession,
) : Command<JsonElement, RoutePlanState>(
        id = RoutePlannerContract.setPoint,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = RoutePlanState.serializer(),
        doc = "Sets one end of the route. Parameters: role (start or destination), lat, lon, and optionally label.",
        policy = PLANNER_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<RoutePlanState>> {
        val role =
            when (params.text(PARAM_ROLE)?.lowercase()) {
                ROLE_START -> RoutePlanRole.Start
                ROLE_DESTINATION -> RoutePlanRole.Destination
                else -> return Outcome.Failure(AppError.InvalidInput)
            }
        val latitude = params.number(PARAM_LAT) ?: return Outcome.Failure(AppError.InvalidInput)
        val longitude = params.number(PARAM_LON) ?: return Outcome.Failure(AppError.InvalidInput)
        return result(session.setPoint(role, GeoPoint(latitude, longitude), params.text(PARAM_LABEL)))
    }

    private companion object {
        const val PARAM_ROLE = "role"
        const val PARAM_LAT = "lat"
        const val PARAM_LON = "lon"
        const val PARAM_LABEL = "label"
        const val ROLE_START = "start"
        const val ROLE_DESTINATION = "destination"
    }
}

/** Requests the current pair again. */
class PlanRouteCommand(
    private val session: RoutePlannerSession,
) : Command<JsonElement, RoutePlanState>(
        id = RoutePlannerContract.plan,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = RoutePlanState.serializer(),
        doc = "Requests the current start and destination again.",
        policy = PLANNER_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<RoutePlanState>> = result(session.plan())
}

/** Swaps the two ends and routes the reversed pair. */
class SwapRoutePlanCommand(
    private val session: RoutePlannerSession,
) : Command<JsonElement, RoutePlanState>(
        id = RoutePlannerContract.swap,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = RoutePlanState.serializer(),
        doc = "Swaps the start and destination and routes the reversed pair.",
        policy = PLANNER_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<RoutePlanState>> = result(session.swap())
}

/** Selects a returned route by index. */
class SelectRoutePlanOptionCommand(
    private val session: RoutePlannerSession,
) : Command<JsonElement, RoutePlanState>(
        id = RoutePlannerContract.select,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = RoutePlanState.serializer(),
        doc = "Selects a returned route by its zero-based index. Parameter: index.",
        policy = PLANNER_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<RoutePlanState>> {
        val index = params.integer(PARAM_INDEX) ?: return Outcome.Failure(AppError.InvalidInput)
        return result(session.select(index))
    }

    private companion object {
        const val PARAM_INDEX = "index"
    }
}

/**
 * The Start action of the preview. Guidance itself is T-0309, so a ready plan
 * answers [AppError.Unsupported] instead of pretending to navigate.
 */
class StartRoutePlanCommand(
    private val session: RoutePlannerSession,
) : Command<JsonElement, RoutePlanState>(
        id = RoutePlannerContract.start,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = RoutePlanState.serializer(),
        doc = "Starts guidance on the selected route; guidance lands in T-0309.",
        policy = PLANNER_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<RoutePlanState>> {
        val state = session.state.value
        if (state.status != RoutePlanStatus.Ready || state.selected == null) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        return Outcome.Failure(AppError.Unsupported)
    }
}

/** Closes the plan and removes its route. */
class ClearRoutePlanCommand(
    private val session: RoutePlannerSession,
) : Command<JsonElement, RoutePlanState>(
        id = RoutePlannerContract.clear,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = RoutePlanState.serializer(),
        doc = "Closes the plan and removes its route.",
        policy = PLANNER_POLICY,
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<RoutePlanState>> = result(session.clear())
}

private fun result(outcome: Outcome<RoutePlanState>): Outcome<CommandResult<RoutePlanState>> =
    when (outcome) {
        is Outcome.Success -> Outcome.Success(CommandResult(outcome.value))
        is Outcome.Failure -> outcome
    }

private fun JsonElement.text(key: String): String? {
    val json = this as? JsonObject ?: return null
    return (json[key] as? JsonPrimitive)
        ?.takeIf { primitive -> primitive.isString }
        ?.contentOrNull
        ?.trim()
        ?.takeIf { value -> value.isNotEmpty() }
}

private fun JsonElement.number(key: String): Double? {
    val json = this as? JsonObject ?: return null
    return (json[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
}

private fun JsonElement.integer(key: String): Int? {
    val json = this as? JsonObject ?: return null
    return (json[key] as? JsonPrimitive)?.intOrNull
}
