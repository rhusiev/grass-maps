package nl.r1a.grassmap.ui.shell

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every map layout string exists in English and in Ukrainian. */
class MapLayoutStringsTest {
    @Test
    fun everyLayoutKeyIsTranslatedAndNotBlank() {
        val english = resources(ENGLISH)
        val ukrainian = resources(UKRAINIAN)
        val keys =
            MapLayoutStrings::class.java.declaredFields
                .filter { field -> field.type == String::class.java }
                .map { field -> field.get(null) as String }
                .toSet()

        assertTrue("the key list is empty", keys.isNotEmpty())
        for (key in keys) {
            val englishValue = english[key]
            val ukrainianValue = ukrainian[key]
            assertTrue("$key is missing in English", englishValue != null)
            assertTrue("$key is missing in Ukrainian", ukrainianValue != null)
            assertTrue("$key is blank in English", englishValue?.isNotBlank() == true)
            assertTrue("$key is blank in Ukrainian", ukrainianValue?.isNotBlank() == true)
        }
    }

    private fun resources(path: String): Map<String, String> =
        Regex("""<string name="([^"]+)">([^<]*)</string>""")
            .findAll(File(path).readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    @Test
    fun theSavedAndTracksLabelsAreSpokenNames() {
        val english = resources("src/main/res/values/strings.xml")
        for ((labelKey, contentKey) in listOf(
            "map_layout_saved_label" to "map_layout_saved_content",
            "map_layout_tracks_label" to "map_layout_tracks_content",
        )) {
            val label = english[labelKey]
            val content = english[contentKey]
            assertTrue("$labelKey is missing", label != null)
            assertTrue("$contentKey is missing", content != null)
            assertTrue("$contentKey reads as a glyph: '$content'", content!!.any { it.isLetter() } && content.length > 1)
            assertNotEquals("$contentKey repeats its drawn label", label, content)
        }
    }

    private companion object {
        const val ENGLISH = "src/main/res/values/strings.xml"
        const val UKRAINIAN = "src/main/res/values-uk/strings.xml"
    }
}
