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
import nl.r1a.grassmap.plugin.api.model.RouteRelationsJson
import nl.r1a.grassmap.plugins.route.relations.RouteRelationsMapController

/** The debug-only command the T-0418 goldens use to draw a fixed route-relation component. */
internal val SET_ROUTE_RELATIONS_COMMAND: CommandId = CommandId("core.map.set_route_relations")

/**
 * Draws the committed fixture's `relations.json` through the same controller
 * the app uses, or clears the layers with `{"relations": null}`.
 *
 * It exists only in the debug source set: the fixture profile has no mounted
 * region package, so the golden harness hands the component bytes to the
 * plugin directly. The parser, assembler, lane assignment, symbol mapping and
 * MapHost path are the production ones.
 */
internal class SetRouteRelationsCommand(
    private val session: MapSessionHolder,
) : Command<JsonElement, String>(
        id = SET_ROUTE_RELATIONS_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Draws a route-relation component, or clears the layers with a null component.",
        policy =
            CommandPolicy(
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.MAIN,
            ),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val host = session.hostOrNull() ?: return Outcome.Failure(AppError.NotReady)
        val element = componentElement(params) ?: return Outcome.Failure(AppError.InvalidInput)
        val controller =
            RouteRelationsMapController(
                mapHost = host,
                owner = RouteRelationsMapController.DEBUG_OWNER,
            )
        if (element is JsonNull) {
            return when (val cleared = controller.clear()) {
                is Outcome.Failure -> cleared
                is Outcome.Success -> Outcome.Success(CommandResult("relations=cleared"))
            }
        }
        val text =
            when (element) {
                is JsonObject -> element.toString()
                is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() } ?: return Outcome.Failure(AppError.InvalidInput)
                else -> return Outcome.Failure(AppError.InvalidInput)
            }
        val component =
            when (val decoded = RouteRelationsJson.decode(text)) {
                is Outcome.Failure -> return decoded
                is Outcome.Success -> decoded.value
            }
        return when (val drawn = controller.show(component)) {
            is Outcome.Failure -> {
                drawn
            }

            is Outcome.Success -> {
                Outcome.Success(
                    CommandResult("relations=set count=${component.relations.size} ways=${component.ways.size}"),
                )
            }
        }
    }

    private fun componentElement(params: JsonElement): JsonElement? =
        when (params) {
            JsonNull -> JsonNull
            is JsonObject -> params["relations"] ?: JsonNull
            else -> null
        }
}
