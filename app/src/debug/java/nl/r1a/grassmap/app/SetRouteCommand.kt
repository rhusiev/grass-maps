package nl.r1a.grassmap.app

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.command.CommandPolicy
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.provider.Route
import nl.r1a.grassmap.routing.RouteJson

/** The debug-only command the map goldens use to put a fixed route on the map. */
internal val SET_ROUTE_COMMAND: CommandId = CommandId("core.map.set_route")

/**
 * Draws a route in the stable [RouteJson] form, or clears the route with
 * `{"route": null}`. It exists only in the debug source set; the product draws
 * a route through the planner's selected option.
 *
 * The T-0311 goldens need a route on a fixture map that has no routing pack, so
 * the fixed route and its turn maneuvers are committed with the golden data and
 * this command hands them to the same route layer the app uses.
 */
internal class SetRouteCommand(
    private val session: MapSessionHolder,
) : Command<JsonElement, String>(
        id = SET_ROUTE_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Draws a route in the stable route JSON form, or clears it with a null route.",
        policy =
            CommandPolicy(
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.MAIN,
            ),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val host = session.hostOrNull() ?: return Outcome.Failure(AppError.NotReady)
        val element = routeElement(params) ?: return Outcome.Failure(AppError.InvalidInput)
        val route =
            when (element) {
                JsonNull -> {
                    null
                }

                is JsonObject -> {
                    decoded(element.toString()) ?: return Outcome.Failure(AppError.InvalidInput)
                }

                is JsonPrimitive -> {
                    val text =
                        element.contentOrNull?.takeIf { it.isNotBlank() }
                            ?: return Outcome.Failure(AppError.InvalidInput)
                    decoded(text) ?: return Outcome.Failure(AppError.InvalidInput)
                }

                else -> {
                    return Outcome.Failure(AppError.InvalidInput)
                }
            }
        when (val outcome = host.setRoute(route)) {
            is Outcome.Failure -> return outcome
            is Outcome.Success -> Unit
        }
        return Outcome.Success(
            CommandResult(
                when (route) {
                    null -> "route=cleared"
                    else -> "route=set points=${route.geometry.size} distance_m=${route.distanceMeters}"
                },
            ),
        )
    }

    /** The `route` member, or JsonNull for an empty parameter object. */
    private fun routeElement(params: JsonElement): JsonElement? =
        when (params) {
            JsonNull -> JsonNull
            is JsonObject -> params["route"] ?: JsonNull
            else -> null
        }

    private fun decoded(text: String): Route? =
        when (val outcome = RouteJson.decode(text)) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> null
        }
}
