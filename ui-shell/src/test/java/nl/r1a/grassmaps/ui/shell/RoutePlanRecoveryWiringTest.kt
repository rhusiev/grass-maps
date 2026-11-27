package nl.r1a.grassmaps.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-0612 final-review follow-up: a download button on a bare pack failure is
 * worse than actionless text, because the planner rejects the command without
 * a missing-region prompt. The button needs a usable prompt; a pack failure
 * without one names the exact screen instead. A JVM test cannot render the
 * panel, so the wiring is source-checked and the copy is read from both
 * string catalogues.
 */
class RoutePlanRecoveryWiringTest {
    private val failed by lazy {
        File(PANEL_FILE)
            .readText()
            .substringAfter("RoutePlanStatus.Failed -> {")
            .substringBefore("RoutePlanStatus.Ready ->")
    }

    @Test
    fun aBarePackFailureDoesNotDrawTheDownloadButton() {
        assertTrue(
            "the download button is not gated on a prompt the planner can act on",
            failed.contains("state.missing != null"),
        )
    }

    @Test
    fun aBarePackFailureNamesTheDownloadsScreenInBothLocales() {
        val english = TestStringCatalog.read(APP_ENGLISH).resolver()
        val ukrainian = TestStringCatalog.read(APP_UKRAINIAN).resolver()

        assertEquals(
            "Open Map downloads to install the missing map",
            english.resolve(GUIDANCE_KEY),
        )
        assertEquals(
            "Відкрийте Завантаження карт, щоб встановити відсутню карту",
            ukrainian.resolve(GUIDANCE_KEY),
        )
        assertFalse(
            "the recovery copy still sends the reader through Settings",
            english.resolve(GUIDANCE_KEY).contains("Settings") ||
                ukrainian.resolve(GUIDANCE_KEY).contains("Налаштування"),
        )
    }

    @Test
    fun aPromptStillDrawsTheDownloadButton() {
        assertTrue(
            "a usable prompt lost the existing download-and-route action",
            failed.contains("FailureNextStep.DownloadAndRoute -> DownloadAndRouteButton(strings, onDownloadMissing)"),
        )
    }

    @Test
    fun engineNoRouteAndTooFarStayTextOnly() {
        assertTrue(
            "a failure the download cannot fix still gets an action",
            failed.contains("FailureNextStep.TextOnly -> Unit"),
        )
    }

    /**
     * Final blind review round 6 P2-5: naming Map downloads is not enough. The
     * no-prompt pack failure must offer the same navigation the Settings index
     * Map downloads row offers, as a full-height button, never the download
     * command that the planner rejects without a region prompt.
     */
    @Test
    fun aBarePackFailureDrawsARealOpenDownloadsButton() {
        assertTrue(
            "the bare pack failure still names Map downloads as plain text",
            failed.contains("FailureNextStep.OpenDownloads -> OpenDownloadsButton(strings, onOpenDownloads)"),
        )
    }

    @Test
    fun theOpenDownloadsButtonKeepsThePanels48DpTouchTarget() {
        val panel = File(PANEL_FILE).readText()
        assertTrue("the panel has no Open downloads button", panel.contains("fun OpenDownloadsButton("))
        val button = panel.substringAfter("fun OpenDownloadsButton(")
        assertTrue(
            "the Open downloads button is under 48 dp",
            button.substringBefore("\n}").contains("CONTROL_TARGET_DP.dp"),
        )
    }

    /**
     * Final blind review 8 P2-1: the button rendered 45.7 dp at 420 dpi. It
     * needs the 48 dp minimum in both dimensions, and its height must grow
     * with the label: an exact height clipped the wrapped label at font
     * scale 2.0. Rendered size is checked on the AVD, not by this test.
     */
    @Test
    fun theOpenDownloadsButtonHasA48DpMinimumThatGrowsWithItsLabel() {
        val panel = File(PANEL_FILE).readText()
        val button = panel.substringAfter("fun OpenDownloadsButton(").substringBefore("\n}")
        assertTrue(
            "the recovery button lost its 48 dp minimum",
            Regex("""sizeIn\(\s*minWidth\s*=\s*CONTROL_TARGET_DP\.dp,\s*minHeight\s*=\s*CONTROL_TARGET_DP\.dp\s*\)""")
                .containsMatchIn(button),
        )
        assertTrue(
            "a fixed height clips the wrapped label at large font scales",
            !Regex("""\.height\(""").containsMatchIn(button),
        )
    }

    @Test
    fun theRecoveryButtonOpensTheDownloadsScreenTheSettingsRowOpens() {
        val plannerCall = File(MAP_FILE).readText().substringAfter("RoutePlanPanel(")
        assertTrue(
            "the panel is not given the downloads callback",
            plannerCall.substringBefore("onDownloadMissing =").contains("onOpenDownloads = {"),
        )
        assertTrue(
            "the downloads callback does not open the Map downloads screen",
            plannerCall.substringAfter("onOpenDownloads = {").substringBefore("},").contains("DownloadsScreenSession.show()"),
        )
    }

    private companion object {
        const val PANEL_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/RoutePlanPanel.kt"
        const val MAP_FILE = "../app/src/main/java/nl/r1a/grassmaps/app/MapScreen.kt"
        const val APP_ENGLISH = "../app/src/main/res/values/strings.xml"
        const val APP_UKRAINIAN = "../app/src/main/res/values-uk/strings.xml"
        const val GUIDANCE_KEY = "route_planner_open_downloads"
    }
}
