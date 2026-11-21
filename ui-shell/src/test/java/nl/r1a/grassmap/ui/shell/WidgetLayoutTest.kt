package nl.r1a.grassmaps.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class WidgetLayoutTest {
    private val layout = WidgetLayout(listOf(GridWidget.Altitude, GridWidget.Coordinates, GridWidget.SpeedMax))

    @Test
    fun addAppendsAWidgetThatIsNotShown() {
        assertEquals(
            listOf(GridWidget.Altitude, GridWidget.Coordinates, GridWidget.SpeedMax, GridWidget.SpeedCurrent),
            layout.add(GridWidget.SpeedCurrent).widgets,
        )
    }

    @Test
    fun addKeepsAWidgetThatIsAlreadyShownWhereItIs() {
        assertSame(layout, layout.add(GridWidget.Coordinates))
    }

    @Test
    fun removeDropsTheWidgetAndKeepsTheOrderOfTheRest() {
        assertEquals(listOf(GridWidget.Altitude, GridWidget.SpeedMax), layout.remove(GridWidget.Coordinates).widgets)
    }

    @Test
    fun removeOfAWidgetThatIsNotShownChangesNothing() {
        assertEquals(layout, layout.remove(GridWidget.GpsAccuracy))
    }

    @Test
    fun moveShiftsAWidgetTowardsTheStartOrTheEnd() {
        assertEquals(
            listOf(GridWidget.Coordinates, GridWidget.Altitude, GridWidget.SpeedMax),
            layout.move(GridWidget.Coordinates, -1).widgets,
        )
        assertEquals(
            listOf(GridWidget.Altitude, GridWidget.SpeedMax, GridWidget.Coordinates),
            layout.move(GridWidget.Coordinates, 1).widgets,
        )
    }

    @Test
    fun moveOutOfTheRowOrOfAWidgetThatIsNotShownChangesNothing() {
        assertEquals(layout, layout.move(GridWidget.Altitude, -1))
        assertEquals(layout, layout.move(GridWidget.SpeedMax, 1))
        assertEquals(layout, layout.move(GridWidget.GpsAccuracy, 1))
    }

    @Test
    fun hiddenListsEveryWidgetThatIsNotShown() {
        assertEquals(
            GridWidget.entries - layout.widgets.toSet(),
            layout.hidden,
        )
        assertEquals(emptyList<GridWidget>(), WidgetLayout(GridWidget.entries).hidden)
    }

    @Test
    fun anEditLeavesTheOriginalLayoutUntouched() {
        layout.add(GridWidget.SpeedCurrent).remove(GridWidget.Altitude)
        assertEquals(3, layout.widgets.size)
    }

    @Test
    fun storageKeysAreUniqueAndRoundTrip() {
        assertEquals(
            GridWidget.entries.size,
            GridWidget.entries
                .map { it.storageKey }
                .toSet()
                .size,
        )
        GridWidget.entries.forEach { assertEquals(it, GridWidget.fromStorageKey(it.storageKey)) }
        assertEquals(null, GridWidget.fromStorageKey("battery"))
    }
}
