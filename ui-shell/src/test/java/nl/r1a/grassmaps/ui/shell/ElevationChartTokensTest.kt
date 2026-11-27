package nl.r1a.grassmaps.ui.shell

import nl.r1a.grassmaps.plugin.ui.api.contrastRatio
import org.junit.Assert.assertTrue
import org.junit.Test

/** The elevation chart's private palette against the shell's control boundary (T-0636). */
class ElevationChartTokensTest {
    @Test
    fun theAxisChipOutlineReadsOnTheChartCard() {
        listOf(false, true).forEach { dark ->
            val palette = elevationChartPalette(dark)
            assertTrue(
                "the axis chip outline on the chart card in the ${if (dark) "dark" else "light"} theme",
                contrastRatio(palette.tokens.outline.chip, palette.surface) >= UI_COMPONENT_CONTRAST,
            )
        }
    }

    private companion object {
        const val UI_COMPONENT_CONTRAST = 3.0
    }
}
