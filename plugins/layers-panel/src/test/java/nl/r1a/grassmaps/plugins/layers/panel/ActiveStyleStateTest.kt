package nl.r1a.grassmaps.plugins.layers.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveStyleStateTest {
    @Test
    fun startsOnMeadowByDefault() {
        assertEquals(MapStyleChoice.MEADOW, ActiveStyleState().active.value)
    }

    @Test
    fun selectingTrailChangesTheActiveStyle() {
        val state = ActiveStyleState()

        assertTrue(state.select(MapStyleChoice.TRAIL))

        assertEquals(MapStyleChoice.TRAIL, state.active.value)
    }

    @Test
    fun selectingTheActiveStyleChangesNothing() {
        val state = ActiveStyleState(MapStyleChoice.TRAIL)

        assertFalse(state.select(MapStyleChoice.TRAIL))

        assertEquals(MapStyleChoice.TRAIL, state.active.value)
    }

    @Test
    fun switchingBackRestoresMeadow() {
        val state = ActiveStyleState()
        state.select(MapStyleChoice.TRAIL)

        state.select(MapStyleChoice.MEADOW)

        assertEquals(MapStyleChoice.MEADOW, state.active.value)
    }

    @Test
    fun choicesMapToTheUnchangedStyleIds() {
        assertEquals("meadow", MapStyleChoice.MEADOW.styleId)
        assertEquals("trail", MapStyleChoice.TRAIL.styleId)
        assertEquals(MapStyleChoice.TRAIL, MapStyleChoice.fromStyleId("trail"))
        assertNull(MapStyleChoice.fromStyleId("trail-dark"))
        assertNull(MapStyleChoice.fromStyleId(null))
    }
}
