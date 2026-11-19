package nl.r1a.grassmap.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The map surface's two polite announcements draw their text from these
 * strings. The live regions themselves are Compose semantics and need a device;
 * this keeps the text they announce present in both languages.
 */
class MapAnnouncementTest {
    @Test
    fun theDistanceAndRegionBannersHaveTextInBothLanguages() {
        val english = resources(ENGLISH)
        val ukrainian = resources(UKRAINIAN)

        for (key in KEYS) {
            val en = english[key]
            val uk = ukrainian[key]
            assertTrue("$key is missing in English", en != null && en.isNotBlank())
            assertTrue("$key is missing in Ukrainian", uk != null && uk.isNotBlank())
        }
        assertTrue(english.getValue("map_distance_metres").contains("%1\$s"))
        assertTrue(english.getValue("map_distance_kilometres").contains("%1\$s"))
    }

    private fun resources(path: String): Map<String, String> =
        Regex("""<string name="([^"]+)">([^<]*)</string>""")
            .findAll(File(path).readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private companion object {
        const val ENGLISH = "src/main/res/values/strings.xml"
        const val UKRAINIAN = "src/main/res/values-uk/strings.xml"

        val KEYS =
            listOf(
                "map_distance_metres",
                "map_distance_kilometres",
                "map_region_needs_redownload",
            )
    }
}
