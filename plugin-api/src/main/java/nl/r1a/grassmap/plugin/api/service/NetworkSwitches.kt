package nl.r1a.grassmap.plugin.api.service

import nl.r1a.grassmap.plugin.api.contract.SwitchId

/**
 * The v1 network switches. There is one switch per kind of contact that leaves
 * the device (cross-cutting.md section 3), and all of them start off.
 *
 * The ids live in the API because a plugin that opens a socket names the
 * switch it needs; the state and its request counters stay with the host.
 */
object NetworkSwitches {
    /** An online place search: sends the query, the IP address and usually the map area. */
    val onlineSearch = SwitchId("network.online_search")

    /** Online raster tiles and tile downloads: the tile server sees which area is on screen. */
    val onlineTiles = SwitchId("network.online_tiles")

    /** Region downloads and update checks, which go to our own server only. */
    val regionUpdates = SwitchId("network.region_updates")

    /** Every v1 switch, in the order settings shows them. */
    val all: List<SwitchId> = listOf(onlineSearch, onlineTiles, regionUpdates)
}
