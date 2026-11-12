package nl.r1a.grassmap.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.r1a.grassmap.core.ProviderRegistry
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.MapHost
import nl.r1a.grassmap.plugin.api.model.SlottedLayer
import nl.r1a.grassmap.plugin.api.model.StyleId
import nl.r1a.grassmap.plugin.api.provider.MapLayerContract
import nl.r1a.grassmap.plugin.api.provider.MapLayerProvider

internal val CONTOURS_PLUGIN: PluginId = PluginId("map.contours")

/**
 * The app's handle on the contour overlay (T-0417).
 *
 * The provider is resolved through [ProviderRegistry] so a missing or failing
 * plugin is a typed value, not a crash. The layers are added under the plugin's
 * own id, so turning the overlay off removes exactly those layers and never
 * touches the base style, the route or the markers.
 */
class ContoursAccess internal constructor(
    private val registry: ProviderRegistry,
    val pluginId: PluginId = CONTOURS_PLUGIN,
) {
    suspend fun layers(styleId: StyleId): Outcome<List<SlottedLayer>> =
        withContext(Dispatchers.IO) {
            val guarded =
                registry.invoke(pluginId, MapLayerContract.REFERENCE) { provider ->
                    when (val layers = provider as? MapLayerProvider) {
                        null -> Outcome.Failure(AppError.Unsupported)
                        else -> Outcome.Success(layers.layers(styleId))
                    }
                }
            when (guarded) {
                is Outcome.Success -> guarded.value
                is Outcome.Failure -> guarded
            }
        }

    /**
     * Removes the previous overlay and adds [styleId]'s contour layers when
     * [enabled]. A style switch calls this after the base style diff so the
     * new variant's palette and zoom table replace the old ones.
     */
    suspend fun apply(
        host: MapHost,
        styleId: StyleId,
        enabled: Boolean,
    ): Outcome<Unit> {
        val removed = host.removeLayers(pluginId)
        if (removed is Outcome.Failure) {
            return removed
        }
        if (!enabled) {
            return Outcome.Success(Unit)
        }
        return when (val resolved = layers(styleId)) {
            is Outcome.Failure -> {
                resolved
            }

            is Outcome.Success -> {
                for (layer in resolved.value) {
                    val added = host.addLayer(layer.slot, layer.spec, pluginId)
                    if (added is Outcome.Failure) {
                        return added
                    }
                }
                Outcome.Success(Unit)
            }
        }
    }
}
