package nl.r1a.grassmaps.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Both locales carry every Settings index row, including the new ones (T-0612). */
class SettingsIndexStringsTest {
    @Test
    fun newRowsAreTranslatedInBothLocales() {
        for (key in NEW_KEYS) {
            assertTrue("$key in English", string(ENGLISH, key).isNotBlank())
            assertTrue("$key in Ukrainian", string(UKRAINIAN, key).isNotBlank())
        }
    }

    @Test
    fun bothLocalesHaveTheSameIndexRowCount() {
        assertEquals("locale row counts match", indexRowCount(ENGLISH), indexRowCount(UKRAINIAN))
        assertEquals("eight index rows", 8, indexRowCount(ENGLISH))
    }

    @Test
    fun searchAndEmptyCopyAreTranslatedInBothLocales() {
        for (key in SEARCH_KEYS) {
            assertTrue("$key in English", string(ENGLISH, key).isNotBlank())
            assertTrue("$key in Ukrainian", string(UKRAINIAN, key).isNotBlank())
        }
    }

    @Test
    fun noCopyClaimsTheWidgetsAndSavedSheetIsMissing() {
        for (path in listOf(ENGLISH, UKRAINIAN)) {
            assertFalse("$path has stale tray copy", File(path).readText().contains("widgets and Saved tray"))
        }
    }

    private fun indexRowCount(path: String): Int =
        Regex("""<string name="settings\.index\.row\.[^"]+">""")
            .findAll(File(path).readText())
            .count()

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
        const val ENGLISH = "src/main/res/values/strings.xml"
        const val UKRAINIAN = "src/main/res/values-uk/strings.xml"
        val NEW_KEYS =
            listOf(
                "settings.index.row.tracks",
                "settings.index.row.plugins",
                "settings.index.row.backup",
            )
        val SEARCH_KEYS =
            listOf(
                "settings.index.search",
                "settings.index.search.empty",
            )
    }
}
