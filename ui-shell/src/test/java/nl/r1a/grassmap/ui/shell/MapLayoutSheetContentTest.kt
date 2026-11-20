package nl.r1a.grassmap.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLayoutSheetContentTest {
    private val buttonKeys = listOf("zoom_in", "zoom_out", "compass", "follow", "planner", "saved", "tracks")
    private val widgetKeys = listOf("altitude", "gps", "coordinates", "speed", "average", "max", "selected")

    @Test
    fun theButtonsTabKeepsTheCatalogOrderAndMarksThePlacedOnes() {
        val content =
            mapLayoutSheetContent(
                buttonKeys = buttonKeys,
                buttonTitle = { key -> key.uppercase() },
                placedButtons = setOf("zoom_in", "saved"),
                widgetKeys = widgetKeys,
                widgetTitle = { key -> key },
                placedWidgets = emptySet(),
            )

        assertEquals(buttonKeys, content.buttons.map { it.key })
        assertEquals(buttonKeys.map { it.uppercase() }, content.buttons.map { it.title })
        assertEquals(
            setOf("zoom_in", "saved"),
            content.buttons
                .filter { it.shown }
                .map { it.key }
                .toSet(),
        )
    }

    @Test
    fun theWidgetsTabSplitsHiddenAndShownWithoutLosingAnItem() {
        val content =
            mapLayoutSheetContent(
                buttonKeys = buttonKeys,
                buttonTitle = { key -> key },
                placedButtons = emptySet(),
                widgetKeys = widgetKeys,
                widgetTitle = { key -> key },
                placedWidgets = setOf("altitude", "gps", "selected"),
            )

        assertEquals(listOf("coordinates", "speed", "average", "max"), content.hiddenWidgets.map { it.key })
        assertEquals(listOf("altitude", "gps", "selected"), content.shownWidgets.map { it.key })
        assertEquals(widgetKeys.toSet(), (content.hiddenWidgets + content.shownWidgets).map { it.key }.toSet())
        assertTrue(content.hiddenWidgets.none { it.shown })
        assertTrue(content.shownWidgets.all { it.shown })
    }

    @Test
    fun anItemTheCatalogDoesNotNameIsNeverListed() {
        val content =
            mapLayoutSheetContent(
                buttonKeys = listOf("zoom_in"),
                buttonTitle = { key -> key },
                placedButtons = setOf("battery", "sunset"),
                widgetKeys = listOf("altitude"),
                widgetTitle = { key -> key },
                placedWidgets = setOf("bearing", "time"),
            )

        assertEquals(listOf("zoom_in"), content.buttons.map { it.key })
        assertFalse(content.buttons.single().shown)
        assertEquals(listOf("altitude"), content.hiddenWidgets.map { it.key })
        assertTrue(content.shownWidgets.isEmpty())
    }
}
