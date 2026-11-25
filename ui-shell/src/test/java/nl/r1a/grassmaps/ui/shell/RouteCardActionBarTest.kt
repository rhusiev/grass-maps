package nl.r1a.grassmaps.ui.shell

import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.model.RoutePlanOption
import nl.r1a.grassmaps.plugin.api.model.RoutePlanPoint
import nl.r1a.grassmaps.plugin.api.model.RoutePlanState
import nl.r1a.grassmaps.plugin.api.model.RoutePlanStatus
import nl.r1a.grassmaps.plugin.api.service.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.util.Locale

/**
 * Round-4 design review P1 1 (T-0612, reopened). The read-only route card must
 * hand its three frequent actions to one pinned bottom bar, keep Save as a
 * separate non-primary action, and give every target at least 48 dp. The JVM
 * suite cannot render the card, so the action model and the modifier shape are
 * pinned here; a rendered assertion would be stronger.
 */
class RouteCardActionBarTest {
    @Test
    fun theBottomBarCarriesStopsDetailsAndStartInThatOrder() {
        val card = card()

        assertEquals(
            "the canon bottom bar is Stops, Details, Start",
            listOf(RouteSummaryField.Stops, RouteSummaryField.Details, RouteSummaryField.Start),
            card.bottomActions,
        )
        assertFalse("Save is not a primary bar action", RouteSummaryField.Save in card.bottomActions)
    }

    @Test
    fun saveStaysASeparateNonPrimaryActionOutsideTheBar() {
        val content = source().substringAfter("fun RouteSummaryCardContent(").substringBefore("/**\n * The route's ends")
        val saveRow = content.substringBefore("// One pinned bottom bar")
        val bar = content.substringAfter("// One pinned bottom bar")

        assertEquals(RouteSummaryField.Save, card().secondaryAction)
        assertTrue("Save keeps its own row above the bar", saveRow.contains("onClick = onSave"))
        assertFalse("the bar composes no Save action", bar.contains("onSave"))
        assertTrue("the bar composes Stops", bar.contains("onClick = onStops"))
        assertTrue("the bar composes Details", bar.contains("onClick = onDetails"))
        assertTrue("the bar composes Start", bar.contains("onClick = onStart"))
    }

    @Test
    fun everyCardActionHasA48DpTargetInBothDimensions() {
        val cardSource = source()
        val chip = cardSource.substringAfter("private fun CardChip(").substringBefore("\n}\n")

        assertEquals(
            "the card's shared tap target changed",
            "48",
            Regex("""private const val MIN_TAP_TARGET_DP = (\d+)""").find(cardSource)?.groupValues?.get(1),
        )
        assertTrue(
            "the card chip lost its 48 dp minimum in both dimensions",
            chip.contains(".sizeIn(minWidth = MIN_TAP_TARGET_DP.dp, minHeight = MIN_TAP_TARGET_DP.dp)"),
        )
        assertTrue("the card chip lost its button role", chip.contains("Role.Button, onClick = onClick"))
    }

    @Test
    fun detailsAndStopsHandOffToDifferentExistingPlannerSections() {
        val details = routePlanSheetSections(RouteDetailsAction.OpenPlannerDetailsSection.sheetView)
        val stops = routePlanSheetSections(RouteStopsAction.OpenPlannerStopsSection.sheetView)

        assertTrue("Details opens the existing chart section", details.details)
        assertFalse("Details does not open the editable header", details.editableHeader)
        assertFalse("Details does not open the stop list", details.stopList)
        assertTrue("Stops still opens the existing stop list", stops.stopList)
        assertTrue("Stops still opens the editable header", stops.editableHeader)
        assertFalse("Stops does not open the chart section", stops.details)
    }

    @Test
    fun theDetailsActionNamesThePlannersDetailsSectionInBothLocales() {
        val english = TestStringCatalog.read("../app/src/main/res/values/strings.xml").resolver()
        val ukrainian = TestStringCatalog.read("../app/src/main/res/values-uk/strings.xml").resolver()

        assertEquals("route_summary_details", RouteDetailsAction.OpenPlannerDetailsSection.targetKey)
        assertEquals("route_planner_stops", RouteStopsAction.OpenPlannerStopsSection.targetKey)
        assertEquals("Details", english.resolve(RouteDetailsAction.OpenPlannerDetailsSection.targetKey))
        assertEquals("Подробиці", ukrainian.resolve(RouteDetailsAction.OpenPlannerDetailsSection.targetKey))
    }

    private fun card(): RouteSummaryCard {
        val strings = strings()
        val preview = routePlanPreview(ready(), strings, Locale.US, ZoneId.of("UTC")) ?: error("no preview")
        return routeSummaryCard(ready(), preview, strings)
    }

    private fun strings(): Strings =
        object : Strings {
            private val templates =
                mapOf(
                    "route_preview_minutes" to "%1\$s min",
                    "route_preview_hours" to "%1\$s h",
                    "route_preview_hours_minutes" to "%1\$s h %2\$s min",
                    "unit_distance_metres" to "%1\$s m",
                    "unit_distance_kilometres" to "%1\$s km",
                    "route_preview_start" to "Start",
                    "route_preview_save" to "Save",
                    "route_planner_stops" to "Stops",
                    "route_summary_details" to "Details",
                )

            override fun resolve(
                key: String,
                arguments: List<String>,
            ): String {
                val template = templates[key] ?: return key
                return String.format(Locale.US, template, *arguments.toTypedArray())
            }

            override fun contains(key: String): Boolean = key in templates
        }

    private fun ready(): RoutePlanState {
        val point = GeoPoint(49.8290, 23.9520)
        return RoutePlanState(
            status = RoutePlanStatus.Ready,
            start = RoutePlanPoint(point, "Pidzamche"),
            destination = RoutePlanPoint(GeoPoint(49.8420, 24.0320), "Stryiskyi Park"),
            options =
                listOf(
                    RoutePlanOption(
                        geometry = listOf(point, GeoPoint(49.8420, 24.0320)),
                        distanceMeters = 7_052.0,
                        durationSeconds = 1_482.0,
                        arrivalEpochMillis = 1_700_001_482_000L,
                    ),
                ),
        )
    }

    private fun source(): String = File("src/main/java/nl/r1a/grassmaps/ui/shell/RouteSummaryCard.kt").readText()
}
