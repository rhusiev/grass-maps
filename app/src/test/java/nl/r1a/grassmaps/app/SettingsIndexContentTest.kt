package nl.r1a.grassmaps.app

import nl.r1a.grassmaps.plugin.api.service.Strings
import org.junit.Assert.assertEquals
import org.junit.Test

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
}
