package nl.r1a.grassmaps.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Final blind review 4 P1: the Settings back glyph was still a 5x6 px sliver in
 * both themes. Train 105 removed alignByBaseline() but never centered the glyph
 * inside its 48 dp circle, so the clip still cut almost all of it. The glyph is
 * now a BasicText child of a centered Box that owns the fixed size, circular
 * clip, button role and spoken name. Mappers are Composables a JVM test cannot
 * render, so pin the modifier and composable shape from source.
 */
class SettingsBackGlyphTest {
    private val source = File(HOST_FILE).readText()
    private val header =
        source
            .substringAfter("verticalAlignment = Alignment.CenterVertically,")
            .substringBefore("text = content.title,")
    private val boxHeader = header.substringAfter("Box(").substringBefore(") {")
    private val boxBody = header.substringAfter("Box(").substringAfter(") {")

    @Test
    fun backGlyphIsCenteredAsAChildOfTheFixedClippedBox() {
        assertTrue("the back header has no Box", header.contains("Box("))
        assertTrue("the fixed size is not on the Box", boxHeader.contains(".size(BACK_DP.dp)"))
        assertTrue("the circular clip is not on the Box", boxHeader.contains(".clip(CircleShape)"))
        assertTrue("the button role is not on the Box", boxHeader.contains("role = Role.Button"))
        assertTrue("the spoken name is not on the Box", boxHeader.contains("contentDescription = content.back"))
        assertTrue("the clipped Box does not center its content", boxHeader.contains("contentAlignment = Alignment.Center"))

        assertTrue("the glyph is not a child of the Box", boxBody.contains("text = BACK_GLYPH,"))
        val glyph = boxBody.substringAfter("text = BACK_GLYPH,")
        assertFalse("the glyph itself is still the clipped sized text", glyph.contains(".size("))
        assertFalse("the glyph itself still carries the clip", glyph.contains(".clip("))
        assertTrue("the glyph lost its accent color", glyph.contains("color = colors.accent"))
    }

    @Test
    fun backGlyphKeepsIts48DpTargetRoleNameAndColor() {
        assertEquals("48", Regex("""const val BACK_DP = (\d+)""").find(source)?.groupValues?.get(1))
        assertTrue("the header lost its vertical centering", source.contains("verticalAlignment = Alignment.CenterVertically"))
        assertTrue("the title is gone from the header", source.contains("text = content.title,"))
        assertTrue("the glyph target lost its button role", boxHeader.contains("role = Role.Button"))
        assertTrue("the glyph target lost its spoken name", boxHeader.contains("contentDescription = content.back"))
    }

    @Test
    fun backGlyphHasNoBaselineAlignment() {
        assertFalse("a fixed-size clipped box still aligns by baseline", header.contains("alignByBaseline()"))
    }

    private companion object {
        const val HOST_FILE = "src/main/java/nl/r1a/grassmaps/app/SettingsIndexHost.kt"
    }
}
