package nl.r1a.grassmap.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.ProviderState
import nl.r1a.grassmap.plugin.api.model.ProviderStatus
import nl.r1a.grassmap.plugin.api.provider.Provider
import nl.r1a.grassmap.plugin.api.provider.ProviderContract
import java.util.concurrent.ConcurrentHashMap

/**
 * Calls providers behind a boundary that turns an unexpected exception into a
 * [AppError.PluginFailed] value. After [failureLimit] failures in one session the
 * provider is disabled without stopping the app or the host.
 */
class ProviderRegistry(
    private val host: PluginHost,
    private val failureLimit: Int = DEFAULT_FAILURE_LIMIT,
) {
    private val failures = ConcurrentHashMap<ProviderKey, Int>()
    private val disabled = ConcurrentHashMap.newKeySet<ProviderKey>()
    private val _statuses = MutableStateFlow<List<ProviderStatus>>(emptyList())

    val statuses: StateFlow<List<ProviderStatus>> = _statuses.asStateFlow()

    fun statusOf(
        pluginId: PluginId,
        contract: ProviderContract,
    ): ProviderStatus? =
        statuses.value.firstOrNull {
            it.pluginId == pluginId && it.contract == contract
        }

    suspend fun <T> invoke(
        pluginId: PluginId,
        contract: ProviderContract,
        block: suspend (Provider) -> T,
    ): Outcome<T> {
        val key = ProviderKey(pluginId, contract)
        if (key in disabled) {
            return Outcome.Failure(AppError.NotReady)
        }

        val provider =
            when (val providerOutcome = host.provider(pluginId, contract)) {
                is Outcome.Success -> providerOutcome.value
                is Outcome.Failure -> return providerOutcome
            }

        return try {
            val value = block(provider)
            if (failures.putIfAbsent(key, 0) == null) {
                publish(key, ProviderState.ACTIVE, 0)
            }
            Outcome.Success(value)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            recordFailure(key, pluginId)
            Outcome.Failure(AppError.PluginFailed(pluginId))
        } catch (error: LinkageError) {
            // Native linkage failures take the same disable path as exceptions.
            recordFailure(key, pluginId)
            Outcome.Failure(AppError.PluginFailed(pluginId))
        }
    }

    private fun recordFailure(
        key: ProviderKey,
        pluginId: PluginId,
    ) {
        val count = failures.merge(key, 1, Int::plus) ?: 1
        if (count >= failureLimit) {
            disabled += key
            publish(key, ProviderState.DISABLED, failureLimit)
        } else {
            publish(key, ProviderState.ACTIVE, count)
        }
    }

    private fun publish(
        key: ProviderKey,
        state: ProviderState,
        failures: Int,
    ) {
        val status =
            ProviderStatus(
                pluginId = key.pluginId,
                contract = key.contract,
                state = state,
                failures = failures,
            )
        _statuses.update { statuses ->
            statuses.filterNot {
                it.pluginId == key.pluginId && it.contract == key.contract
            } + status
        }
    }

    private data class ProviderKey(
        val pluginId: PluginId,
        val contract: ProviderContract,
    )

    companion object {
        const val DEFAULT_FAILURE_LIMIT = 3
    }
}
