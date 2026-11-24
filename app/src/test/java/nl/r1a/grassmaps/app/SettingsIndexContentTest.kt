package nl.r1a.grassmaps.app

import nl.r1a.grassmaps.plugin.api.service.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The Settings index offers only rows with a real destination (T-0612). */
class SettingsIndexContentTest {
    private val strings =
        object : Strings {
            override fun resolve(
                key: String,
                arguments: List<String>,
            ): String = "[$key]"

            override fun contains(key: String): Boolean = true
        }

    private fun content() = settingsIndexContent(strings)

    @Test
    fun rowsAndTargetsArePinned() {
        assertEquals(
            listOf(
                SETTINGS_INDEX_PROFILES_KEY to SettingsIndexTarget.PROFILES,
                SETTINGS_INDEX_LANGUAGE_KEY to SettingsIndexTarget.APPEARANCE,
                SETTINGS_INDEX_APPEARANCE_KEY to SettingsIndexTarget.APPEARANCE,
                SETTINGS_INDEX_DOWNLOADS_KEY to SettingsIndexTarget.DOWNLOADS,
                SETTINGS_INDEX_SAVED_KEY to SettingsIndexTarget.SAVED,
            ),
            content().rows.map { it.key to it.target },
        )
    }

    @Test
    fun rowsSitInTheCanonsTwoGroups() {
        assertEquals(
            listOf(
                SettingsIndexGroup.USE,
                SettingsIndexGroup.USE,
                SettingsIndexGroup.USE,
                SettingsIndexGroup.DATA_AND_PRIVACY,
                SettingsIndexGroup.DATA_AND_PRIVACY,
            ),
            content().rows.map { it.group },
        )
    }

    @Test
    fun headersAndRowTitlesResolveFromStrings() {
        val content = content()
        assertEquals("[settings.screen_title]", content.title)
        assertEquals("[settings.index.subtitle]", content.subtitle)
        assertEquals("[settings.index.group.use]", content.groupTitles[SettingsIndexGroup.USE])
        assertEquals("[settings.index.group.data]", content.groupTitles[SettingsIndexGroup.DATA_AND_PRIVACY])
        assertEquals("[settings.index.row.profiles]", content.rows[0].title)
    }

    @Test
    fun defaultSettingsIndexOpensSavedAboveSettings() {
        val saved = content().rows.singleOrNull { it.key == "settings.index.row.saved" }
        assertEquals("SAVED", saved?.target?.name)
        assertEquals("[settings.index.row.saved]", saved?.title)

        val map = File("src/main/java/nl/r1a/grassmaps/app/MapScreen.kt").readText()
        val settings = map.indexOf("SettingsIndexHost(")
        val savedHost = map.indexOf("FavouritesListsHost(")
        assertTrue("The default map must expose Settings", map.contains("onClick = SettingsIndexSession::show"))
        assertTrue("Saved must open above Settings", settings in 0 until savedHost)
        assertTrue(map.substring(settings, savedHost).contains("SettingsIndexTarget.SAVED -> FavouritesListsSession.show()"))
    }
}
