package nl.r1a.grassmaps.plugins.downloads.ui

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import nl.r1a.grassmaps.plugin.api.contract.CommandId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-0612: the Downloads refresh ("Update all") and token settings actions must
 * sit in the bottom bar beside Search regions, not as buttons at the top right
 * (design-aware review section 9). DownloadsActionBar is a private Composable
 * and the JVM tests have no Compose runtime, so its Modifier cannot be rendered
 * here. These tests pin the named height constant and the wiring from source,
 * which is weaker than a rendered assertion and is recorded as such.
 */
class DownloadsRefreshActionBarTest {
    @Test
    fun refreshAndTokenSettingsAreDrawnInTheBottomBarNotTheHeader() {
        val source = File(SCREEN_FILE).readText()
        val barStart = source.indexOf("private fun DownloadsActionBar(")
        assertTrue("the bottom bar is missing", barStart >= 0)

        val updateAll = source.indexOf("R.string.downloads_update_all")
        val tokenSettings = source.indexOf("R.string.downloads_token_settings")
        assertTrue("Update all is no longer drawn", updateAll >= 0)
        assertTrue("Token settings is no longer drawn", tokenSettings >= 0)
        assertTrue("Update all is still in the header, not the bottom bar", updateAll > barStart)
        assertTrue("Token settings is still in the header, not the bottom bar", tokenSettings > barStart)
        assertTrue("the bottom bar lost Search regions", source.substring(barStart).contains("SearchField("))
    }

    @Test
    fun theBarActionsUseTheExistingCallbacksAndAddNoCommand() {
        val source = File(SCREEN_FILE).readText()
        // The screen keeps the two existing callbacks; no new command is added.
        assertTrue(source.contains("onUpdateAll: () -> Unit,"))
        assertTrue(source.contains("onTokenSettings: () -> Unit,"))
        val bar = source.substringAfter("private fun DownloadsActionBar(")
        assertTrue("the bar refresh does not call the existing onUpdateAll", bar.contains("onClick = onUpdateAll"))
        assertTrue("the bar does not receive the token settings callback", bar.contains("onTokenSettings: () -> Unit"))
    }

    @Test
    fun theBarActionsKeepTheirTapTargetFromTheNamedConstant() {
        val source = File(SCREEN_FILE).readText()
        assertTrue(
            "the named 48 dp action constant is missing",
            source.contains("private const val ACTION_MIN_HEIGHT_DP = 48"),
        )
        assertTrue(
            "the actions no longer use the named height constant",
            source.contains("heightIn(min = ACTION_MIN_HEIGHT_DP.dp)"),
        )
    }

    @Test
    fun theUpdateAllPathStillRefreshesTheCatalogueFirst() =
        runBlocking {
            val access = FakeDownloadsAccess()
            val context = RecordingContext(FakeServices(access))
            DownloadsUiPlugin().activate(context)
            access.useCatalog(CatalogTestData.real())

            context.command(DownloadsUiContract.updateAll).invokeRaw(JsonObject(emptyMap()))

            assertEquals("refresh", access.calls.first())
        }

    private fun RecordingContext.command(id: CommandId) = commands.single { it.id == id }

    private companion object {
        const val SCREEN_FILE = "src/main/java/nl/r1a/grassmaps/plugins/downloads/ui/DownloadsScreenContent.kt"
    }
}
