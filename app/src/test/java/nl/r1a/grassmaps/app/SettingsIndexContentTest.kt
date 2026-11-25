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
        assertTrue("The default map must expose Settings", map.contains("SettingsIndexSession.show()"))
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
        assertEquals("no baseline alignment in the header", 0, Regex("alignByBaseline\\(\\)").findAll(host).count())
        assertTrue(
            "the header centers back and title vertically",
            host.contains("verticalAlignment = Alignment.CenterVertically"),
        )
        assertFalse("no second back size", host.contains("BACK_SP"))
        assertEquals("48", Regex("ROW_MIN_DP = (\\d+)").find(host)?.groupValues?.get(1))
        assertTrue("rows use the minimum height", host.contains("heightIn(min = ROW_MIN_DP.dp)"))
    }

    @Test
    fun filteringMatchesResolvedTitlesInEnglishAndUkrainian() {
        val english = settingsIndexContent(localizedStrings(english = true))
        val ukrainian = settingsIndexContent(localizedStrings(english = false))

        assertEquals(listOf("Profiles"), english.filtered("profiles").rows.map { it.title })
        assertEquals(listOf("Map downloads"), english.filtered("DOWNLOAD").rows.map { it.title })
        assertEquals(listOf("Профілі"), ukrainian.filtered("ПРОФІ").rows.map { it.title })
        assertEquals(listOf("Завантаження карт"), ukrainian.filtered("завантаження").rows.map { it.title })
    }

    @Test
    fun filteringKeepsTheRowsDestinationGroupAndTitle() {
        val content = settingsIndexContent(localizedStrings(english = true))
        for (query in listOf("profiles", "saved", "map")) {
            val filtered = content.filtered(query).rows
            assertTrue("query $query must match", filtered.isNotEmpty())
            for (row in filtered) {
                val original = content.rows.single { it.key == row.key }
                assertEquals(original, row)
            }
        }
    }

    @Test
    fun emptyStateIsResolvedWhenNoRowMatches() {
        val english = settingsIndexContent(localizedStrings(english = true)).filtered("zzzz-no-match")
        assertTrue("no row may remain", english.rows.isEmpty())
        assertEquals("Search settings", english.search)
        assertEquals("No settings match your search", english.empty)

        val ukrainian = settingsIndexContent(localizedStrings(english = false)).filtered("zzzz-no-match")
        assertTrue("no row may remain in UK", ukrainian.rows.isEmpty())
        assertEquals("Пошук налаштувань", ukrainian.search)
        assertEquals("За вашим запитом налаштувань не знайдено", ukrainian.empty)
    }

    @Test
    fun hostDrawsAFilteredSearchFieldAndTheEmptyState() {
        val host = File("src/main/java/nl/r1a/grassmaps/app/SettingsIndexHost.kt").readText()
        assertTrue("the host holds a query", host.contains("var query by remember { mutableStateOf(\"\") }"))
        assertTrue("the host filters the rows", host.contains("content.filtered(query)"))
        assertTrue("the host draws a text field", host.contains("BasicTextField("))
        assertTrue("the field edits the query", host.contains("value = query"))
        assertTrue("the field edits the query", host.contains("onValueChange = onQueryChange"))
        assertTrue("the host draws the search label", host.contains("content.search"))
        assertTrue("the host draws the empty state", host.contains("content.empty"))
        assertTrue("an empty list hides the group headers", host.contains("visible.rows.isEmpty()"))
        assertEquals("the field and the rows keep 48 dp targets", 2, Regex("heightIn\\(min = ROW_MIN_DP\\.dp\\)").findAll(host).count())
        assertTrue("the back glyph target is kept", host.contains(".size(BACK_DP.dp)"))
        assertTrue("the back glyph role is kept", host.contains("role = Role.Button"))
    }

    @Test
    fun staleTrayClaimIsRemovedAndMissingScreensAreRecorded() {
        val source = File("src/main/java/nl/r1a/grassmaps/app/SettingsIndexContent.kt").readText()
        assertFalse("the stale tray claim is gone", source.contains("widgets and Saved tray"))
        assertFalse("the stale missing-tray claim is gone", source.contains("until the map bar has"))
        for (name in listOf("Units", "Gestures", "Privacy", "Storage", "About")) {
            assertTrue("$name blocker is recorded", source.contains(name))
        }
        val kdoc = source.replace(Regex("""\s*\*\s*"""), " ")
        assertTrue("the missing screens are named as absent", kdoc.contains("no screen in this build"))
    }

    private fun localizedStrings(english: Boolean): Strings {
        val titles =
            mapOf(
                "settings.index.row.profiles" to if (english) "Profiles" else "Профілі",
                "settings.index.row.language" to if (english) "Language" else "Мова",
                "settings.index.row.appearance" to if (english) "Appearance" else "Вигляд",
                "settings.index.row.tracks" to if (english) "Tracks" else "Треки",
                "settings.index.row.plugins" to if (english) "Plugins" else "Плагіни",
                "settings.index.row.downloads" to if (english) "Map downloads" else "Завантаження карт",
                "settings.index.row.saved" to if (english) "Saved" else "Збережене",
                "settings.index.row.backup" to if (english) "Backup" else "Резервна копія",
                "settings.index.search" to if (english) "Search settings" else "Пошук налаштувань",
                "settings.index.search.empty" to
                    if (english) "No settings match your search" else "За вашим запитом налаштувань не знайдено",
            )
        return object : Strings {
            override fun resolve(
                key: String,
                arguments: List<String>,
            ): String = titles[key] ?: "[$key]"

            override fun contains(key: String): Boolean = true
        }
    }
}
