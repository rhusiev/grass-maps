package nl.r1a.grassmaps.ui.shell

import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.model.RoutePlanOption
import nl.r1a.grassmaps.plugin.api.model.RoutePlanPoint
import nl.r1a.grassmaps.plugin.api.model.RoutePlanState
import nl.r1a.grassmaps.plugin.api.model.RoutePlanStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * The read-only card over the map (T-0612). Round 8 `x-route-preview` draws a
 * small From/To card at the top; every press sits in the bottom sheet. A
 * private Composable cannot be rendered in this JVM module, so these tests pin
 * the card's model and check that its Composable has no control.
 */
class RouteSummaryCardTest {
    @Test
    fun theCardShowsOnlyTheRoutesEnds() {
        val card = routeSummaryCard(READY)

        assertEquals(listOf(RouteSummaryField.From, RouteSummaryField.To), card.entries.map { it.field })
        assertEquals(listOf("Pidzamche", "Stryiskyi Park"), card.entries.map { it.text })
        assertEquals("Pidzamche", card.from)
        assertEquals("Stryiskyi Park", card.to)
    }

    @Test
    fun theCardIsReadOnly() {
        val content =
            File("src/main/java/nl/r1a/grassmaps/ui/shell/RouteSummaryCard.kt")
                .readText()
                .substringAfter("fun RouteSummaryCardContent(")
                .substringBefore("/**\n * The route's ends")

        assertFalse("the top card must hold no press", "clickable" in content)
        assertFalse("the top card must hold no press", "onClick" in content)
    }

    @Test
    fun anEndWithoutALabelShowsItsCoordinates() {
        val state =
            READY.copy(
                start = RoutePlanPoint(START_POINT),
                destination = RoutePlanPoint(DESTINATION_POINT),
            )
        val card = routeSummaryCard(state)

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
