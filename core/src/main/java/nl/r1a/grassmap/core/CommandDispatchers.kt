package nl.r1a.grassmap.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.command.CommandPolicy

/**
 * Maps the command policy to a coroutine dispatcher. Tests replace every entry.
 */
class CommandDispatchers(
    val main: CoroutineDispatcher = Dispatchers.Main,
    val default: CoroutineDispatcher = Dispatchers.Default,
    val io: CoroutineDispatcher = Dispatchers.IO,
) {
    fun forPolicy(policy: CommandPolicy): CoroutineDispatcher = when (policy.dispatcher) {
        CommandDispatcher.MAIN -> main
        CommandDispatcher.DEFAULT -> default
        CommandDispatcher.IO -> io
    }
}
