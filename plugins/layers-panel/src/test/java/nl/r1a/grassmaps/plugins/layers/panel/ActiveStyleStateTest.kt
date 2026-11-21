package nl.r1a.grassmaps.plugins.layers.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveStyleStateTest {
    @Test
    fun startsOnMeadowByDefault() {
        assertEquals(MapStyleChoice.STANDARD, ActiveStyleState().active.value)
    }

    @Test
    fun selectingTrailChangesTheActiveStyle() {
        val state = ActiveStyleState()

        assertTrue(state.select(MapStyleChoice.TOURING))

        assertEquals(MapStyleChoice.TOURING, state.active.value)
    }

    @Test
    fun selectingTheActiveStyleChangesNothing() {
        val state = ActiveStyleState(MapStyleChoice.TOURING)

        assertFalse(state.select(MapStyleChoice.TOURING))

        assertEquals(MapStyleChoice.TOURING, state.active.value)
    }

    @Test
    fun switchingBackRestoresMeadow() {
        val state = ActiveStyleState()
        state.select(MapStyleChoice.TOURING)

        state.select(MapStyleChoice.STANDARD)

        assertEquals(MapStyleChoice.STANDARD, state.active.value)
    }

    @Test
    fun choicesMapToTheUnchangedStyleIds() {
        assertEquals("standard", MapStyleChoice.STANDARD.styleId)
        assertEquals("touring", MapStyleChoice.TOURING.styleId)
        assertEquals(MapStyleChoice.TOURING, MapStyleChoice.fromStyleId("touring"))
        assertNull(MapStyleChoice.fromStyleId("touring-dark"))
        assertNull(MapStyleChoice.fromStyleId(null))
    }
}
