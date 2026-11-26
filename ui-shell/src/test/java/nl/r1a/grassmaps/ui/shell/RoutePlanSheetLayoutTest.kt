package nl.r1a.grassmaps.ui.shell

import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.model.MissingRegions
import nl.r1a.grassmaps.plugin.api.model.RoutePlanFailure
import nl.r1a.grassmaps.plugin.api.model.RoutePlanOption
import nl.r1a.grassmaps.plugin.api.model.RoutePlanPoint
import nl.r1a.grassmaps.plugin.api.model.RoutePlanState
import nl.r1a.grassmaps.plugin.api.model.RoutePlanStatus
import nl.r1a.grassmaps.ui.shell.RoutePlanBarAction.Apply
import nl.r1a.grassmaps.ui.shell.RoutePlanBarAction.Done
import nl.r1a.grassmaps.ui.shell.RoutePlanBarAction.HideDetails
import nl.r1a.grassmaps.ui.shell.RoutePlanBarAction.Reset
import nl.r1a.grassmaps.ui.shell.RoutePlanSection.Alternatives
import nl.r1a.grassmaps.ui.shell.RoutePlanSection.Chart
import nl.r1a.grassmaps.ui.shell.RoutePlanSection.EditableHeader
import nl.r1a.grassmaps.ui.shell.RoutePlanSection.ModeChips
import nl.r1a.grassmaps.ui.shell.RoutePlanSection.Preferences
import nl.r1a.grassmaps.ui.shell.RoutePlanSection.PreferencesEntry
import nl.r1a.grassmaps.ui.shell.RoutePlanSection.RecentRoutes
import nl.r1a.grassmaps.ui.shell.RoutePlanSection.Status
import nl.r1a.grassmaps.ui.shell.RoutePlanSection.StopList
import nl.r1a.grassmaps.ui.shell.RoutePlanSection.Turns
import nl.r1a.grassmaps.ui.shell.RoutePlanSection.Warnings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import nl.r1a.grassmaps.ui.shell.RoutePlanBarAction.Details as DetailsAction
import nl.r1a.grassmaps.ui.shell.RoutePlanBarAction.Start as StartAction
import nl.r1a.grassmaps.ui.shell.RoutePlanBarAction.Stops as StopsAction
import nl.r1a.grassmaps.ui.shell.RoutePlanSection.Summary as SummarySection

/**
 * Round 9 reviews (T-0612): the ready route preview is the round 8
 * `x-route-preview` sheet. The scrolling sheet holds the mode chips, the
 * summary, the alternatives, the warnings row and the Route preferences row;
 * the pinned bar at the bottom holds Stops, Details and Start. The JVM suite
 * cannot render the sheet, so this pins the layout model the Composable
 * iterates, and the source checks below pin that it does iterate it.
 */
class RoutePlanSheetLayoutTest {
    @Test
    fun theReadyPreviewShowsEveryPreviewSectionAndPinsStopsDetailsStart() {
        val layout = routePlanSheetLayout(RoutePlanSheetView.Summary, READY)

        assertEquals(listOf(ModeChips, SummarySection, Alternatives, Warnings, PreferencesEntry), layout.sections)
        assertEquals(listOf(StopsAction, DetailsAction, StartAction), layout.bar)
    }

    @Test
    fun detailsShowsTheSummaryChartAndTurnsAndPinsHideDetailsStart() {
        val layout = routePlanSheetLayout(RoutePlanSheetView.Details, READY)

        assertEquals(listOf(SummarySection, Chart, Turns), layout.sections)
        assertEquals(listOf(HideDetails, StartAction), layout.bar)
    }

    @Test
    fun stopsShowsTheEditorAndPinsDone() {
        val layout = routePlanSheetLayout(RoutePlanSheetView.Stops, READY)

        assertEquals(listOf(EditableHeader, StopList), layout.sections)
        assertEquals(listOf(Done), layout.bar)
    }

    @Test
    fun stopsKeepsTheRerouteStatusUnderTheEditor() {
        val layout = routePlanSheetLayout(RoutePlanSheetView.Stops, READY.copy(status = RoutePlanStatus.Planning))

        assertEquals(listOf(EditableHeader, StopList, Status, PreferencesEntry), layout.sections)
        assertEquals(listOf(Done), layout.bar)
    }

    @Test
    fun preferencesPinResetAndApplySoTheyStayReachableAtAnyFontScale() {
        for (status in listOf(RoutePlanStatus.Ready, RoutePlanStatus.Planning, RoutePlanStatus.Failed)) {
            val layout = routePlanSheetLayout(RoutePlanSheetView.Preferences, READY.copy(status = status))

            assertEquals("$status", listOf(PreferencesEntry, Preferences), layout.sections)
            assertEquals("$status", listOf(Reset, Apply), layout.bar)
        }
    }

    @Test
    fun aPlanThatIsNotReadyKeepsItsStatusAndThePreferencesRow() {
        for (status in listOf(RoutePlanStatus.Planning, RoutePlanStatus.Failed)) {
            for (view in listOf(RoutePlanSheetView.Summary, RoutePlanSheetView.Details)) {
                val layout = routePlanSheetLayout(view, READY.copy(status = status))

                assertEquals("$status $view", listOf(Status, PreferencesEntry), layout.sections)
                assertEquals("$status $view has no route to act on", emptyList<RoutePlanBarAction>(), layout.bar)
            }
        }
    }

