package nl.r1a.grassmaps.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The route-preferences scope says what the change covers and what the dot
 * means (T-0612). Both languages use two short sentences and no semicolon.
 */
class RoutePreferencesScopeStringsTest {
    @Test
    fun bothLanguagesStateTheScopeAndTheDotMeaning() {
        val english = scope(ENGLISH)
        val ukrainian = scope(UKRAINIAN)

        assertEquals("These apply to this route only. A dot marks the %1\$s profile defaults.", english)
        assertEquals(
            "Ці значення стосуються лише цього маршруту. Крапка позначає типові значення профілю «%1\$s».",
            ukrainian,
        )
        assertTrue("English explains the dot", english.contains("dot", ignoreCase = true))
        assertTrue("Ukrainian explains the dot", ukrainian.contains("крапка", ignoreCase = true))
        assertTrue("English names the profile", english.contains("profile", ignoreCase = true))
        assertTrue("Ukrainian names the profile", ukrainian.contains("профілю", ignoreCase = true))
    }

    @Test
    fun neitherLanguageUsesASemicolonOrMoreThanTwoSentences() {
        for ((path, text) in listOf(ENGLISH to scope(ENGLISH), UKRAINIAN to scope(UKRAINIAN))) {
            assertFalse("$path has no semicolon", text.contains(';'))
            assertEquals("$path has two sentences", 2, text.split(". ").size)
        }
    }

    private fun scope(path: String): String =
        Regex("""<string name="route_preferences_scope">([^<]*)</string>""")
            .find(File(path).readText())
            ?.groupValues
            ?.get(1)
            .orEmpty()

    private companion object {
        const val ENGLISH = "src/main/res/values/strings.xml"
        const val UKRAINIAN = "src/main/res/values-uk/strings.xml"
    }
}
