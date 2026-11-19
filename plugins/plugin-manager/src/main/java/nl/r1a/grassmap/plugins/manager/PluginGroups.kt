package nl.r1a.grassmap.plugins.manager

import nl.r1a.grassmap.plugin.api.contract.PluginId

/** The sections of the plugin list, in the order the screen draws them. */
enum class PluginGroup {
    MAP,
    ROUTING,
    PLACES,
    WIDGETS,
}

/**
 * The plugins a user may turn off, grouped and ordered as the screen lists
 * them. Every other plugin is essential: the host refuses to switch it, and the
 * screen does not draw it. `app` hands [switchableIds] to the host, so this
 * list is the one place that decides what a user can turn off.
 *
 * A plugin belongs here only when every app path that uses it goes through the
 * host, so switching it off really stops it. `links`, `coordinates`,
 * `way.alerts`, `track.layer`, `share` and `guidance.ui` are called directly by
 * `app` today; they stay essential until those call sites read the switch.
 */
object PluginGroups {
    private val ORDERED: List<Pair<PluginGroup, List<String>>> =
        listOf(
            PluginGroup.MAP to listOf("map.contours"),
            PluginGroup.ROUTING to listOf("valhalla"),
            PluginGroup.PLACES to listOf("osm.places", "favourites.ui", "online.search"),
            PluginGroup.WIDGETS to listOf("info.widgets"),
        )

    private val ORDER: Map<PluginId, Int> =
        ORDERED
            .flatMap { it.second }
            .withIndex()
            .associate { PluginId(it.value) to it.index }

    private val GROUP: Map<PluginId, PluginGroup> =
        ORDERED
            .flatMap { (group, ids) -> ids.map { PluginId(it) to group } }
            .toMap()

    val switchableIds: Set<PluginId> = GROUP.keys

    fun groupOf(id: PluginId): PluginGroup? = GROUP[id]

    fun positionOf(id: PluginId): Int = ORDER[id] ?: Int.MAX_VALUE
}
