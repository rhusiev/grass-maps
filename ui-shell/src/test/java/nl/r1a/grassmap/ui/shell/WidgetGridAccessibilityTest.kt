package nl.r1a.grassmap.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The widget grid's spoken contract: a cell is one line with its title and
 * values, and only the selected point announces its changes. Altitude, GPS
 * accuracy, coordinates and speeds move with every fix, so a live region on
 * them would chatter.
 */
class WidgetGridAccessibilityTest {
    @Test
    fun aCellIsSpokenAsItsTitleAndValues() {
        val cell =
            WidgetCell(
                widget = GridWidget.Altitude,
                title = "Altitude",
                lines = listOf(WidgetLine(value = "296 m", label = "alt")),
            )

        assertEquals("Altitude: 296 m", widgetCellDescription(cell))
    }

    @Test
    fun aCellWithoutAValueKeepsItsTitle() {
        val cell = WidgetCell(widget = GridWidget.SelectedPoint, title = "Selected point", lines = emptyList())

        assertEquals("Selected point", widgetCellDescription(cell))
    }

    @Test
    fun twoLinesAreBothSpoken() {
        val cell =
            WidgetCell(
                widget = GridWidget.SelectedPoint,
                title = "Selected point",
                lines = listOf(WidgetLine("320 m"), WidgetLine("57°")),
            )

        assertEquals("Selected point: 320 m, 57°", widgetCellDescription(cell))
    }

    @Test
    fun onlyTheSelectedPointAnnouncesItsChanges() {
        val announcing = GridWidget.entries.filter { widget -> widgetAnnouncesValueChanges(widget) }

        assertEquals(listOf(GridWidget.SelectedPoint), announcing)
        for (widget in GridWidget.entries - GridWidget.SelectedPoint) {
            assertFalse("$widget must not announce every fix", widgetAnnouncesValueChanges(widget))
        }
        assertTrue(GridWidget.SelectedPoint.storageKey.isNotBlank())
    }
}
