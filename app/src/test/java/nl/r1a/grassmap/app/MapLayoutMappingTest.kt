package nl.r1a.grassmaps.app

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import nl.r1a.grassmaps.core.services.MapButtonLayout
import nl.r1a.grassmaps.core.services.MapControlCommands
import nl.r1a.grassmaps.plugin.api.contract.CommandId
import nl.r1a.grassmaps.plugin.api.service.Strings
import nl.r1a.grassmaps.ui.shell.GridWidget
import nl.r1a.grassmaps.ui.shell.WidgetLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class MapLayoutMappingTest {
    @Test
    fun theButtonsTabListsExactlyTheAppButtons() {
        val content = layoutSheetContent(MapButtonLayout.DEFAULT, WidgetLayout.DEFAULT, CatalogStrings)

        assertEquals(
            MapControlCommands.CONFIGURABLE,
            content.buttons.map { item -> CommandId(item.key) },
        )
        assertEquals(7, content.buttons.size)
        assertEquals(
            listOf(
                MapControlCommands.ZOOM_IN,
                MapControlCommands.ZOOM_OUT,
                MapControlCommands.NORTH_UP,
                MapControlCommands.FOLLOW,
                MapControlCommands.ROUTE_PLANNER,
            ),
            content.buttons.filter { it.shown }.map { item -> CommandId(item.key) },
        )
        assertEquals(
            listOf(MapControlCommands.SAVED, MapControlCommands.TRACKS),
            content.buttons.filterNot { it.shown }.map { item -> CommandId(item.key) },
        )
    }

    @Test
    fun theWidgetsTabListsExactlyTheSevenWidgetsTheAppHas() {
        val content = layoutSheetContent(MapButtonLayout.DEFAULT, WidgetLayout.DEFAULT, CatalogStrings)

        assertEquals(
            GridWidget.entries.map { it.storageKey }.toSet(),
            (content.hiddenWidgets + content.shownWidgets).map { it.key }.toSet(),
        )
        assertEquals(
            WidgetLayout.DEFAULT.widgets.map { it.storageKey },
            content.shownWidgets.map { it.key },
        )
        assertEquals(
            WidgetLayout.DEFAULT.hidden.map { it.storageKey },
            content.hiddenWidgets.map { it.key },
        )
        val allKeys = content.buttons.map { it.key } + content.hiddenWidgets.map { it.key } + content.shownWidgets.map { it.key }
        for (absent in listOf("time", "battery", "sunset", "bearing")) {
            assertFalse("$absent must not be offered", absent in allKeys)
        }
    }

    @Test
    fun aRemovedButtonIsListedAsFreeAndTheRestKeepTheirSlots() {
        val layout = MapButtonLayout.DEFAULT.remove(MapControlCommands.NORTH_UP)

        val content = layoutSheetContent(layout, WidgetLayout.DEFAULT, CatalogStrings)

        assertFalse(content.buttons.single { it.key == MapControlCommands.NORTH_UP.value }.shown)
        assertTrue(content.buttons.single { it.key == MapControlCommands.ZOOM_IN.value }.shown)
        assertEquals(0, layout.slots.indexOf(MapControlCommands.ZOOM_IN))
        assertEquals(3, layout.slots.indexOf(MapControlCommands.FOLLOW))
    }

    @Test
    fun aSavedButtonOpensTheSavedListsAndTheNextOneTheTracks() {
        var saved = 0
        var tracks = 0
        val commands = mutableListOf<Pair<CommandId, JsonObject>>()

        dispatchLayoutButton(
            id = MapControlCommands.SAVED,
            parameters = JsonObject(emptyMap()),
            invokeCommand = { id, params -> commands += id to params },
            openSaved = { saved += 1 },
            openTracks = { tracks += 1 },
        )
        dispatchLayoutButton(
            id = MapControlCommands.TRACKS,
            parameters = JsonObject(emptyMap()),
            invokeCommand = { id, params -> commands += id to params },
            openSaved = { saved += 1 },
            openTracks = { tracks += 1 },
        )

        assertEquals(1, saved)
        assertEquals(1, tracks)
        assertTrue(commands.isEmpty())
    }

    @Test
    fun aQuickAccessCommandKeepsItsStoredParameters() {
        val parameters = JsonObject(mapOf("point" to JsonPrimitive("home")))
        var invoked: Pair<CommandId, JsonObject>? = null

        dispatchLayoutButton(
            id = MapControlCommands.ROUTE_PLANNER,
            parameters = parameters,
            invokeCommand = { id, params -> invoked = id to params },
            openSaved = {},
            openTracks = {},
        )

        assertEquals(MapControlCommands.ROUTE_PLANNER, invoked?.first)
        assertEquals(parameters, invoked?.second)
    }

    private object CatalogStrings : Strings {
        private val values =
            mapOf(
                "map_layout_button_zoom_in" to "Zoom in",
                "map_layout_button_zoom_out" to "Zoom out",
                "map_layout_button_compass" to "Compass",
                "map_layout_button_follow" to "Follow",
                "map_layout_button_route_planner" to "Route planner",
                "map_layout_button_saved" to "Saved",
                "map_layout_button_tracks" to "Tracks",
                "widget_grid_altitude" to "Altitude",
                "widget_grid_gps_accuracy" to "GPS accuracy",
                "widget_grid_coordinates" to "Coordinates",
                "widget_grid_speed_current" to "Speed",
                "widget_grid_speed_average" to "Average speed",
                "widget_grid_speed_max" to "Max speed",
                "widget_grid_selected_point" to "Selected point",
            )

        override fun resolve(
            key: String,
            arguments: List<String>,
        ): String {
            val template = values[key] ?: return key
            return if (arguments.isEmpty()) template else String.format(Locale.US, template, *arguments.toTypedArray())
        }

        override fun contains(key: String): Boolean = key in values
    }
}
