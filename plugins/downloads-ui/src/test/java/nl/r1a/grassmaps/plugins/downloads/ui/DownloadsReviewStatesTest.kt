package nl.r1a.grassmaps.plugins.downloads.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Local copy and Compose wiring checks. The module's JVM suite does not render Compose. */
class DownloadsReviewStatesTest {
    private val screen = File("src/main/java/nl/r1a/grassmaps/plugins/downloads/ui/DownloadsScreenContent.kt").readText()

    @Test
    fun theTitleMatchesTheMapDownloadsEntryInBothLocales() {
        assertEquals("Map downloads", label("values", "downloads_title"))
        assertEquals("Завантаження карт", label("values-uk", "downloads_title"))
    }

    @Test
    fun theOffStateUsesTheSwitchWithoutADuplicateNoticeOrAction() {
        assertTrue(screen.contains("onCheckedChange = onSetDownloadsEnabled"))
        assertFalse("The Off card duplicates the switch", screen.contains("DownloadsOffAlertCard("))
        assertFalse("The switch already turns downloads on", screen.contains("R.string.downloads_switch_turn_on"))
    }

    @Test
    fun aCatalogNetworkFailureDoesNotClaimADownloadStoppedOrOfferRetry() {
        assertTrue(screen.contains("CatalogStatus(state)"))
        val status = screen.substringAfter("private fun CatalogStatus(").substringBefore("\n}")
        assertTrue(status.contains("DownloadsProblemText.catalogKey(state)"))
        assertFalse("A catalog error must not use transfer failure copy", status.contains("ProblemLine("))
        assertFalse(status.contains("ActionButton("))
        assertEquals("No connection", label("values", "downloads_no_connection"))
        assertEquals("Немає з’єднання", label("values-uk", "downloads_no_connection"))
    }

    @Test
    fun anUnloadedCatalogIsNotAnEmptySearchResult() {
        val search = screen.substringAfter("if (state.query.isNotBlank() && state.catalogLoaded) {").substringBefore("            } else {")
        assertTrue(search.contains("if (state.searchResults.isEmpty())"))
        assertTrue(search.contains("R.string.downloads_search_empty"))
        assertEquals("No connection. Connect to load regions", label("values", "downloads_catalog_offline"))
        assertEquals(
            "Немає з’єднання. Підключіться, щоб завантажити список областей",
            label("values-uk", "downloads_catalog_offline"),
        )
    }

    @Test
    fun settingsShowCheckedSwitchesAndUpdateAllRequiresDownloadsOn() {
        val settings = screen.substringAfter("private fun MobileDataCard(").substringBefore("\n}")
        assertTrue(settings.contains("checked = wifiOnly"))
        assertTrue(settings.contains("checked = state.autoUpdate"))
        assertTrue(settings.contains("onCheckedChange = { onSetMobileData(!it) }"))
        assertTrue(settings.contains("onCheckedChange = onSetAutoUpdate"))
        val updateAll = screen.substringAfter("text = stringResource(R.string.downloads_update_all)").substringBefore("\n            )")
        assertTrue(updateAll.contains("enabled = state.downloadsEnabled"))
        assertTrue(screen.contains(".clickable(enabled = enabled, role = Role.Button, onClick = onClick)"))
    }

    @Test
    fun theBackButtonAndSwitchRowsHaveAnExplicitMinimumTouchSize() {
        val back = screen.substringAfter("private fun DownloadsBackButton(").substringBefore("\n}")
        assertTrue(back.contains("sizeIn(minWidth = ACTION_MIN_HEIGHT_DP.dp, minHeight = ACTION_MIN_HEIGHT_DP.dp)"))
        assertTrue(back.contains(".clickable(role = Role.Button, onClick = onClose)"))
        val track = screen.substringAfter("private fun SettingsSwitch(").substringBefore("\n}")
        assertTrue(track.contains("heightIn(min = SWITCH_ROW_HEIGHT_DP.dp)"))
        assertTrue(track.contains(".toggleable(value = checked, role = Role.Switch"))
    }

    private fun label(
        locale: String,
        key: String,
    ): String? =
        File("src/main/res/$locale/strings.xml")
            .readLines()
            .firstOrNull { it.contains("""name="$key"""") }
            ?.substringAfter('>')
            ?.substringBefore("</string>")
}
