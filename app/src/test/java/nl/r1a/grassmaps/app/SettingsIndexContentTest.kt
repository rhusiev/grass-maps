package nl.r1a.grassmaps.app

import nl.r1a.grassmaps.plugin.api.service.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                "settings.index.row.profiles" to "PROFILES",
                "settings.index.row.language" to "APPEARANCE",
                "settings.index.row.appearance" to "APPEARANCE",
                "settings.index.row.tracks" to "TRACKS",
                "settings.index.row.plugins" to "PLUGINS",
                "settings.index.row.downloads" to "DOWNLOADS",
                "settings.index.row.saved" to "SAVED",
                "settings.index.row.backup" to "BACKUP",
            ),
            content().rows.map { it.key to it.target.name },
        )
    }

    @Test
    fun rowsSitInTheCanonsTwoGroups() {
        assertEquals(
            listOf(
                SettingsIndexGroup.USE,
                SettingsIndexGroup.USE,
                SettingsIndexGroup.USE,
                SettingsIndexGroup.USE,
                SettingsIndexGroup.USE,
                SettingsIndexGroup.DATA_AND_PRIVACY,
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

    @Test
    fun newRowsOpenTheirExistingScreens() {
        val map = File("src/main/java/nl/r1a/grassmaps/app/MapScreen.kt").readText()
        val index = map.indexOf("SettingsIndexHost(")
        assertTrue("The map composes the Settings index", index >= 0)
        for ((target, show, host) in listOf(
            Triple("TRACKS", "TrackLibraryScreen.show()", "TrackLibraryHost("),
            Triple("PLUGINS", "PluginManagerScreenSession.show()", "PluginManagerScreen("),
            Triple("BACKUP", "BackupScreenSession.show()", "BackupScreenHost("),
        )) {
            assertTrue("MapScreen wires $target", map.contains("SettingsIndexTarget.$target -> $show"))
            assertTrue("$target composes after the index", map.indexOf(host, index) > index)
        }
    }

    @Test
    fun backChevronMatchesRowChevronAndRowsMeetTouchTarget() {
        val host = File("src/main/java/nl/r1a/grassmaps/app/SettingsIndexHost.kt").readText()
        assertEquals("one shared chevron size", 2, Regex("CHEVRON_SP\\.sp").findAll(host).count())
        assertEquals("back and title share a baseline", 2, Regex("alignByBaseline\\(\\)").findAll(host).count())
        assertFalse("no second back size", host.contains("BACK_SP"))
        assertEquals("48", Regex("ROW_MIN_DP = (\\d+)").find(host)?.groupValues?.get(1))
        assertTrue("rows use the minimum height", host.contains("heightIn(min = ROW_MIN_DP.dp)"))
    }
}
