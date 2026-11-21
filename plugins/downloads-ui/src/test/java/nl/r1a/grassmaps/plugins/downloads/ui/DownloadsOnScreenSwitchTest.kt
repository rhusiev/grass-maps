package nl.r1a.grassmaps.plugins.downloads.ui

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmaps.plugin.api.command.CommandResult
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.model.DownloadsData
import nl.r1a.grassmaps.plugin.api.model.DownloadsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The on-screen downloads switch (blind review section 7, Top 10 item 1).
 *
 * A store user on a fresh install must see a switch in the off state and be
 * able to turn it on where they are. The test drives the plugin's command with
 * the typed store fake, so the privacy write is checked without a device.
 */
class DownloadsOnScreenSwitchTest {
    @Test
    fun theFreshStatePublishesTheSwitchInTheOffState() {
        val state = DownloadsScreen.state(DownloadsData(serverUrl = SERVER))

        assertFalse(state.downloadsEnabled)
        val notice = downloadsSwitchNotice(LABELS, enabled = state.downloadsEnabled)
        assertFalse(notice.checked)
        assertEquals("Off", notice.stateText)
        assertEquals("Map downloads", notice.title)
        assertNotNull(notice.alert)
    }

    @Test
    fun theOffStateAlertHasADirectActionAndTheSwitchKeepsTheHonestPrivacyNote() {
        val notice = downloadsSwitchNotice(LABELS, enabled = false)

        assertNotNull(notice.alert)
        assertEquals("Downloads are switched off", notice.alert!!.message)
        assertEquals("Turn on downloads", notice.alert.action)
        assertTrue(notice.privacyNote.contains("IP address"))
        assertTrue(notice.privacyNote.contains("which maps"))
    }

    @Test
    fun turningTheSwitchOnWritesTheExistingStoreAndPublishesTheNewState() =
        runBlocking {
            val access = FakeDownloadsAccess()
            val context = context(access)
            DownloadsUiPlugin().activate(context)

            val state = stateOf(context.command(DownloadsUiContract.setDownloadsEnabled).invokeRaw(json("enabled" to "true")))

            assertEquals(listOf("refresh", "switch:true", "refresh"), access.calls)
            assertTrue(state.downloadsEnabled)
            assertNull(downloadsSwitchNotice(LABELS, enabled = state.downloadsEnabled).alert)
        }

    @Test
    fun aFailedSwitchWriteReturnsATypedFailureAndKeepsTheState() =
        runBlocking {
            val access = FakeDownloadsAccess()
            access.switchWriteFails = true
            val context = context(access)
            DownloadsUiPlugin().activate(context)

            val outcome = context.command(DownloadsUiContract.setDownloadsEnabled).invokeRaw(json("enabled" to "true"))

            assertEquals(Outcome.Failure(AppError.NotFound), outcome)
            assertFalse(access.downloadsEnabled)
            assertEquals("switch:true", access.calls.last())
        }

    /**
     * The strings the switch draws land in both bundles. The privacy sentence is
     * shared with the token form, so a change there has to face this test too.
     */
    @Test
    fun theSwitchWordingLandsInBothLocales() {
        for (path in listOf(ENGLISH, UKRAINIAN)) {
            val bundle = resources(path)
            assertTrue(bundle.getValue("downloads_switch_title").isNotBlank())
            assertTrue(bundle.getValue("downloads_switch_off").isNotBlank())
            assertTrue(bundle.getValue("downloads_switch_turn_on").isNotBlank())
            assertTrue(
                bundle.getValue("downloads_server_sees").contains("IP address") ||
                    bundle.getValue("downloads_server_sees").contains("IP-адрес"),
            )
        }
    }

    private fun context(access: FakeDownloadsAccess) = RecordingContext(FakeServices(access))

    private fun RecordingContext.command(id: nl.r1a.grassmaps.plugin.api.contract.CommandId) = commands.single { it.id == id }

    @Suppress("UNCHECKED_CAST")
    private fun stateOf(outcome: Outcome<CommandResult<JsonElement>>): DownloadsState =
        when (outcome) {
            is Outcome.Success -> Json.decodeFromJsonElement(DownloadsState.serializer(), outcome.value.value)
            is Outcome.Failure -> throw AssertionError("command failed: ${outcome.error}")
        }

    private fun json(vararg pairs: Pair<String, String>): JsonObject =
        buildJsonObject {
            pairs.forEach { (key, value) -> put(key, value) }
        }

    private fun resources(path: String): Map<String, String> =
        Regex("""<string name="([^"]+)">([^<]*)</string>""")
            .findAll(File(path).readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private companion object {
        const val SERVER = "https://maps.test"
        const val ENGLISH = "src/main/res/values/strings.xml"
        const val UKRAINIAN = "src/main/res/values-uk/strings.xml"

        val LABELS =
            DownloadsSwitchLabels(
                title = "Map downloads",
                onText = "On",
                offText = "Off",
                privacyNote = "The server sees your token, your IP address and which maps you fetch.",
                offMessage = "Downloads are switched off",
                turnOnAction = "Turn on downloads",
            )
    }
}
