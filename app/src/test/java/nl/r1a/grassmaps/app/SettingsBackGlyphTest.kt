package nl.r1a.grassmaps.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Final blind review 3 P1: the Settings back glyph was clipped to a sliver in
 * both themes. The 18 sp glyph sat in a 48 dp box clipped to a circle, but the
 * box also used alignByBaseline() against a larger baseline-aligned title. The
 * baseline alignment moved the box so the glyph fell outside the clip. The
 * header now centers both children vertically, keeping the glyph inside its
 * clipped target. Mappers are Composables a JVM test cannot render, so pin the
 * modifier arrangement from source.
 */
class SettingsBackGlyphTest {
    private val source = File(HOST_FILE).readText()
    private val back = source.substringAfter("text = BACK_GLYPH,").substringBefore("\n                )")

    @Test
    fun backGlyphHasNoBaselineAlignmentOnItsFixedClippedBox() {
        assertTrue("the back glyph is gone", back.isNotBlank())
        assertFalse(
            "a fixed-size clipped box still aligns by baseline and clips the glyph",
            back.contains("alignByBaseline()"),
        )
        val title = source.substringAfter("text = content.title,").substringBefore("\n                )")
        assertFalse(
            "the title still drags the back box off center",
            title.contains("alignByBaseline()"),
        )
        assertTrue(
            "the header must center back and title vertically",
            source.contains("verticalAlignment = Alignment.CenterVertically"),
        )
    }

    @Test
    fun backGlyphKeepsIts48DpTargetRoleNameAndColor() {
        assertEquals("48", Regex("""const val BACK_DP = (\d+)""").find(source)?.groupValues?.get(1))
        assertTrue("the back glyph lost its 48 dp target", back.contains(".size(BACK_DP.dp)"))
        assertTrue("the back glyph lost its circular clip", back.contains(".clip(CircleShape)"))
        assertTrue("the back glyph lost its button role", back.contains("role = Role.Button"))
        assertTrue("the back glyph lost its spoken name", back.contains("contentDescription = content.back"))
        assertTrue("the back glyph lost its accent color", back.contains("color = colors.accent"))
        assertTrue("the title is gone from the header", source.contains("text = content.title,"))
    }

    private companion object {
        const val HOST_FILE = "src/main/java/nl/r1a/grassmaps/app/SettingsIndexHost.kt"
    }
}
