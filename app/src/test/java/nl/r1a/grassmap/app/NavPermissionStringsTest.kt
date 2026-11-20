package nl.r1a.grassmap.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The guidance-permission notice exists in both languages and stays honest
 * (T-0639). It never claims the app blocks the system location switch.
 */
class NavPermissionStringsTest {
    @Test
    fun everyNoticeStringExistsInBothLanguages() {
        for (key in KEYS) {
            assertTrue("$key in English", string(ENGLISH, key).isNotBlank())
            assertTrue("$key in Ukrainian", string(UKRAINIAN, key).isNotBlank())
        }
    }

    @Test
    fun theMessageNamesGuidanceAndTheLocationPermission() {
        val english = string(ENGLISH, MESSAGE)
        val ukrainian = string(UKRAINIAN, MESSAGE)
        assertTrue("English names guidance", english.contains("guidance", ignoreCase = true))
        assertTrue("English names the permission", english.contains("location permission", ignoreCase = true))
        assertTrue("Ukrainian names navigation", ukrainian.contains("навігац", ignoreCase = true))
        assertTrue("Ukrainian names the permission", ukrainian.contains("дозвол", ignoreCase = true))
    }

    @Test
    fun theMessageNeverClaimsTheAppBlocksTurningLocationOff() {
        val english = string(ENGLISH, MESSAGE)
        val ukrainian = string(UKRAINIAN, MESSAGE)
        assertFalse(english.contains("prevent", ignoreCase = true))
        assertFalse(english.contains("cannot turn off", ignoreCase = true))
        assertFalse(ukrainian.contains("не дає вимкнути", ignoreCase = true))
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
        const val ENGLISH = "src/main/res/values/strings.xml"
        const val UKRAINIAN = "src/main/res/values-uk/strings.xml"
        const val MESSAGE = "nav_permission_required_message"

        val KEYS =
            listOf(
                "nav_permission_notice_title",
                MESSAGE,
                "nav_permission_open_settings",
            )
    }
}
