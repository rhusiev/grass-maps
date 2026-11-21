package nl.r1a.grassmaps.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.r1a.grassmaps.core.ProviderRegistry
import nl.r1a.grassmaps.plugin.api.contract.CancellationHandle
import nl.r1a.grassmaps.plugin.api.contract.PluginId
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.host.MapHost
import nl.r1a.grassmaps.plugin.api.model.SlottedLayer
import nl.r1a.grassmaps.plugin.api.provider.AttributePage
import nl.r1a.grassmaps.plugin.api.provider.AttributeRequest
import nl.r1a.grassmaps.plugin.api.provider.CostModifier
import nl.r1a.grassmaps.plugin.api.provider.CostModifierRequest
import nl.r1a.grassmaps.plugin.api.provider.DataSource
import nl.r1a.grassmaps.plugin.api.provider.DataSourceContract
import nl.r1a.grassmaps.plugin.api.provider.DataSourceDescription

/**
 * The app's handle on the synthetic DataSource. Routing will read cost
 * modifiers through it; today it proves that the provider resolves and that its
 * map layer reaches the host. Every call returns a typed [Outcome], so a missing
 * provider is a value, not a crash. A release build passes no plugin id: the
 * development plugin that provides the synthetic source is not packaged there.
 */
class DataSourceAccess internal constructor(
    private val registry: ProviderRegistry,
    val pluginId: PluginId?,
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
        val id = pluginId ?: return Outcome.Failure(AppError.NotFound)
        val guarded =
            registry.invoke(id, DataSourceContract.REFERENCE) { provider ->
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
 * own plugin id when [enabled], or removes it when disabled. The synthetic
 * seam draws from the same contour archive as the overlay, so the "Contours"
 * switch owns it too. A release build has no plugin id, so the off state is
 * already true and disabling is a no-op. A missing provider or a layer the
 * host rejects is returned as a failure for the caller to log; the map keeps
 * running either way.
 */
internal suspend fun installDataSourceLayer(
    dataSources: DataSourceAccess,
    host: MapHost,
    enabled: Boolean,
): Outcome<Unit> {
    if (!enabled) {
        val pluginId = dataSources.pluginId ?: return Outcome.Success(Unit)
        return host.removeLayers(pluginId)
    }
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
                val pluginId = dataSources.pluginId ?: return Outcome.Failure(AppError.NotFound)
                host.addLayer(slotted.slot, slotted.spec, pluginId)
            }
        }
    }
}
