package nl.r1a.grassmap.plugins.route.relations

import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.MapHost
import nl.r1a.grassmap.plugin.api.model.LayerSlot
import nl.r1a.grassmap.plugin.api.model.RouteRelations

/**
 * Draws the mounted region's hiking and cycling route relations (T-0418, T-0419).
 *
 * The controller reads the component through [load], assembles the member
 * geometry, and adds the layers to the map's routes slot under the plugin's
 * owner id. It uses [MapHost] only, so the geometry never comes from
 * `queryRenderedFeatures`: the member ways are read from the region component
 * and assembled here.
 *
 * [refresh] is idempotent: it replaces every layer this owner added, so a
 * camera move or a second bind does not stack duplicates.
 */
class RouteRelationsMapController(
    private val mapHost: MapHost,
    private val load: suspend () -> Outcome<RouteRelations> = { Outcome.Failure(AppError.InvalidInput) },
    private val onLog: (String) -> Unit = {},
    private val owner: PluginId = OWNER,
) {
    suspend fun refresh(): Outcome<Unit> {
        val outcome = load()
        return when (outcome) {
            is Outcome.Failure -> {
                mapHost.removeLayers(owner)
                onLog("route relations unreadable: ${outcome.error}")
                outcome
            }

            is Outcome.Success -> {
                show(outcome.value)
            }
        }
    }

    suspend fun show(component: RouteRelations): Outcome<Unit> {
        mapHost.removeLayers(owner)
        val layers = RouteRelationLayers.build(component)
        if (layers.isEmpty()) {
            return Outcome.Success(Unit)
        }
        for (layer in layers) {
            when (val result = mapHost.addLayer(LayerSlot.Routes, layer.spec, owner)) {
                is Outcome.Failure -> {
                    mapHost.removeLayers(owner)
                    onLog("route-relation layer ${layer.spec.id} rejected: ${result.error}")
                    return result
                }

                is Outcome.Success -> {
                    Unit
                }
            }
        }
        onLog("route relations drawn: ${layers.size} layers")
        return Outcome.Success(Unit)
    }

    suspend fun clear(): Outcome<Unit> = mapHost.removeLayers(owner)

    companion object {
        val OWNER: PluginId = PluginId("route.relations")

        /** The debug goldens own their own layers, so a region refresh cannot clear them. */
        val DEBUG_OWNER: PluginId = PluginId("route.relations.debug")
    }
}
