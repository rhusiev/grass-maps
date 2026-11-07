package nl.r1a.grassmap.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.r1a.grassmap.core.ProviderRegistry
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.MapHost
import nl.r1a.grassmap.plugin.api.model.SlottedLayer
import nl.r1a.grassmap.plugin.api.provider.AttributePage
import nl.r1a.grassmap.plugin.api.provider.AttributeRequest
import nl.r1a.grassmap.plugin.api.provider.CostModifier
import nl.r1a.grassmap.plugin.api.provider.CostModifierRequest
import nl.r1a.grassmap.plugin.api.provider.DataSource
import nl.r1a.grassmap.plugin.api.provider.DataSourceContract
import nl.r1a.grassmap.plugin.api.provider.DataSourceDescription

internal val SYNTHETIC_DATA_PLUGIN: PluginId = PluginId("data.synthetic")

/**
 * The app's handle on the synthetic DataSource. Routing will read cost
 * modifiers through it; today it proves that the provider resolves and that its
 * map layer reaches the host. Every call returns a typed [Outcome], so a missing
 * provider is a value, not a crash.
 */
class DataSourceAccess internal constructor(
    private val registry: ProviderRegistry,
    val pluginId: PluginId = SYNTHETIC_DATA_PLUGIN,
) {
    suspend fun describe(): Outcome<DataSourceDescription> = withSource { Outcome.Success(it.describe()) }

    suspend fun attributes(
        request: AttributeRequest,
        cancellation: CancellationHandle,
    ): Outcome<AttributePage> = withSource { it.attributes(request, cancellation) }

    suspend fun costModifiers(
        request: CostModifierRequest,
        cancellation: CancellationHandle,
    ): Outcome<List<CostModifier>> = withSource { it.costModifiers(request, cancellation) }

    suspend fun mapLayer(): Outcome<SlottedLayer?> = withSource { Outcome.Success(it.mapLayer()) }

    /**
     * Runs every contract call behind [ProviderRegistry.invoke], not just the
     * provider lookup. A plugin that throws from [DataSource.describe] or
     * [DataSource.mapLayer] becomes a typed failure and counts toward its
     * disable limit instead of escaping into the caller's coroutine.
     */
    private suspend fun <T> withSource(block: suspend (DataSource) -> Outcome<T>): Outcome<T> {
        val guarded =
            registry.invoke(pluginId, DataSourceContract.REFERENCE) { provider ->
                when (val source = provider as? DataSource) {
                    null -> Outcome.Failure(AppError.Unsupported)
                    else -> block(source)
                }
            }
        return when (guarded) {
            is Outcome.Success -> guarded.value
            is Outcome.Failure -> guarded
        }
    }
}

/**
 * Resolves the DataSource map layer off Main and adds it under the provider's
 * own plugin id. A missing provider or a layer the host rejects is returned as a
 * failure for the caller to log; the map keeps running either way.
 */
internal suspend fun installDataSourceLayer(
    dataSources: DataSourceAccess,
    host: MapHost,
): Outcome<Unit> {
    val layer = withContext(Dispatchers.IO) { dataSources.mapLayer() }
    return when (layer) {
        is Outcome.Failure -> {
            layer
        }

        is Outcome.Success -> {
            val slotted = layer.value
            if (slotted == null) {
                Outcome.Success(Unit)
            } else {
                host.addLayer(slotted.slot, slotted.spec, dataSources.pluginId)
            }
        }
    }
}
