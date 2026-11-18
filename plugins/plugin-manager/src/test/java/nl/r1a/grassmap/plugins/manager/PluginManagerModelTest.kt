package nl.r1a.grassmap.plugins.manager

import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.model.PluginInfo
import nl.r1a.grassmap.plugin.api.model.PluginState
import nl.r1a.grassmap.plugin.api.model.PluginStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginManagerModelTest {
    private fun status(
        id: String,
        state: PluginState = PluginState.DECLARED,
        enabled: Boolean = true,
        switchable: Boolean = true,
        info: PluginInfo? = PluginInfo(nameKey = "$id.name", descriptionKey = "$id.desc", version = "1", commandCount = 2),
        failures: Int = 0,
    ) = PluginStatus(PluginId(id), state, failures = failures, enabled = enabled, switchable = switchable, info = info)

    private val resolve: (String) -> String = { key -> key.replace("track.layer", "Track line") }

    @Test
    fun onlyTheListedSwitchablePluginsAppear() {
        val sections =
            pluginSections(
                listOf(
                    status("core.thing", switchable = false),
                    status("map.contours"),
                    status("unknown.one"),
                    status("links", info = null),
                ),
                resolve,
            )

        assertEquals(listOf("map.contours"), sections.flatMap { it.rows }.map { it.status.pluginId.value })
    }

    @Test
    fun sectionsAndRowsFollowTheFixedOrder() {
        val sections =
            pluginSections(listOf(status("info.widgets"), status("links"), status("track.layer"), status("map.contours")), resolve)

        assertEquals(listOf(PluginGroup.MAP, PluginGroup.SHARING, PluginGroup.WIDGETS), sections.map { it.group })
        assertEquals(listOf("map.contours", "track.layer"), sections.first().rows.map { it.status.pluginId.value })
    }

    @Test
    fun searchMatchesNameOrDescriptionIgnoringCase() {
        val all = listOf(status("track.layer"), status("map.contours"))

        assertEquals(1, pluginSections(all, resolve, "TRACK LINE").sumOf { it.rows.size })
        assertEquals(1, pluginSections(all, resolve, "contours.desc").sumOf { it.rows.size })
        assertTrue(pluginSections(all, resolve, "zzz").isEmpty())
    }

    @Test
    fun countsTotalAndOff() {
        val counts = pluginCounts(listOf(status("map.contours", enabled = false), status("links"), status("core.x", switchable = false)))

        assertEquals(PluginCounts(total = 2, off = 1), counts)
    }

    @Test
    fun healthFollowsTheSwitchThenTheState() {
        fun health(
            state: PluginState,
            enabled: Boolean = true,
        ) = PluginRow(status("links", state, enabled), "n", "d").health

        assertEquals(PluginHealth.OFF, health(PluginState.RUNNING, enabled = false))
        assertEquals(PluginHealth.STOPPED, health(PluginState.FAILED))
        assertEquals(PluginHealth.RUNNING, health(PluginState.RUNNING))
        assertEquals(PluginHealth.ON, health(PluginState.DECLARED))
    }

    @Test
    fun networkUseComesFromTheManifestNeeds() {
        val online = PluginInfo("n", "d", "1", 0, network = listOf("example.org"))

        assertTrue(PluginRow(status("links", info = online), "n", "d").usesNetwork)
        assertFalse(PluginRow(status("links"), "n", "d").usesNetwork)
    }

    @Test
    fun theSwitchableSetHasTheTwelveListedIds() {
        assertEquals(12, PluginGroups.switchableIds.size)
        assertEquals(PluginGroup.ROUTING, PluginGroups.groupOf(PluginId("valhalla")))
        assertEquals(null, PluginGroups.groupOf(PluginId("plugin.manager")))
    }
}
