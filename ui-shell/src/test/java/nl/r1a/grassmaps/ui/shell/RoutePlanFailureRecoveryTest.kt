package nl.r1a.grassmaps.ui.shell

import nl.r1a.grassmaps.plugin.api.model.RoutePlanFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-0612 final-review defect P1-3: pack-related planner failures must have a
 * next step that works. The download button needs a missing-region prompt;
 * without one the panel names Map downloads instead of showing a no-op.
 */
class RoutePlanFailureRecoveryTest {
    @Test
    fun packMissingWithAPromptOffersTheDownloadNextStep() {
        assertEquals(
            FailureNextStep.DownloadAndRoute,
            failureNextStep(RoutePlanFailure.PackMissing, hasMissingPrompt = true),
        )
    }

    @Test
    fun packUnusableWithAPromptOffersTheDownloadNextStep() {
        assertEquals(
            FailureNextStep.DownloadAndRoute,
            failureNextStep(RoutePlanFailure.PackUnusable, hasMissingPrompt = true),
        )
    }

    @Test
    fun regionsMissingWithAPromptOffersTheDownloadNextStep() {
        assertEquals(
            FailureNextStep.DownloadAndRoute,
            failureNextStep(RoutePlanFailure.RegionsMissing, hasMissingPrompt = true),
        )
    }

    @Test
    fun barePackMissingNamesTheDownloadsScreen() {
        assertEquals(
            FailureNextStep.OpenDownloads,
            failureNextStep(RoutePlanFailure.PackMissing, hasMissingPrompt = false),
        )
    }

    @Test
    fun barePackUnusableNamesTheDownloadsScreen() {
        assertEquals(
            FailureNextStep.OpenDownloads,
            failureNextStep(RoutePlanFailure.PackUnusable, hasMissingPrompt = false),
        )
    }

    @Test
    fun engineUnavailableKeepsHonestTextWithoutADownload() {
        assertEquals(
            FailureNextStep.TextOnly,
            failureNextStep(RoutePlanFailure.EngineUnavailable, hasMissingPrompt = true),
        )
    }

    @Test
    fun noRouteKeepsHonestTextWithoutADownload() {
        assertEquals(
            FailureNextStep.TextOnly,
            failureNextStep(RoutePlanFailure.NoRoute, hasMissingPrompt = true),
        )
    }

    @Test
    fun tooFarKeepsHonestTextWithoutADownload() {
        assertEquals(
            FailureNextStep.TextOnly,
            failureNextStep(RoutePlanFailure.TooFar, hasMissingPrompt = true),
        )
    }

    @Test
    fun theRecoveryButtonReusesTheMissingRegionsDownloadAction() {
        val panel = File(PANEL_FILE).readText()
        val card = File(CARD_FILE).readText()

        assertTrue(
            "the failure panel does not draw a recovery button",
            panel.contains("DownloadAndRouteButton(strings, onDownloadMissing)"),
        )
        assertTrue(
            "the missing-regions card does not reuse the same button",
            card.contains("DownloadAndRouteButton(strings, onDownload)"),
        )
        assertTrue(
            "the shared button is not wired to the planner's download callback",
            panel.contains("onDownloadMissing: () -> Unit") &&
                panel.contains("DownloadAndRouteButton(strings, onDownloadMissing)"),
        )
        val map = File(MAP_FILE).readText()
        assertTrue(
            "the download callback is not the planner's existing download command",
            map.substringAfter("onDownloadMissing = {").substringBefore("},").contains("RoutePlannerContract.downloadMissing"),
        )
    }

    private companion object {
        const val PANEL_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/RoutePlanPanel.kt"
        const val CARD_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/MissingRegionsCard.kt"
        const val MAP_FILE = "../app/src/main/java/nl/r1a/grassmaps/app/MapScreen.kt"
    }
}
