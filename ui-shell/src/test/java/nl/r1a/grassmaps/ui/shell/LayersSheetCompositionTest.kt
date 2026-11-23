package nl.r1a.grassmaps.ui.shell

import androidx.compose.ui.Alignment
import nl.r1a.grassmaps.plugin.ui.api.LayerRowSpec
import nl.r1a.grassmaps.plugin.ui.api.LayerStyleOption
import nl.r1a.grassmaps.plugin.ui.api.layerStyleRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composition fixes of T-0612: phone-size tap targets, a style row whose
 * choice and hint are separate lines, and a bottom sheet at the foot of the
 * screen. These pin the spec the Layers card renders from, not a screenshot.
 */
class LayersSheetCompositionTest {
    @Test
    fun everyInteractiveRowAndRadioIsAtLeastFortyEightDp() {
        assertEquals(48, LayerRowSpec.ROW_MIN_HEIGHT_DP)
        assertEquals(48, LayerRowSpec.RADIO_MIN_SIZE_DP)
        assertTrue(LayerRowSpec.ROW_MIN_HEIGHT_DP >= 48)
        assertTrue(LayerRowSpec.RADIO_MIN_SIZE_DP >= 48)
    }

    @Test
    fun eachStyleRowKeepsTheRoundElevenNameAndHintApart() {
        val rows =
            layerStyleRows(
                options =
                    listOf(
                        LayerStyleOption(name = "Meadow", hint = "Everyday and city"),
                        LayerStyleOption(name = "Trail", hint = "Cycling and hiking"),
                    ),
                selectedName = "Trail",
            )

        assertEquals(2, rows.size)
        assertEquals("Meadow", rows[0].name)
        assertEquals("Everyday and city", rows[0].hint)
        assertFalse(rows[0].selected)
        assertEquals("Trail", rows[1].name)
        assertEquals("Cycling and hiking", rows[1].hint)
        assertTrue(rows[1].selected)
        for (row in rows) {
            assertTrue("a hint must not be folded into the choice", row.name.isNotBlank() && row.hint.isNotBlank())
            assertFalse("name and hint must be separate lines", row.name.contains(row.hint))
        }
    }

    @Test
    fun theLayersCardIsABottomSheetWithAHandle() {
        assertEquals(Alignment.BottomCenter, LAYERS_SHEET_ANCHOR)
        assertTrue(LAYERS_SHEET_HAS_HANDLE)
    }
}
