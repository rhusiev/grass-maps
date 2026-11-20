package nl.r1a.grassmap.plugin.ui.api

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The round 9 token set of T-0612 against the design handoff's exact values.
 *
 * The colours are literals on purpose: the test is the handoff's copy, so a
 * changed token has to be changed here and in the handoff together.
 */
class ShellColorsTest {
    @Test
    fun theDarkTokensMatchTheRound9Handoff() {
        val dark = DarkShellColors
        assertEquals(Color(0xFF1B1F24), dark.map)
        assertEquals(Color(0xFF24282D), dark.surface)
        assertEquals(Color(0xFF2F343A), dark.soft)
        assertEquals(Color(0xEB24282D), dark.glass)
        assertEquals(0.92f, dark.glass.alpha, ALPHA_TOLERANCE)
        assertEquals(Color(0xFFE8EAED), dark.ink)
        assertEquals(Color(0xFF9AA0A6), dark.muted)
        assertEquals(Color(0xFF474D54), dark.line)
        assertEquals(Color(0xFF5FD39A), dark.accent)
        assertEquals(Color(0xFFF28B82), dark.danger)
        assertEquals(Color(0xFF413B27), dark.warning.surface)
        assertEquals(Color(0xFFC99A12), dark.warning.border)
        assertEquals(Color(0xFFFFD970), dark.warning.title)
        assertEquals(Color(0xFF80868B), dark.offSwitchBorder)
        assertEquals(Color(0xFF15181C), dark.keyboard)
        assertEquals(Color(0xFF31363C), dark.key)
    }

    @Test
    fun theCategoryColoursTakeTheirDarkVariants() {
        val categories = DarkShellColors.categories
        assertEquals(Color(0xFF21B08C), categories.pharmacy)
        assertEquals(Color(0xFFBC88CD), categories.theater)
        assertEquals(Color(0xFFD48865), categories.coffee)
        assertEquals(Color(0xFFE08500), categories.closingSoon)
        assertEquals(Color(0xFFE77C75), categories.pin)
        assertEquals(Color(0xFFE4799E), categories.hiking)
        assertEquals(Color(0xFFCF8D4B), categories.contours)
        assertEquals(Color(0xFF6DAC52), categories.satellite)
        assertEquals(Color(0xFF9498C4), categories.night)
    }

    @Test
    fun everyDarkCategoryColourReadsOnTheSurface() {
        val categories = DarkShellColors.categories
        val onSurface =
            listOf(
                categories.pharmacy,
                categories.theater,
                categories.coffee,
                categories.closingSoon,
                categories.pin,
                categories.hiking,
                categories.contours,
                categories.satellite,
                categories.night,
            )
        onSurface.forEach { colour ->
            assertTrue(
                "$colour is below the 4.5:1 body-text target on the surface",
                contrastRatio(colour, DarkShellColors.surface) >= BODY_TEXT_CONTRAST,
            )
        }
    }

    @Test
    fun aSelectedChipInvertsToInkAndPicksTheContrastingText() {
        val dark = DarkShellColors
        assertEquals(dark.ink, dark.selectedChip)
        assertEquals(dark.onAccent, inkOn(dark.selectedChip, dark))
        assertEquals(dark.map, inkOn(dark.selectedChip, dark))

        val light = LightShellColors
        assertEquals(light.ink, light.selectedChip)
        assertEquals(light.onAccent, inkOn(light.selectedChip, light))
        assertEquals(light.surface, inkOn(light.selectedChip, light))
    }

    @Test
    fun theOnSwitchKnobPicksTheContrastingInk() {
        assertEquals(DarkShellColors.onAccent, inkOn(DarkShellColors.accent, DarkShellColors))
        assertEquals(LightShellColors.onAccent, inkOn(LightShellColors.accent, LightShellColors))
    }

    @Test
    fun theOffSwitchUsesTheHandoffBorder() {
        assertEquals(Color(0xFF80868B), DarkShellColors.offSwitchBorder)
        assertTrue(
            contrastRatio(DarkShellColors.offSwitchBorder, DarkShellColors.soft) >= QUIET_CONTROL_CONTRAST,
        )
    }

    @Test
    fun theWarningCardReadsInBothThemes() {
        val dark = DarkShellColors.warning
        assertEquals(Color(0xFF413B27), dark.surface)
        assertEquals(Color(0xFFC99A12), dark.border)
        assertEquals(Color(0xFFFFD970), dark.title)
        assertTrue(contrastRatio(dark.title, dark.surface) >= BODY_TEXT_CONTRAST)
        assertTrue(contrastRatio(dark.text, dark.surface) >= BODY_TEXT_CONTRAST)

        val light = LightShellColors.warning
        assertEquals(Color(0xFFFFF8E1), light.surface)
        assertEquals(Color(0xFFF2B200), light.border)
        assertEquals(Color(0xFF5C4300), light.title)
        assertTrue(contrastRatio(light.title, light.surface) >= BODY_TEXT_CONTRAST)
    }

    @Test
    fun thePrimaryTextReadsInBothThemes() {
        listOf(LightShellColors, DarkShellColors).forEach { colors ->
            assertTrue(contrastRatio(colors.ink, colors.surface) >= BODY_TEXT_CONTRAST)
            assertTrue(contrastRatio(colors.muted, colors.surface) >= MUTED_TEXT_CONTRAST)
        }
    }

    @Test
    fun theFloatingRingStandsInForAShadowOnlyInDark() {
        assertEquals(Color.Transparent, LightShellColors.ring)
        assertEquals(Color(0x12FFFFFF), DarkShellColors.ring)
    }

    @Test
    fun theLightThemeStaysTheLightCanon() {
        val light = LightShellColors
        assertEquals(Color(0xFF202124), light.ink)
        assertEquals(Color(0xFF686C72), light.muted)
        assertEquals(Color(0xFFEBE8E3), light.line)
        assertEquals(Color(0xFF1E7F53), light.accent)
        assertEquals(Color(0xFFB3261E), light.danger)
        assertEquals(Color(0xFFF2F1EE), light.soft)
        assertEquals(Color(0xFFFFFFFF), light.surface)
    }

    @Test
    fun shellColorsSwitchesEveryTokenAtOnce() {
        assertEquals(LightShellColors, shellColors(dark = false))
        assertEquals(DarkShellColors, shellColors(dark = true))
        assertEquals(false, LightShellColors.dark)
        assertEquals(true, DarkShellColors.dark)
    }

    private companion object {
        const val ALPHA_TOLERANCE = 0.005f
        const val BODY_TEXT_CONTRAST = 4.5
        const val MUTED_TEXT_CONTRAST = 4.5
        const val QUIET_CONTROL_CONTRAST = 3.0
    }
}
