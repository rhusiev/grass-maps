package nl.r1a.grassmap.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The notification channel copy must not claim more than the app does: the
 * navigation foreground service keeps location active, it does not block the
 * system location switch (T-0613).
 */
class NotificationChannelStringsTest {
    @Test
    fun theNavigationChannelSaysItKeepsLocationActive() {
        val english = string(ENGLISH, "nav_notification_channel_description")
        val ukrainian = string(UKRAINIAN, "nav_notification_channel_description")

        assertTrue(english.contains("keeps location active"))
        assertTrue(ukrainian.contains("підтримує місцеположення активним"))
        assertFalse(ukrainian.contains("не дає вимкнути"))
    }

    @Test
    fun everyChannelDescriptionExistsInBothLanguages() {
        for (key in CHANNEL_DESCRIPTIONS) {
            assertTrue("$key in English", string(ENGLISH, key).isNotBlank())
            assertTrue("$key in Ukrainian", string(UKRAINIAN, key).isNotBlank())
        }
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

        val CHANNEL_DESCRIPTIONS =
            listOf(
                "nav_notification_channel_description",
                "download_notification_channel_description",
                "map_update_channel_description",
            )
    }
}
