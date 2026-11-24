package nl.r1a.grassmaps.ui.shell

import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.model.RoutePlanOption
import nl.r1a.grassmaps.plugin.api.model.RoutePlanPoint
import nl.r1a.grassmaps.plugin.api.model.RoutePlanState
import nl.r1a.grassmaps.plugin.api.model.RoutePlanStatus
import nl.r1a.grassmaps.plugin.api.service.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/**
 * The read-only card over the map (T-0612). A private Composable cannot be
 * rendered in this JVM module, so these tests pin the card's model: its ends,
 * its entries and the target of its Stops action. A rendered assertion would
 * be stronger.
 */
class RouteSummaryCardTest {
    @Test
    fun theCardShowsTheRoutesEndsAndReusesThePreviewValues() {
        val preview = routePlanPreview(READY, strings(), Locale.US, ZoneId.of("UTC")) ?: error("no preview")

        val card = routeSummaryCard(READY, preview, strings())

        assertEquals(
            listOf(
                RouteSummaryField.From,
                RouteSummaryField.To,
                RouteSummaryField.Time,
                RouteSummaryField.Distance,
                RouteSummaryField.Arrival,
                RouteSummaryField.Save,
                RouteSummaryField.Start,
                RouteSummaryField.Stops,
            ),
            card.entries.map { it.field },
        )
        assertEquals(
            listOf("Pidzamche", "Stryiskyi Park", "25 min", "7.1 km", "22:38", "Save", "Start", "Stops"),
            card.entries.map { it.text },
        )
        assertEquals("Pidzamche", card.from)
        assertEquals("Stryiskyi Park", card.to)
        assertEquals("25 min", card.time)
        assertEquals("7.1 km", card.distance)
        assertEquals("22:38", card.arrival)
    }

    @Test
    fun theStopsActionTargetsThePlannerSheetsExistingStopsSection() {
        val preview = routePlanPreview(READY, strings(), Locale.US, ZoneId.of("UTC")) ?: error("no preview")

        val card = routeSummaryCard(READY, preview, strings())

        assertEquals(RouteStopsAction.OpenPlannerStopsSection, card.stopsAction)
        assertEquals("route_planner_stops", card.stopsAction.targetKey)
        assertEquals(RouteSummaryField.Stops, card.entries.last().field)
        assertEquals("Stops", card.stops)
        assertNotNull("the card reads one published plan", preview)
    }

    @Test
    fun anEndWithoutALabelShowsItsCoordinates() {
        val state =
            READY.copy(
                start = RoutePlanPoint(START_POINT),
                destination = RoutePlanPoint(DESTINATION_POINT),
            )
        val preview = routePlanPreview(state, strings(), Locale.US, ZoneId.of("UTC")) ?: error("no preview")

        val card = routeSummaryCard(state, preview, strings())

        assertEquals("49.82900, 23.95200", card.from)
        assertEquals("49.84200, 24.03200", card.to)
    }

    @Test
    fun theEndRowsSayTheirRoleInBothLanguages() {
        val english = TestStringCatalog.read(APP_ENGLISH).resolver()
        val ukrainian = TestStringCatalog.read(APP_UKRAINIAN).resolver()

        assertEquals("From: Pidzamche", english.resolve("route_summary_from", listOf("Pidzamche")))
        assertEquals("To: Stryiskyi Park", english.resolve("route_summary_to", listOf("Stryiskyi Park")))
        assertEquals("Звідки: Pidzamche", ukrainian.resolve("route_summary_from", listOf("Pidzamche")))
        assertEquals("Куди: Stryiskyi Park", ukrainian.resolve("route_summary_to", listOf("Stryiskyi Park")))
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
                    "unit_distance_feet" to "%1\$s ft",
                    "unit_distance_miles" to "%1\$s mi",
                    "route_preview_start" to "Start",
                    "route_preview_save" to "Save",
                    "route_planner_stops" to "Stops",
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

    private companion object {
        const val NOW_MILLIS = 1_700_000_000_000L
        const val DISTANCE_METERS = 7_052.0
        const val DURATION_SECONDS = 1_482.0
        const val APP_ENGLISH = "../app/src/main/res/values/strings.xml"
        const val APP_UKRAINIAN = "../app/src/main/res/values-uk/strings.xml"

        val START_POINT = GeoPoint(49.8290, 23.9520)
        val DESTINATION_POINT = GeoPoint(49.8420, 24.0320)

        val READY =
            RoutePlanState(
                status = RoutePlanStatus.Ready,
                start = RoutePlanPoint(START_POINT, "Pidzamche"),
                destination = RoutePlanPoint(DESTINATION_POINT, "Stryiskyi Park"),
                options =
                    listOf(
                        RoutePlanOption(
                            geometry = listOf(START_POINT, DESTINATION_POINT),
                            distanceMeters = DISTANCE_METERS,
                            durationSeconds = DURATION_SECONDS,
                            arrivalEpochMillis = NOW_MILLIS + DURATION_SECONDS.toLong() * 1_000L,
                        ),
                    ),
            )
    }
}
