package nl.r1a.grassmap.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.r1a.grassmap.core.ProviderRegistry
import nl.r1a.grassmap.map.TileNetworkPolicy
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.MapHost
import nl.r1a.grassmap.plugin.api.model.StyleId
import nl.r1a.grassmap.plugin.api.provider.MapLayerContract
import nl.r1a.grassmap.plugin.api.service.NetworkGate
import nl.r1a.grassmap.plugin.api.service.NetworkSwitches
import nl.r1a.grassmap.plugins.raster.RasterLayerProvider
import nl.r1a.grassmap.plugins.raster.RasterSource
import nl.r1a.grassmap.plugins.raster.RasterTileClient
import nl.r1a.grassmap.plugins.raster.RasterTransparency

internal val RASTER_PLUGIN: PluginId = PluginId("map.raster")

/**
 * The app's handle on the online tile overlay (T-0512).
 *
 * The provider is resolved through [ProviderRegistry], so a missing or failing
 * plugin is a typed value. The layer is added under the plugin's id, so turning
 * online tiles off removes exactly that layer.
 *
 * [interceptor] is the engine's only path to a tile URL: it asks [allows]
 * whether the URL belongs to the shown source and the privacy gate whether the
 * switch permits the request. The provider is null whenever online tiles are
 * off, so the interceptor blocks every tile URL even if the engine still holds
 * a queued one.
 */
class RasterTilesAccess(
    private val registry: ProviderRegistry,
    gate: NetworkGate,
    val pluginId: PluginId = RASTER_PLUGIN,
) {
    private val client = RasterTileClient(gate = gate)

    @Volatile
    private var provider: RasterLayerProvider? = null

    val policy: TileNetworkPolicy =
        TileNetworkPolicy(
            gate = gate,
            switch = NetworkSwitches.onlineTiles,
            allowsUrl = { url -> allows(url) },
            userAgent = RasterTileClient.USER_AGENT,
        )

    /** Whether [url] belongs to the source currently shown. */
    fun allows(url: String): Boolean = provider?.allows(url) == true

    /** Fetches one tile through the gate, so a custom template is proven first. */
    suspend fun probe(source: RasterSource): Outcome<Unit> =
        when (val image = client.probe(source)) {
            is Outcome.Success -> Outcome.Success(Unit)
            is Outcome.Failure -> image
        }

    /**
     * Shows [layer]'s source over the style when [enabled]. Disabling clears
     * the provider before the layer goes away, so a tile already queued in the
     * engine is blocked at the interceptor.
     *
     * An enabled call adds the layer without removing the previous one first:
     * the layer keeps its id, so the host replaces it in place. A slider drag
     * therefore changes one style property and keeps the engine's tiles, while
     * a placement change moves the same layer to another slot.
     */
    suspend fun apply(
        host: MapHost,
        styleId: StyleId,
        enabled: Boolean,
        layer: RasterLayerState,
    ): Outcome<Unit> {
        if (!enabled) {
            provider?.show(null)
            provider = null
            return host.removeLayers(pluginId)
        }
        return when (val resolved = resolveProvider()) {
            is Outcome.Failure -> {
                resolved
            }

            is Outcome.Success -> {
                val raster = resolved.value
                raster.show(
                    source = layer.source,
                    placement = layer.placement,
                    opacity = RasterTransparency.opacityOf(layer.transparencyPercent),
                )
                provider = raster
                install(host, raster, styleId)
            }
        }
    }

    private suspend fun resolveProvider(): Outcome<RasterLayerProvider> =
        withContext(Dispatchers.IO) {
            when (
                val resolved =
                    registry.invoke(pluginId, MapLayerContract.REFERENCE) { candidate ->
                        when (val raster = candidate as? RasterLayerProvider) {
                            null -> Outcome.Failure(AppError.Unsupported)
                            else -> Outcome.Success(raster)
                        }
                    }
            ) {
                is Outcome.Success -> resolved.value
                is Outcome.Failure -> resolved
            }
        }

    private suspend fun install(
        host: MapHost,
        raster: RasterLayerProvider,
        styleId: StyleId,
    ): Outcome<Unit> {
        for (layer in raster.layers(styleId)) {
            val added = host.addLayer(layer.slot, layer.spec, pluginId)
            if (added is Outcome.Failure) {
                provider = null
                return added
            }
        }
        return Outcome.Success(Unit)
    }
}
