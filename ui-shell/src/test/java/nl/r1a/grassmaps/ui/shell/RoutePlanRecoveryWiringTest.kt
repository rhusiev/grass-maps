package nl.r1a.grassmaps.ui.shell

import org.junit.Assert.assertEquals
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
            "Open Settings > Map downloads to install the missing map",
            english.resolve(GUIDANCE_KEY),
        )
        assertEquals(
            "Відкрийте Налаштування > Завантаження карт, щоб встановити відсутню карту",
            ukrainian.resolve(GUIDANCE_KEY),
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

    private companion object {
        const val PANEL_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/RoutePlanPanel.kt"
        const val APP_ENGLISH = "../app/src/main/res/values/strings.xml"
        const val APP_UKRAINIAN = "../app/src/main/res/values-uk/strings.xml"
        const val GUIDANCE_KEY = "route_planner_open_downloads"
    }
}
