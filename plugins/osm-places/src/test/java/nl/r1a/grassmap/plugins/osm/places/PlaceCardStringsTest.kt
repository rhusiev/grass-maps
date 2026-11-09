package nl.r1a.grassmap.plugins.osm.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every row label and typed value the card names exists in English and in
 * Ukrainian, and the two files carry the same keys.
 */
class PlaceCardStringsTest {
    @Test
    fun englishAndUkrainianCarryTheSameKeys() {
        val english = resources(ENGLISH)
        val ukrainian = resources(UKRAINIAN)

        assertEquals(english.keys, ukrainian.keys)
        for ((key, value) in english + ukrainian) {
            assertTrue("$key is blank", value.isNotBlank())
        }
    }

    @Test
    fun everyLabelTheCodeNamesHasAString() {
        val keys = resources(ENGLISH).keys

        for (key in referencedKeys()) {
            assertTrue("label key '$key' has no string", key in keys)
        }
    }

    private fun referencedKeys(): Set<String> {
        val constants =
            OsmPlaceLabels::class.java.declaredFields
                .filter { it.type == String::class.java }
                .map { it.get(null) as String }
        val networks = SocialNetwork.entries.map { it.labelKey }
        return (constants + networks + setOf(PLUGIN_NAME, PLUGIN_DESCRIPTION)).toSet()
    }

    private fun resources(path: String): Map<String, String> =
        Regex("""<string name="([^"]+)">([^<]*)</string>""")
            .findAll(File(path).readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private companion object {
        const val ENGLISH = "src/main/res/values/strings.xml"
        const val UKRAINIAN = "src/main/res/values-uk/strings.xml"
        const val PLUGIN_NAME = "plugin.osm_places.name"
        const val PLUGIN_DESCRIPTION = "plugin.osm_places.description"
    }
}
