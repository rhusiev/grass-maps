package nl.r1a.grassmap.ui.shell

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every navigation screen string exists in English and in Ukrainian. */
class NavigationScreenStringsTest {
    @Test
    fun everyScreenKeyIsTranslatedAndNotBlank() {
        val english = resources(ENGLISH)
        val ukrainian = resources(UKRAINIAN)
        val keys =
            NavigationScreenStrings::class.java.declaredFields
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

    private companion object {
        const val ENGLISH = "src/main/res/values/strings.xml"
        const val UKRAINIAN = "src/main/res/values-uk/strings.xml"
    }
}
