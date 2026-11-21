package nl.r1a.grassmaps.plugin.api.host

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import nl.r1a.grassmaps.plugin.api.command.CommandInvocation
import nl.r1a.grassmaps.plugin.api.contract.CommandId
import nl.r1a.grassmaps.plugin.api.error.Outcome

/**
 * The only way UI and other plugins mutate state.
 */
interface CommandInvoker {
    suspend fun invoke(
        id: CommandId,
        params: JsonElement = JsonNull,
    ): Outcome<JsonElement>

    suspend fun invoke(invocation: CommandInvocation): Outcome<JsonElement> = invoke(invocation.commandId, invocation.params)

    suspend fun undo(): Outcome<Unit>

    suspend fun redo(): Outcome<Unit>
}
