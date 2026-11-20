package nl.r1a.grassmap.core.services

import nl.r1a.grassmap.plugin.api.contract.CommandId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MapButtonLayoutTest {
    private val custom =
        MapButtonLayout(
            listOf(
                MapControlCommands.ZOOM_IN,
                null,
                MapControlCommands.NORTH_UP,
                null,
                MapControlCommands.TRACKS,
                null,
            ),
        )

    @Test
    fun theDefaultLayoutHasOneFreeSlot() {
        assertEquals(MapButtonLayout.SLOT_COUNT, MapButtonLayout.DEFAULT.slots.size)
        assertEquals(1, MapButtonLayout.DEFAULT.slots.count { it == null })
        assertEquals(MapControlCommands.DEFAULT_ORDER, MapButtonLayout.DEFAULT.commands)
    }

    @Test
    fun placeFillsTheFirstFreeSlotAndMovesNothingElse() {
        val placed = custom.place(MapControlCommands.SAVED)

        assertEquals(
            listOf(
                MapControlCommands.ZOOM_IN,
                MapControlCommands.SAVED,
                MapControlCommands.NORTH_UP,
                null,
                MapControlCommands.TRACKS,
                null,
            ),
            placed.slots,
        )
        assertEquals(2, placed.slots.indexOf(MapControlCommands.NORTH_UP))
        assertEquals(4, placed.slots.indexOf(MapControlCommands.TRACKS))
    }

    @Test
    fun placeOfAPlacedCommandChangesNothing() {
        assertSame(custom, custom.place(MapControlCommands.TRACKS))
    }

    @Test
    fun placeIntoAFullLayoutChangesNothing() {
        val full = MapButtonLayout(List(MapButtonLayout.SLOT_COUNT) { index -> MapControlCommands.CONFIGURABLE[index] })

        assertSame(full, full.place(MapControlCommands.TRACKS))
    }

    @Test
    fun removeFreesOnlyItsOwnSlot() {
        val removed = custom.remove(MapControlCommands.NORTH_UP)

        assertEquals(
            listOf(
                MapControlCommands.ZOOM_IN,
                null,
                null,
                null,
                MapControlCommands.TRACKS,
                null,
            ),
            removed.slots,
        )
        assertEquals(4, removed.slots.indexOf(MapControlCommands.TRACKS))
    }

    @Test
    fun removeOfAnUnplacedCommandChangesNothing() {
        assertSame(custom, custom.remove(MapControlCommands.SAVED))
    }

    @Test
    fun theArcReadsThePlacedCommandsInSlotOrderAndSkipsFreeSlots() {
        assertEquals(
            listOf(MapControlCommands.ZOOM_IN, MapControlCommands.NORTH_UP, MapControlCommands.TRACKS),
            custom.commands,
        )
    }

    @Test
    fun everyConfigurableButtonIsUnique() {
        assertEquals(
            MapControlCommands.CONFIGURABLE.size,
            MapControlCommands.CONFIGURABLE.toSet().size,
        )
        assertEquals(
            MapControlCommands.CONFIGURABLE.toSet(),
            MapControlCommands.DEFAULT_ORDER.toSet() +
                setOf(MapControlCommands.SAVED, MapControlCommands.TRACKS),
        )
        assertEquals(CommandId("app.map.saved"), MapControlCommands.SAVED)
    }
}