    @Test
    fun pickingAnEndOffersTheRecentRoutes() {
        for (status in listOf(RoutePlanStatus.PickingStart, RoutePlanStatus.PickingDestination)) {
            val layout = routePlanSheetLayout(RoutePlanSheetView.Summary, RoutePlanState(status = status))

            assertEquals("$status", listOf(Status, PreferencesEntry, RecentRoutes), layout.sections)
        }
    }

    @Test
    fun theMissingRegionsCardReplacesTheRestOfTheSheet() {
        val state =
            READY.copy(
                status = RoutePlanStatus.Failed,
                failure = RoutePlanFailure.RegionsMissing,
                missing = MissingRegions(emptyList()),
            )

        assertEquals(listOf(Status), routePlanSheetLayout(RoutePlanSheetView.Summary, state).sections)
    }

    @Test
    fun anIdlePlanComposesNothing() {
        for (view in RoutePlanSheetView.entries) {
            val layout = routePlanSheetLayout(view, RoutePlanState())

            assertEquals(emptyList<RoutePlanSection>(), layout.sections)
            assertEquals(emptyList<RoutePlanBarAction>(), layout.bar)
        }
    }

    @Test
    fun eachBarActionLeavesTheSheetInItsOwnView() {
        assertEquals(RoutePlanSheetView.Stops, StopsAction.next)
        assertEquals(RoutePlanSheetView.Details, DetailsAction.next)
        assertNull("Start hands the route to navigation", StartAction.next)
        for (action in listOf(HideDetails, Done, Reset, Apply)) {
            assertEquals("$action returns to the preview", RoutePlanSheetView.Summary, action.next)
        }
    }

    @Test
    fun backCollapsesAnOpenViewBeforeItClosesThePlan() {
        assertNull("Back on the preview closes the plan", routePlanBackView(RoutePlanSheetView.Summary))
        for (view in listOf(RoutePlanSheetView.Stops, RoutePlanSheetView.Details, RoutePlanSheetView.Preferences)) {
            assertEquals("Back on $view returns to the preview", RoutePlanSheetView.Summary, routePlanBackView(view))
        }
    }

    @Test
    fun thePanelDrawsTheLayoutInTheSharedBottomSheetWithThePinnedBarAsItsFooter() {
        val panel = panel()

        assertTrue("the panel does not reuse the shared sheet chrome", "ShellBottomSheet(" in panel)
        assertTrue("the preview sheet must leave the map usable", "scrim = false" in panel)
        assertTrue("the sheet does not iterate the layout's sections", "layout.sections.forEach" in panel)
        assertTrue(
            "the bar is not the sheet's pinned footer",
            Regex("""footer = \{\s*RoutePlanBottomBar\(\s*actions = layout\.bar""").containsMatchIn(panel),
        )
    }

    @Test
    fun theSharedSheetCanDropItsScrimAndNeverSqueezesItsFooter() {
        val sheet = File("src/main/java/nl/r1a/grassmaps/ui/shell/ShellBottomSheet.kt").readText()

        assertTrue("the shared sheet has no scrim switch", "scrim: Boolean = true" in sheet)
        assertTrue(
            "the scrolling area must yield to the footer when the sheet is full",
            ".weight(1f, fill = false)" in sheet,
        )
    }

    @Test
    fun everyBarButtonHasA48DpMinimumAndCentresItsLabel() {
        val bar = panel().substringAfter("internal fun RoutePlanBottomBar(").substringBefore("\n}\n")

        assertTrue("a bar button lost its minimum height", ".heightIn(min = BAR_BUTTON_HEIGHT_DP.dp)" in bar)
        assertTrue("a bar label is not centred", "contentAlignment = Alignment.Center" in bar)
        assertTrue("a bar button lost its role", "role = Role.Button" in bar)
        assertTrue("a long label at a large font scale must wrap the bar", "FlowRow(" in bar)
        assertTrue(
            "the bar height is below the 48 dp minimum",
            (
                Regex("""private const val BAR_BUTTON_HEIGHT_DP = (\d+)""")
                    .find(panel())
                    ?.groupValues
                    ?.get(1)
                    ?.toInt() ?: 0
            ) >= 48,
        )
    }

    private fun panel(): String = File("src/main/java/nl/r1a/grassmaps/ui/shell/RoutePlanPanel.kt").readText()

    private companion object {
        val START = GeoPoint(49.8290, 23.9520)
        val DESTINATION = GeoPoint(49.8420, 24.0320)
        val READY =
            RoutePlanState(
                status = RoutePlanStatus.Ready,
                start = RoutePlanPoint(START, "Pidzamche"),
                destination = RoutePlanPoint(DESTINATION, "Stryiskyi Park"),
                options =
                    listOf(
                        RoutePlanOption(
                            geometry = listOf(START, DESTINATION),
                            distanceMeters = 7_052.0,
                            durationSeconds = 1_482.0,
                            arrivalEpochMillis = 1_700_001_482_000L,
                        ),
                    ),
            )
    }
}
