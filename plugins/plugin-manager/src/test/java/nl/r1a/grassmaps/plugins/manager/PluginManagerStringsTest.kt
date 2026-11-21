package nl.r1a.grassmaps.plugins.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every manager label exists in English and in Ukrainian, and none is blank. */
class PluginManagerStringsTest {
    @Test
    fun englishAndUkrainianCarryTheSameStrings() {
        val english = strings(ENGLISH)
        val ukrainian = strings(UKRAINIAN)

        assertEquals(english.keys, ukrainian.keys)
        for ((key, value) in english + ukrainian) {
            assertTrue("$key is blank", value.isNotBlank())
        }
    }

    @Test
    fun englishAndUkrainianCarryTheSamePlurals() {
        val english = plurals(ENGLISH)
        val ukrainian = plurals(UKRAINIAN)

        assertEquals(english.keys, ukrainian.keys)
        assertEquals(setOf("one", "other"), english.getValue("plugin_manager_count"))
        assertEquals(setOf("one", "few", "many", "other"), ukrainian.getValue("plugin_manager_count"))
    }

    @Test
    fun everyResourceTheSourcesNameExists() {
        val declared = strings(ENGLISH).keys + plurals(ENGLISH).keys
        val used =
            File(SOURCE_ROOT)
                .walkTopDown()
                .filter { it.extension == "kt" }
                .flatMap { Regex("""R\.(?:string|plurals)\.([a-z_]+)""").findAll(it.readText()).map { m -> m.groupValues[1] } }
                .toSet()

        assertTrue("missing: ${used - declared}", declared.containsAll(used))
    }

    @Test
    fun theManifestKeysAreDeclared() {
        assertTrue("plugin.manager.name" in strings(ENGLISH))
        assertTrue("plugin.manager.description" in strings(ENGLISH))
    }

    private fun strings(path: String): Map<String, String> =
        Regex("""<string name="([^"]+)">([^<]*)</string>""")
            .findAll(File(path).readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun plurals(path: String): Map<String, Set<String>> =
        Regex("""<plurals name="([^"]+)">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(File(path).readText())
            .associate { block ->
                block.groupValues[1] to
                    Regex("""quantity="([a-z]+)"""").findAll(block.groupValues[2]).map { it.groupValues[1] }.toSet()
            }

    private companion object {
        const val ENGLISH = "src/main/res/values/strings.xml"
        const val UKRAINIAN = "src/main/res/values-uk/strings.xml"
        const val SOURCE_ROOT = "src/main/java/nl/r1a/grassmaps/plugins/manager"
    }
}
