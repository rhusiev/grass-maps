package nl.r1a.grassmaps.plugins.example

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.builtins.serializer
import nl.r1a.grassmaps.plugin.api.command.Command
import nl.r1a.grassmaps.plugin.api.command.CommandPolicy
import nl.r1a.grassmaps.plugin.api.command.CommandResult
import nl.r1a.grassmaps.plugin.api.contract.CommandId
import nl.r1a.grassmaps.plugin.api.error.Outcome

/**
 * Sets the example counter and returns its new value. The inverse invocation
 * carries the previous value so undo restores it.
 */
class SetValueCommand(
    private val value: MutableStateFlow<Int>,
) : Command<Int, Int>(
        id = CommandId("example.set_value"),
        paramsSerializer = Int.serializer(),
        resultSerializer = Int.serializer(),
        doc = "Sets the example counter and returns the new value.",
        policy = CommandPolicy(undoable = true, allowlistedForControl = true),
    ) {
    override suspend fun execute(params: Int): Outcome<CommandResult<Int>> {
        val previous = value.value
        value.value = params
        return Outcome.Success(
            CommandResult(value = params, inverse = invocation(previous)),
        )
    }
}
