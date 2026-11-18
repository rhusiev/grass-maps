package nl.r1a.grassmap.plugins.manager

import nl.r1a.grassmap.plugin.api.model.PluginState
import nl.r1a.grassmap.plugin.api.model.PluginStatus

/** What a row says about a plugin, from the user's side. */
enum class PluginHealth {
    OFF,
    ON,
    RUNNING,
    STOPPED,
}

/** One plugin with its texts already resolved. */
data class PluginRow(
    val status: PluginStatus,
    val name: String,
    val description: String,
) {
    val usesNetwork: Boolean get() = status.info?.network?.isNotEmpty() == true

    val health: PluginHealth
        get() =
            when {
                !status.enabled -> PluginHealth.OFF
                status.state == PluginState.FAILED -> PluginHealth.STOPPED
                status.state == PluginState.RUNNING -> PluginHealth.RUNNING
                else -> PluginHealth.ON
            }
}

data class PluginSection(
    val group: PluginGroup,
    val rows: List<PluginRow>,
)

data class PluginCounts(
    val total: Int,
    val off: Int,
)

/** The plugins the screen lists: switchable and known to [PluginGroups]. */
private fun listed(statuses: List<PluginStatus>): List<PluginStatus> =
    statuses
        .filter { it.switchable && it.info != null && PluginGroups.groupOf(it.pluginId) != null }
        .sortedBy { PluginGroups.positionOf(it.pluginId) }

fun pluginCounts(statuses: List<PluginStatus>): PluginCounts {
    val rows = listed(statuses)
    return PluginCounts(total = rows.size, off = rows.count { !it.enabled })
}

/**
 * Groups the listed plugins and keeps those whose name or description contains
 * [query], ignoring case. [resolve] turns a manifest text key into text.
 */
fun pluginSections(
    statuses: List<PluginStatus>,
    resolve: (String) -> String,
    query: String = "",
): List<PluginSection> {
    val needle = query.trim().lowercase()
    val rows =
        listed(statuses)
            .mapNotNull { status ->
                val info = status.info ?: return@mapNotNull null
                PluginRow(status, resolve(info.nameKey), resolve(info.descriptionKey))
            }.filter { needle.isEmpty() || needle in it.name.lowercase() || needle in it.description.lowercase() }
    return PluginGroup.entries.mapNotNull { group ->
        rows
            .filter { PluginGroups.groupOf(it.status.pluginId) == group }
            .takeIf { it.isNotEmpty() }
            ?.let { PluginSection(group, it) }
    }
}
