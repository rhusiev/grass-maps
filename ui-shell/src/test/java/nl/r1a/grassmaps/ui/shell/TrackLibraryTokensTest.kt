package nl.r1a.grassmaps.ui.shell

import nl.r1a.grassmaps.plugin.ui.api.DarkShellColors
import nl.r1a.grassmaps.plugin.ui.api.LightShellColors
import nl.r1a.grassmaps.plugin.ui.api.contrastRatio
import nl.r1a.grassmaps.plugin.ui.api.inkOn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The track library palette's token wiring (T-0612), without a device. */
class TrackLibraryTokensTest {
    @Test
    fun theLightPaletteIsTheLightTokenSet() {
        val palette = trackLibraryPalette(dark = false)
        assertEquals("round 8 x-tracks draws the light list on white", LightShellColors.surface, palette.background)
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

    @Test
    fun theUndoSnackbarReadsInBothThemes() {
        listOf(false, true).forEach { dark ->
            val palette = trackLibraryPalette(dark)
            assertTrue(
                "the undo action on the inverse snackbar",
                contrastRatio(palette.undoAction, palette.undoBackground) >= BODY_TEXT_CONTRAST,
            )
            assertTrue(
                "the snackbar message on the inverse snackbar",
                contrastRatio(palette.undoText, palette.undoBackground) >= BODY_TEXT_CONTRAST,
            )
            assertTrue(
                "the track library field outline",
                contrastRatio(palette.border, palette.field) >= UI_COMPONENT_CONTRAST,
            )
        }
    }

    private companion object {
        const val BODY_TEXT_CONTRAST = 4.5
        const val UI_COMPONENT_CONTRAST = 3.0
    }
}
