package nl.r1a.grassmaps.plugins.downloads.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The downloads screen's strings land in both bundles (T-0504). The gate's
 * translation check covers the app; this keeps the module's own two files in
 * step while it is built on its own.
 */
class DownloadsUiStringsTest {
    @Test
    fun englishAndUkrainianCarryTheSameKeys() {
        val english = resources(ENGLISH)
        val ukrainian = resources(UKRAINIAN)

        assertEquals(english.keys, ukrainian.keys)
        for ((key, value) in english + ukrainian) {
            assertTrue("$key is blank", value.isNotBlank())
        }
    }

    /**
     * The sentences the done-when names: the data date, the 30 m elevation row,
     * the token request and the rejected token all have a string in both
     * languages.
     */
    @Test
    fun theNamedSentencesExistInBothLanguages() {
        for (path in listOf(ENGLISH, UKRAINIAN)) {
            val bundle = resources(path)
            for (key in REQUIRED) {
                assertTrue("$key is missing from $path", key in bundle)
            }
        }
    }

    /**
     * The plugin name is the plural of the glossary's `мапа`, not `карта`
     * (T-0634).
     */
    @Test
    fun thePluginNameUsesTheGlossaryTerm() {
        val value = resources(UKRAINIAN).getValue("plugin.downloads_ui.name")

        assertTrue("plugin name: $value", "Мапи" in value)
        assertFalse("plugin name: $value", "Карти" in value)
    }

    @Test
    fun theServerSeesSentenceNamesWhatLeavesThePhone() {
        val english = resources(ENGLISH).getValue("downloads_server_sees")

        assertTrue(english.contains("token"))
        assertTrue(english.contains("IP address"))
        assertTrue(english.contains("which maps"))
    }

    /**
     * Only the elevation reader waits for the next start: the map, the search
     * index and the routing engine all pick up an installed pack in this
     * process. The hint must not promise a restart for them (T-0626).
     */
    @Test
    fun theNextStartSentenceNamesOnlyTheElevationReader() {
        for (path in listOf(ENGLISH, UKRAINIAN)) {
            val sentence = resources(path).getValue("downloads_applies_at_next_start")

            assertFalse("$path names search: $sentence", sentence.contains("Search", ignoreCase = true))
            assertFalse("$path names search: $sentence", sentence.contains("Пошук", ignoreCase = true))
            assertFalse("$path names routing: $sentence", sentence.contains("routing", ignoreCase = true))
            assertFalse("$path names routing: $sentence", sentence.contains("аршрутизац"))
            assertTrue(
                "$path names elevation: $sentence",
                sentence.contains("Elevation", ignoreCase = true) || sentence.contains("Висоти"),
            )
        }
    }

    @Test
    fun theScreenTitleIsDrawnOnceAndTheSwitchNamesItsOwnAction() {
        val screen = File("src/main/java/nl/r1a/grassmaps/plugins/downloads/ui/DownloadsScreenContent.kt").readText()

        assertEquals(1, Regex("""stringResource\(R\.string\.downloads_title\)""").findAll(screen).count())
        assertTrue("the switch still reuses the screen title", screen.contains("label = stringResource(R.string.downloads_switch_label)"))
        for (path in listOf(ENGLISH, UKRAINIAN)) {
            val value = resources(path).getValue("downloads_switch_label")
            assertTrue("$path: the switch label is blank", value.isNotBlank())
            assertFalse("$path: the switch repeats the screen title", value == resources(path).getValue("downloads_title"))
        }
    }

    @Test
    fun theMapOverviewIsNotAButton() {
        for (path in listOf(ENGLISH, UKRAINIAN)) {
            val description = resources(path).getValue("downloads_map_description")
            assertTrue("$path: the map has no description", description.isNotBlank())
            assertFalse(
                "$path: the map asks for a per-region tap",
                description.contains("Tap", true) || description.contains("Торкніться", true),
            )
            assertTrue(
                "$path: the map does not name the list",
                description.contains("list") || description.contains("списку"),
            )
        }
    }

    private fun resources(path: String): Map<String, String> =
        Regex("""<string name="([^"]+)">([^<]*)</string>""")
            .findAll(File(path).readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private companion object {
        const val ENGLISH = "src/main/res/values/strings.xml"
        const val UKRAINIAN = "src/main/res/values-uk/strings.xml"

        val REQUIRED =
            setOf(
                "plugin.downloads_ui.name",
                "plugin.downloads_ui.description",
                "downloads_title",
                "downloads_switch_label",
                "downloads_first_run_title",
                "downloads_world_overview",
                "downloads_region_detail",
                "downloads_routing_for",
                "downloads_elevation_30m_for",
                "downloads_size_and_date",
                "downloads_download",
                "downloads_cancel",
                "downloads_remove_region",
                "downloads_up_to_date",
                "downloads_token_required",
                "downloads_token_rejected",
                "downloads_token_field",
                "downloads_part_contours",
                "downloads_part_routing",
                "downloads_part_elevation_30m",
            )
    }
}
