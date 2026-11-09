package nl.r1a.grassmap.plugins.links

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every links label exists in English and in Ukrainian. */
class LinksStringsTest {
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

    private fun resources(path: String): Map<String, String> =
        Regex("""<string name="([^"]+)">([^<]*)</string>""")
            .findAll(File(path).readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private companion object {
        const val ENGLISH = "src/main/res/values/strings.xml"
        const val UKRAINIAN = "src/main/res/values-uk/strings.xml"

        val KEYS =
            setOf(
                "plugin.links.name",
                "plugin.links.description",
            )
    }
}
