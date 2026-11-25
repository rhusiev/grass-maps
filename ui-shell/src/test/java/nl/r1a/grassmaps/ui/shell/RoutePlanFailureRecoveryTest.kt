package nl.r1a.grassmaps.ui.shell

import nl.r1a.grassmaps.plugin.api.model.RoutePlanFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-0612 final-review defect P1-3: pack-related planner failures fall through
 * to an actionless status line. Every failure kind must declare its next step,
 * and the download step must reuse the action that already works for missing
 * regions. Engine-unavailable keeps its honest text and gets no download.
 */
class RoutePlanFailureRecoveryTest {
    @Test
    fun packMissingOffersTheDownloadNextStep() {
        assertEquals(FailureNextStep.DownloadAndRoute, failureNextStep(RoutePlanFailure.PackMissing))
    }

    @Test
    fun packUnusableOffersTheDownloadNextStep() {
        assertEquals(FailureNextStep.DownloadAndRoute, failureNextStep(RoutePlanFailure.PackUnusable))
    }

    @Test
    fun regionsMissingOffersTheDownloadNextStep() {
        assertEquals(FailureNextStep.DownloadAndRoute, failureNextStep(RoutePlanFailure.RegionsMissing))
    }

    @Test
    fun engineUnavailableKeepsHonestTextWithoutADownload() {
        assertEquals(FailureNextStep.TextOnly, failureNextStep(RoutePlanFailure.EngineUnavailable))
    }

    @Test
    fun noRouteKeepsHonestTextWithoutADownload() {
        assertEquals(FailureNextStep.TextOnly, failureNextStep(RoutePlanFailure.NoRoute))
    }

    @Test
    fun tooFarKeepsHonestTextWithoutADownload() {
        assertEquals(FailureNextStep.TextOnly, failureNextStep(RoutePlanFailure.TooFar))
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
