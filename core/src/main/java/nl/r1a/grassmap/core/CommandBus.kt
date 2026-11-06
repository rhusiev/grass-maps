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
 * [CommandInvoked] for every attempt.
 */
class CommandBus(
    private val host: PluginHost,
    commands: List<GeneratedCommand>,
    private val dispatchers: CommandDispatchers = CommandDispatchers(),
    private val switchEnabled: (SwitchId) -> Boolean = { false },
) : CommandInvoker {
    private val owners: Map<CommandId, PluginId> = commands.associate { it.id to it.pluginId }
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

    override suspend fun undo(): Outcome<Unit> = historyMutex.withLock {
        val invocation = history.popUndo() ?: return@withLock Outcome.Failure(AppError.NotReady)
        val execution = executeInvocation(invocation, recordHistory = false)
        val outcome = execution.outcome
        if (outcome is Outcome.Failure) {
            history.pushUndo(invocation)
            return@withLock outcome
        }
        execution.inverse?.let { history.pushRedo(it) }
        Outcome.Success(Unit)
    }

    override suspend fun redo(): Outcome<Unit> = historyMutex.withLock {
        val invocation = history.popRedo() ?: return@withLock Outcome.Failure(AppError.NotReady)
        val execution = executeInvocation(invocation, recordHistory = false)
        val outcome = execution.outcome
        if (outcome is Outcome.Failure) {
            history.pushRedo(invocation)
            return@withLock outcome
        }
        execution.inverse?.let { history.pushUndo(it) }
        Outcome.Success(Unit)
    }

    private suspend fun executeInvocation(
        invocation: CommandInvocation,
        recordHistory: Boolean,
    ): Execution {
        val started = TimeSource.Monotonic.markNow()
        val owner = owners[invocation.commandId]
        val execution = if (owner == null) {
            Execution(outcome = Outcome.Failure(AppError.NotFound), inverse = null)
        } else {
            dispatch(owner, invocation, recordHistory)
        }
        _events.tryEmit(
            CommandInvoked(
                commandId = invocation.commandId,
                pluginId = owner,
                succeeded = execution.outcome is Outcome.Success,
                error = (execution.outcome as? Outcome.Failure)?.error,
                durationMillis = started.elapsedNow().inWholeMilliseconds,
            ),
        )
        return execution
    }

    private suspend fun dispatch(
        owner: PluginId,
        invocation: CommandInvocation,
        recordHistory: Boolean,
    ): Execution {
        val command = when (val commandOutcome = host.command(invocation.commandId)) {
            is Outcome.Success -> commandOutcome.value
            is Outcome.Failure -> return Execution(outcome = commandOutcome, inverse = null)
        }

        val policy = command.policy
        val requiredSwitch = policy.requiresSwitch
        if (requiredSwitch != null && !switchEnabled(requiredSwitch)) {
            return Execution(outcome = Outcome.Failure(AppError.SwitchOff(requiredSwitch)), inverse = null)
        }

        val cancellationKey = policy.cancellableBy
        if (cancellationKey == null) {
            return executeCommand(command, owner, invocation, recordHistory)
        }
        return executeCancellable(cancellationKey, command, owner, invocation, recordHistory)
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
            throw exception
        } catch (exception: Exception) {
            host.recordFailure(owner)
            return Execution(outcome = Outcome.Failure(AppError.PluginFailed(owner)), inverse = null)
        }

        return when (outcome) {
            is Outcome.Success -> {
                val inverse = outcome.value.inverse
                if (recordHistory && command.policy.undoable && inverse != null) {
                    historyMutex.withLock { history.record(inverse) }
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
