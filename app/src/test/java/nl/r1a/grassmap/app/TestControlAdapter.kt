package nl.r1a.grassmap.app

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.CommandInvoker

/**
 * A test-only stand-in for the control channel. It invokes commands through the
 * same [CommandInvoker] path that T-0013 will expose over JSON-RPC.
 */
class TestControlAdapter(
    private val invoker: CommandInvoker,
) {
    suspend fun invoke(
        commandId: String,
        params: JsonElement = JsonNull,
    ): Outcome<JsonElement> = invoker.invoke(CommandId(commandId), params)
}
