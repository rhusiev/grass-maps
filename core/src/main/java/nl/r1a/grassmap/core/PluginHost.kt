package nl.r1a.grassmap.core

import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.contract.Plugin
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.contract.StreamId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.StreamDescriptor
import nl.r1a.grassmap.plugin.api.manifest.GeneratedCommand
import nl.r1a.grassmap.plugin.api.manifest.GeneratedStream
import nl.r1a.grassmap.plugin.api.manifest.PluginFactory
import nl.r1a.grassmap.plugin.api.manifest.PluginManifest
import nl.r1a.grassmap.plugin.api.model.PluginState
import nl.r1a.grassmap.plugin.api.model.PluginStatus
import nl.r1a.grassmap.plugin.api.provider.Provider
import nl.r1a.grassmap.plugin.api.provider.ProviderContract

/**
 * Owns plugin lifecycle, generated registries and contribution validation.
 * A plugin is created and activated on its first activation event, off Main.
 */
class PluginHost(
    plugins: List<PluginFactory>,
    private val streams: List<GeneratedStream>,
    commands: List<GeneratedCommand>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private val factories: Map<PluginId, PluginFactory> = plugins.associateBy { it.manifest.id }
    private val streamOwners: Map<StreamId, PluginId> = streams.associate { it.id to it.pluginId }
    private val streamDeclarations: Map<StreamId, GeneratedStream> = streams.associateBy { it.id }
    private val commandOwners: Map<CommandId, PluginId> = commands.associate { it.id to it.pluginId }
    private val mutex = Mutex()
    private val activationScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val activations = ConcurrentHashMap<PluginId, Deferred<Outcome<Unit>>>()
    private val active = mutableMapOf<PluginId, ActivePlugin>()
    private val failed = mutableSetOf<PluginId>()
    private val _statuses = MutableStateFlow(
        plugins.map { PluginStatus(pluginId = it.manifest.id, state = PluginState.DECLARED) },
    )

    val statuses: StateFlow<List<PluginStatus>> = _statuses.asStateFlow()

    fun manifests(): List<PluginManifest> = factories.values.map { it.manifest }

    fun statusOf(pluginId: PluginId): PluginStatus? =
        statuses.value.firstOrNull { it.pluginId == pluginId }

    suspend fun ensureActive(pluginId: PluginId): Outcome<Unit> {
        if (currentCoroutineContext()[ActivationElement]?.pluginId == pluginId) {
            return Outcome.Failure(AppError.NotReady)
        }
        val activation = mutex.withLock { claimActivation(pluginId) }
        val job = when (activation) {
            is Activation.Ready -> return activation.outcome
            is Activation.Pending -> activation.job
        }
        job.start()
        return try {
            job.await()
        } catch (exception: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                throw exception
            }
            Outcome.Failure(AppError.PluginFailed(pluginId))
        }
    }

    /**
     * Claims one in-flight activation per plugin under the lock. The caller awaits
     * the returned job outside the lock, so activation work never blocks lookups.
     * A finished job removes its own entry, never a newer job for the same plugin.
     */
    private fun claimActivation(pluginId: PluginId): Activation {
        if (active.containsKey(pluginId)) {
            return Activation.Ready(Outcome.Success(Unit))
        }
        if (pluginId in failed) {
            return Activation.Ready(Outcome.Failure(AppError.PluginFailed(pluginId)))
        }
        val factory = factories[pluginId]
            ?: return Activation.Ready(Outcome.Failure(AppError.NotFound))
        val existing = activations[pluginId]
        if (existing != null) {
            return Activation.Pending(existing)
        }
        val job = activationScope.async(
            context = ActivationElement(pluginId),
            start = CoroutineStart.LAZY,
        ) {
            activate(factory)
        }
        activations[pluginId] = job
        job.invokeOnCompletion { activations.remove(pluginId, job) }
        return Activation.Pending(job)
    }

    suspend fun command(id: CommandId): Outcome<Command<*, *>> {
        val owner = commandOwners[id] ?: return Outcome.Failure(AppError.NotFound)
        val activation = ensureActive(owner)
        if (activation is Outcome.Failure) {
            return activation
        }
        return mutex.withLock {
            val command = active[owner]?.commands?.get(id)
            if (command == null) {
                Outcome.Failure(AppError.NotFound)
            } else {
                Outcome.Success(command)
            }
        }
    }

    suspend fun stream(id: StreamId): Outcome<StreamDescriptor<*>> {
        val owner = streamOwners[id] ?: return Outcome.Failure(AppError.NotFound)
        val activation = ensureActive(owner)
        if (activation is Outcome.Failure) {
            return activation
        }
        return mutex.withLock {
            val stream = active[owner]?.streams?.get(id)
            if (stream == null) {
                Outcome.Failure(AppError.NotFound)
            } else {
                Outcome.Success(stream)
            }
        }
    }

    internal suspend fun provider(
        pluginId: PluginId,
        contract: ProviderContract,
    ): Outcome<Provider> {
        val activation = ensureActive(pluginId)
        if (activation is Outcome.Failure) {
            return activation
        }
        return mutex.withLock {
            val provider = active[pluginId]?.providers?.get(contract)
            if (provider == null) {
                Outcome.Failure(AppError.NotFound)
            } else {
                Outcome.Success(provider)
            }
        }
    }

    internal fun recordFailure(pluginId: PluginId) {
        _statuses.update { statuses ->
            statuses.map { status ->
                if (status.pluginId == pluginId) {
                    status.copy(failures = status.failures + 1)
                } else {
                    status
                }
            }
        }
    }

    private suspend fun activate(factory: PluginFactory): Outcome<Unit> {
        val pluginId = factory.manifest.id
        val started = TimeSource.Monotonic.markNow()
        updateState(pluginId, PluginState.ACTIVATING)
        val plugin = try {
            factory.create()
        } catch (exception: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                throw exception
            }
            markFailed(pluginId)
            return Outcome.Failure(AppError.PluginFailed(pluginId))
        } catch (exception: Exception) {
            markFailed(pluginId)
            return Outcome.Failure(AppError.PluginFailed(pluginId))
        }

        val session = ActivationSession(pluginId)
        val activation = try {
            plugin.activate(session)
        } catch (exception: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                throw exception
            }
            markFailed(pluginId, plugin)
            return Outcome.Failure(AppError.PluginFailed(pluginId))
        } catch (exception: Exception) {
            Outcome.Failure(AppError.PluginFailed(pluginId))
        }

        if (activation is Outcome.Failure) {
            markFailed(pluginId, plugin)
            return Outcome.Failure(AppError.PluginFailed(pluginId))
        }

        val activePlugin = when (
            val contributions = validateContributions(factory.manifest, session)
        ) {
            is Outcome.Success -> contributions.value
            is Outcome.Failure -> {
                markFailed(pluginId, plugin)
                return contributions
            }
        }

        mutex.withLock { active[pluginId] = activePlugin }
        val activatedAt = clockMillis()
        updateState(
            pluginId = pluginId,
            state = PluginState.RUNNING,
            activatedAtMillis = activatedAt,
            activationMillis = started.elapsedNow().inWholeMilliseconds,
        )
        return Outcome.Success(Unit)
    }

    private fun validateContributions(
        manifest: PluginManifest,
        session: ActivationSession,
    ): Outcome<ActivePlugin> {
        val commands = session.commands.associateBy { it.id }
        val streams = session.streams.associateBy { it.id }
        val providers = session.providers.associateBy { it.contract }
        if (commands.size != session.commands.size ||
            streams.size != session.streams.size ||
            providers.size != session.providers.size
        ) {
            return Outcome.Failure(AppError.PluginFailed(manifest.id))
        }

        val declaredCommands = manifest.contributes.commands.associateBy { it.id }
        if (commands.keys != declaredCommands.keys) {
            return Outcome.Failure(AppError.PluginFailed(manifest.id))
        }
        for ((id, command) in commands) {
            val declaration = declaredCommands[id] ?: return invalid(manifest)
            if (declaration.undoable != command.policy.undoable ||
                declaration.requiresSwitch != command.policy.requiresSwitch ||
                declaration.allowlistedForControl != command.policy.allowlistedForControl ||
                declaration.dispatcher != command.policy.dispatcher
            ) {
                return invalid(manifest)
            }
        }

        val declaredProviders = manifest.contributes.providers
            .map { ProviderContract(it.contract, it.version) }
            .toSet()
        if (providers.keys != declaredProviders) {
            return Outcome.Failure(AppError.PluginFailed(manifest.id))
        }

        val declaredStreams = manifest.contributes.streams.associateBy { it.id }
        if (streams.keys != declaredStreams.keys) {
            return Outcome.Failure(AppError.PluginFailed(manifest.id))
        }
        for ((id, descriptor) in streams) {
            val declaration = declaredStreams[id] ?: return invalid(manifest)
            val generated = streamDeclarations[id] ?: return invalid(manifest)
            if (generated.pluginId != manifest.id ||
                descriptor.valueType != declaration.valueType ||
                descriptor.valueType != generated.valueType
            ) {
                return invalid(manifest)
            }
        }

        return Outcome.Success(
            ActivePlugin(commands = commands, streams = streams, providers = providers),
        )
    }

    private fun invalid(manifest: PluginManifest): Outcome<ActivePlugin> =
        Outcome.Failure(AppError.PluginFailed(manifest.id))

    private suspend fun markFailed(pluginId: PluginId, plugin: Plugin? = null) {
        if (plugin != null) {
            try {
                plugin.deactivate()
            } catch (exception: Exception) {
                // Deactivation is best effort; the plugin is already failed.
            }
        }
        mutex.withLock { failed += pluginId }
        _statuses.update { statuses ->
            statuses.map { status ->
                if (status.pluginId == pluginId) {
                    status.copy(state = PluginState.FAILED, failures = status.failures + 1)
                } else {
                    status
                }
            }
        }
    }

    private fun updateState(
        pluginId: PluginId,
        state: PluginState,
        activatedAtMillis: Long? = null,
        activationMillis: Long? = null,
    ) {
        _statuses.update { statuses ->
            statuses.map { status ->
                if (status.pluginId == pluginId) {
                    status.copy(
                        state = state,
                        activatedAtMillis = activatedAtMillis ?: status.activatedAtMillis,
                        activationMillis = activationMillis ?: status.activationMillis,
                    )
                } else {
                    status
                }
            }
        }
    }

    private sealed interface Activation {
        data class Ready(val outcome: Outcome<Unit>) : Activation
        data class Pending(val job: Deferred<Outcome<Unit>>) : Activation
    }

    /** Marks the coroutine that activates [pluginId], so it cannot await itself. */
    private class ActivationElement(
        val pluginId: PluginId,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<ActivationElement>
    }

    private class ActivePlugin(
        val commands: Map<CommandId, Command<*, *>>,
        val streams: Map<StreamId, StreamDescriptor<*>>,
        val providers: Map<ProviderContract, Provider>,
    )

    private class ActivationSession(
        override val pluginId: PluginId,
    ) : PluginContext {
        val commands = mutableListOf<Command<*, *>>()
        val streams = mutableListOf<StreamDescriptor<*>>()
        val providers = mutableListOf<Provider>()

        override fun addCommand(command: Command<*, *>) {
            commands += command
        }

        override fun addStream(stream: StreamDescriptor<*>) {
            streams += stream
        }

        override fun addProvider(provider: Provider) {
            providers += provider
        }
    }
}
