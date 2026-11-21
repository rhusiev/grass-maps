package nl.r1a.grassmaps.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The glossary keeps one Ukrainian term per concept. The missing-part labels
 * are visible siblings of the terms fixed in T-0613; this pins the map label
 * to `мапа` so `карта` does not drift back (T-0634).
 */
class UkrainianGlossaryStringsTest {
    @Test
    fun theMissingMapPartUsesTheGlossaryTerm() {
        val value = string(UKRAINIAN, "route_planner_missing_part_map")

        assertTrue("map part label: $value", "Мапа" in value)
        assertFalse("map part label: $value", "Карта" in value)
    }

    private fun string(
        path: String,
        key: String,
    ): String =
        Regex("""<string name="${Regex.escape(key)}">([^<]*)</string>""")
            .find(File(path).readText())
            ?.groupValues
            ?.get(1)
            .orEmpty()

    private companion object {
        const val UKRAINIAN = "src/main/res/values-uk/strings.xml"
    }
}
