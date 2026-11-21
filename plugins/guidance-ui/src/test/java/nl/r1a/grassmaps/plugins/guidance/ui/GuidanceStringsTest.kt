package nl.r1a.grassmaps.plugins.guidance.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The guidance panel's strings land in both bundles (T-0310). The gate's
 * translation check covers the app; this keeps the module's own two files in
 * step while it is built on its own.
 */
class GuidanceStringsTest {
    @Test
    fun englishAndUkrainianCarryTheSameKeys() {
        val english = resources(ENGLISH)
        val ukrainian = resources(UKRAINIAN)

        assertEquals(KEYS, english.keys)
        assertEquals(english.keys, ukrainian.keys)
        for ((key, value) in english + ukrainian) {
            assertTrue("$key is blank", value.isNotBlank())
        }
    }

    @Test
    fun everyPanelKeyHasAText() {
        val english = resources(ENGLISH)

        for (key in KEYS) {
            assertTrue("$key is missing from the English bundle", key in english)
        }
    }

    /**
     * The description names the via point with the glossary's family of
     * `проміжна точка`, not the transit `зупинка` (T-0634).
     */
    @Test
    fun theDescriptionUsesTheViaPointTerm() {
        val value = resources(UKRAINIAN).getValue(GuidanceStrings.PLUGIN_DESCRIPTION)

        assertTrue("plugin description: $value", "проміжну точку" in value)
        assertFalse("plugin description: $value", "зупинку" in value)
    }

    private fun resources(path: String): Map<String, String> =
        Regex("""<string name="([^"]+)">([^<]*)</string>""")
            .findAll(File(path).readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private companion object {
        const val ENGLISH = "src/main/res/values/strings.xml"
        const val UKRAINIAN = "src/main/res/values-uk/strings.xml"

        val KEYS =
            setOf(
                GuidanceStrings.PLUGIN_NAME,
                GuidanceStrings.PLUGIN_DESCRIPTION,
                GuidanceStrings.NEXT_STOP,
                GuidanceStrings.ARRIVE,
                GuidanceStrings.ARRIVED,
                GuidanceStrings.OFF_ROUTE,
                GuidanceStrings.IN,
                GuidanceStrings.THEN_IN,
                GuidanceStrings.MINUTES,
                GuidanceStrings.HOURS,
                GuidanceStrings.HOURS_MINUTES,
                GuidanceStrings.SCREEN_THEN,
            )
    }
}
