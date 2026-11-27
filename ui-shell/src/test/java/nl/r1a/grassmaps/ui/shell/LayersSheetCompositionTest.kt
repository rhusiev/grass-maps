package nl.r1a.grassmaps.ui.shell

import androidx.compose.ui.Alignment
import nl.r1a.grassmaps.plugin.ui.api.LayerRowSpec
import nl.r1a.grassmaps.plugin.ui.api.LayerStyleOption
import nl.r1a.grassmaps.plugin.ui.api.StyleSwatch
import nl.r1a.grassmaps.plugin.ui.api.layerStyleRows
import nl.r1a.grassmaps.plugin.ui.api.switchOutline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
                        LayerStyleOption(name = "Meadow", hint = "Everyday and city", swatch = StyleSwatch.MEADOW),
                        LayerStyleOption(name = "Trail", hint = "Cycling and hiking", swatch = StyleSwatch.TRAIL),
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

    @Test
    fun eachRoundElevenStyleCarriesItsOwnTokenSwatch() {
        val rows =
            layerStyleRows(
                options =
                    listOf(
                        LayerStyleOption(name = "Meadow", hint = "Everyday and city", swatch = StyleSwatch.MEADOW),
                        LayerStyleOption(name = "Trail", hint = "Cycling and hiking", swatch = StyleSwatch.TRAIL),
                    ),
                selectedName = "Trail",
            )

        assertEquals(StyleSwatch.MEADOW, rows[0].swatch)
        assertEquals(StyleSwatch.TRAIL, rows[1].swatch)
        assertNotEquals(rows[0].swatch, rows[1].swatch)
        assertEquals(2, StyleSwatch.entries.size)
    }

    @Test
    fun theMoreRowReusesTheExistingAllProfilesEntryPoint() {
        assertEquals("all_profiles", LAYERS_MORE_ENTRY_POINT)
    }

    @Test
    fun switchUsesCheckedStateForSemanticsAndThumbPosition() {
        val row = sheetSource().substringAfter("fun LayerToggle(").substringBefore("fun LayerMarkerShapeOptions(")
        assertTrue(row.contains(".toggleable(value = checked, role = Role.Switch, onValueChange = onChange)"))
        assertTrue(row.contains(".heightIn(min = LayerRowSpec.ROW_MIN_HEIGHT_DP.dp)"))
        assertTrue(row.contains(".align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)"))
    }

    @Test
    fun markerShapeUsesSelectedFillAndMinimumTargets() {
        val row = sheetSource().substringAfter("fun LayerMarkerShapeOptions(").substringBefore("fun LayerStyleChoices(")
        assertTrue(row.contains("if (selected) colors.selectedChip else colors.soft"))
        assertTrue(row.contains(".selectable(selected = selected, role = Role.RadioButton)"))
        assertTrue(row.contains(".sizeIn(minWidth = target, minHeight = target)"))
    }

    @Test
    fun styleRadioAlwaysDrawsARingAndAddsADotOnlyWhenSelected() {
        val radio = sheetSource().substringAfter("private fun LayerStyleRadio(")
        assertTrue(radio.contains(".switchOutline(colors, selected, CircleShape)"))
        assertTrue(radio.contains("if (selected)"))
        assertTrue(radio.contains(".background(colors.accent, CircleShape)"))
    }

    @Test
    fun styleChoicesAreHorizontalThumbnailsWithADashedMoreAndFortyEightDpTargets() {
        val source = sheetSource()
        val choices = source.substringAfter("fun LayerStyleChoices(").substringBefore("private fun LayerStyleRadio(")

        assertFalse("full-width radio rows are gone", choices.contains("Column(Modifier.selectableGroup())"))
        assertTrue("the choices sit in one horizontal row", choices.contains("Row("))
        assertTrue(choices.contains("horizontalArrangement = Arrangement.spacedBy"))
        assertTrue("the More tile has a dashed outline", choices.contains("dashedOutline("))
        assertTrue("the preview draws a dashed trail", choices.contains("PathEffect.dashPathEffect"))
        assertTrue("the selection command survives the layout", choices.contains("onSelect(index)"))
        assertTrue("the selected ring and dot survive the layout", choices.contains("LayerStyleRadio(row.selected)"))
        assertTrue("the name stays visible", choices.contains("row.name"))
        assertTrue("the one-line hint stays visible", choices.contains("row.hint"))
        assertTrue(
            "each style keeps its token swatch",
            choices.contains("if (row.swatch == StyleSwatch.MEADOW) colors.accent else colors.amber"),
        )

        val minWidth =
            Regex("STYLE_TILE_MIN_WIDTH_DP = (\\d+)")
                .find(source)!!
                .groupValues[1]
                .toInt()
        assertTrue("each tile must be at least 48 dp wide", minWidth >= 48)
        assertTrue("each tile keeps the 48 dp height floor", choices.contains("minHeight = LayerRowSpec.ROW_MIN_HEIGHT_DP.dp"))
    }

    private fun sheetSource(): String = File("src/main/java/nl/r1a/grassmaps/ui/shell/LayersSheet.kt").readText()
}
