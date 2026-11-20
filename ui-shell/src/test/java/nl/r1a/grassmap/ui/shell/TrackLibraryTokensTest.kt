package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.ui.api.DarkShellColors
import nl.r1a.grassmap.plugin.ui.api.LightShellColors
import nl.r1a.grassmap.plugin.ui.api.inkOn
import org.junit.Assert.assertEquals
import org.junit.Test

/** The track library palette's token wiring (T-0612), without a device. */
class TrackLibraryTokensTest {
    @Test
    fun theLightPaletteIsTheLightTokenSet() {
        val palette = trackLibraryPalette(dark = false)
        assertEquals(LightShellColors.map, palette.background)
        assertEquals(LightShellColors.surface, palette.surface)
        assertEquals(LightShellColors.soft, palette.field)
        assertEquals(LightShellColors.selectedChip, palette.selectedSurface)
        assertEquals(LightShellColors.ink, palette.textPrimary)
        assertEquals(LightShellColors.muted, palette.textSecondary)
        assertEquals(LightShellColors.accent, palette.accent)
        assertEquals(inkOn(LightShellColors.selectedChip, LightShellColors), palette.selectedText)
    }

    @Test
    fun theDarkPaletteIsTheDarkTokenSet() {
        val palette = trackLibraryPalette(dark = true)
        assertEquals(DarkShellColors.map, palette.background)
        assertEquals(DarkShellColors.surface, palette.surface)
        assertEquals(DarkShellColors.soft, palette.field)
        assertEquals(DarkShellColors.selectedChip, palette.selectedSurface)
        assertEquals(DarkShellColors.ink, palette.textPrimary)
        assertEquals(DarkShellColors.muted, palette.textSecondary)
        assertEquals(DarkShellColors.accent, palette.accent)
        assertEquals(DarkShellColors.danger, palette.danger)
        assertEquals(inkOn(DarkShellColors.selectedChip, DarkShellColors), palette.selectedText)
    }
}
