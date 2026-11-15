package nl.r1a.grassmap.plugins.downloads.ui

import org.junit.Assert.assertEquals
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

    @Test
    fun theServerSeesSentenceNamesWhatLeavesThePhone() {
        val english = resources(ENGLISH).getValue("downloads_server_sees")

        assertTrue(english.contains("token"))
        assertTrue(english.contains("IP address"))
        assertTrue(english.contains("which maps"))
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
