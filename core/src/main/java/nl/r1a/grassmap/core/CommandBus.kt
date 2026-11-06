package nl.r1a.grassmap.core

import java.util.concurrent.ConcurrentHashMap
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import nl.r1a.grassmap.plugin.api.command.CancellationKey
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.command.CommandInvocation
import nl.r1a.grassmap.plugin.api.command.CommandInvoked
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.contract.SwitchId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.CommandInvoker
import nl.r1a.grassmap.plugin.api.manifest.GeneratedCommand

/**
 * Dispatches commands from the generated registry. Validates parameters, applies
 * the command policy, runs on the policy dispatcher, records undo, and emits
 * [CommandInvoked] on a best-effort basis for every attempt.
 */
class CommandBus(
    private val host: PluginHost,
    commands: List<GeneratedCommand>,
    private val dispatchers: CommandDispatchers = CommandDispatchers(),
    private val switchEnabled: (SwitchId) -> Boolean = { false },
) : CommandInvoker {
    private val generated: Map<CommandId, GeneratedCommand> = commands.associateBy { it.id }
    private val inFlight = ConcurrentHashMap<CancellationKey, Job>()
    private val history = CommandHistory()
    private val historyMutex = Mutex()
    private val _events = MutableSharedFlow<CommandInvoked>(
        extraBufferCapacity = EVENT_BUFFER_CAPACITY,
    )

    val events: SharedFlow<CommandInvoked> = _events.asSharedFlow()

    override suspend fun invoke(id: CommandId, params: JsonElement): Outcome<JsonElement> =
        executeInvocation(
            invocation = CommandInvocation(commandId = id, params = params),
            recordHistory = true,
        ).outcome

    override suspend fun undo(): Outcome<Unit> {
        val invocation = historyMutex.withLock { history.popUndo() }
            ?: return Outcome.Failure(AppError.NotReady)
        val execution = executeInvocation(invocation, recordHistory = false)
        val outcome = execution.outcome
        if (outcome is Outcome.Failure) {
            historyMutex.withLock { history.pushUndo(invocation) }
            return outcome
        }
        execution.inverse?.let { inverse ->
            historyMutex.withLock { history.pushRedo(inverse) }
        }
        return Outcome.Success(Unit)
    }

    override suspend fun redo(): Outcome<Unit> {
        val invocation = historyMutex.withLock { history.popRedo() }
            ?: return Outcome.Failure(AppError.NotReady)
        val execution = executeInvocation(invocation, recordHistory = false)
        val outcome = execution.outcome
        if (outcome is Outcome.Failure) {
            historyMutex.withLock { history.pushRedo(invocation) }
            return outcome
        }
        execution.inverse?.let { inverse ->
            historyMutex.withLock { history.pushUndo(inverse) }
        }
        return Outcome.Success(Unit)
    }

    private suspend fun executeInvocation(
        invocation: CommandInvocation,
        recordHistory: Boolean,
    ): Execution {
        val started = TimeSource.Monotonic.markNow()
        val generatedCommand = generated[invocation.commandId]
        var outcome: Outcome<JsonElement>? = null
        try {
            val execution = if (generatedCommand == null) {
                Execution(outcome = Outcome.Failure(AppError.NotFound), inverse = null)
            } else {
                dispatch(generatedCommand, invocation, recordHistory)
            }
            outcome = execution.outcome
            return execution
        } finally {
            val error = when (val result = outcome) {
                null -> AppError.Cancelled
                is Outcome.Failure -> result.error
                is Outcome.Success -> null
            }
            _events.tryEmit(
                CommandInvoked(
                    commandId = invocation.commandId,
                    pluginId = generatedCommand?.pluginId,
                    succeeded = outcome is Outcome.Success,
                    error = error,
                    durationMillis = started.elapsedNow().inWholeMilliseconds,
                ),
            )
        }
    }

    private suspend fun dispatch(
        generatedCommand: GeneratedCommand,
        invocation: CommandInvocation,
        recordHistory: Boolean,
    ): Execution {
        val requiredSwitch = generatedCommand.requiresSwitch
        if (requiredSwitch != null && !switchEnabled(requiredSwitch)) {
            return Execution(outcome = Outcome.Failure(AppError.SwitchOff(requiredSwitch)), inverse = null)
        }

        val command = when (val commandOutcome = host.command(invocation.commandId)) {
            is Outcome.Success -> commandOutcome.value
            is Outcome.Failure -> return Execution(outcome = commandOutcome, inverse = null)
        }

        val cancellationKey = command.policy.cancellableBy
        if (cancellationKey == null) {
            return executeCommand(command, generatedCommand.pluginId, invocation, recordHistory)
        }
        return executeCancellable(
            cancellationKey,
            command,
            generatedCommand.pluginId,
            invocation,
            recordHistory,
        )
    }

    private suspend fun executeCancellable(
        key: CancellationKey,
        command: Command<*, *>,
        owner: PluginId,
        invocation: CommandInvocation,
        recordHistory: Boolean,
    ): Execution = coroutineScope {
        val job = async { executeCommand(command, owner, invocation, recordHistory) }
        inFlight.put(key, job)?.cancel()
        try {
            job.await()
        } catch (exception: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                throw exception
            }
            Execution(outcome = Outcome.Failure(AppError.Cancelled), inverse = null)
        } finally {
            inFlight.remove(key, job)
        }
    }

    private suspend fun executeCommand(
        command: Command<*, *>,
        owner: PluginId,
        invocation: CommandInvocation,
        recordHistory: Boolean,
    ): Execution {
        val outcome = try {
            withContext(dispatchers.forPolicy(command.policy)) {
                command.invokeRaw(invocation.params)
            }
        } catch (exception: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                throw exception
            }
            return Execution(outcome = Outcome.Failure(AppError.Cancelled), inverse = null)
        } catch (exception: Exception) {
            host.recordFailure(owner)
            return Execution(outcome = Outcome.Failure(AppError.PluginFailed(owner)), inverse = null)
        }

        return when (outcome) {
            is Outcome.Success -> {
                val inverse = outcome.value.inverse
                if (recordHistory) {
                    val recorded = inverse?.takeIf { command.policy.undoable }
                    historyMutex.withLock { history.record(recorded) }
                }
                Execution(outcome = Outcome.Success(outcome.value.value), inverse = inverse)
            }

            is Outcome.Failure -> Execution(outcome = outcome, inverse = null)
        }
    }

    private data class Execution(
        val outcome: Outcome<JsonElement>,
        val inverse: CommandInvocation?,
    )

    private companion object {
        const val EVENT_BUFFER_CAPACITY = 64
    }
}
