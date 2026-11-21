package nl.r1a.grassmaps.app

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import nl.r1a.grassmaps.plugin.api.command.Command
import nl.r1a.grassmaps.plugin.api.command.CommandDispatcher
import nl.r1a.grassmaps.plugin.api.command.CommandPolicy
import nl.r1a.grassmaps.plugin.api.command.CommandResult
import nl.r1a.grassmaps.plugin.api.contract.CommandId
import nl.r1a.grassmaps.plugin.api.error.Outcome

/** The debug-only probe of what the route layer last drew. */
internal val ROUTE_LAYER_POINTS_COMMAND: CommandId = CommandId("core.map.route_layer_points")

/**
 * Reports the point count of the route the map last accepted from the route
 * layer, so the goldens script can wait until the map holds the fixture's
 * route before it captures. A clear reads 0.
 */
internal class RouteLayerPointsCommand(
    private val drawnPointCount: () -> Int,
    private val acceptedDrawCount: () -> Int,
) : Command<JsonElement, String>(
        id = ROUTE_LAYER_POINTS_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc =
            "Returns points=N draws=M: the point count of the route the route layer last drew " +
                "(0 when cleared) and the number of accepted draws.",
        policy =
            CommandPolicy(
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.IO,
            ),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> =
        Outcome.Success(CommandResult("points=${drawnPointCount()} draws=${acceptedDrawCount()}"))
}
