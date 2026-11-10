package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.RoutePlanOption
import nl.r1a.grassmap.plugin.api.model.RoutePlanState
import nl.r1a.grassmap.plugin.api.model.RoutePlanStatus
import nl.r1a.grassmap.plugin.api.service.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/**
 * The preview rule (T-0302): time, distance, arrival and Start, and nothing
 * else. Elevation, slope, surface and way type are other tasks.
 */
class RoutePlanPreviewTest {
    @Test
    fun thePreviewIsExactlyTimeDistanceArrivalAndStart() {
        val preview = routePlanPreview(READY, strings(), Locale.US, ZoneId.of("UTC")) ?: error("no preview")

        assertEquals(
            listOf(
                RoutePreviewField.Time,
                RoutePreviewField.Distance,
                RoutePreviewField.Arrival,
                RoutePreviewField.Start,
            ),
            preview.entries.map { it.field },
        )
        assertEquals(listOf("25 min", "7.1 km", "22:38", "Start"), preview.entries.map { it.text })
        assertTrue(
            preview.entries.none { entry ->
                listOf("elevation", "slope", "surface", "way type").any { word ->
                    entry.text.contains(word, ignoreCase = true)
                }
            },
        )
    }

    @Test
    fun aFifthPreviewItemCannotBeAdded() {
        val preview = routePlanPreview(READY, strings(), Locale.US, ZoneId.of("UTC")) ?: error("no preview")

        val extra =
            preview.entries +
                RoutePreviewEntry(RoutePreviewField.Time, "elevation 120 m")

        val failure = runCatching { RoutePreview(extra) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun aPlanWithoutASelectedRouteHasNoPreview() {
        val state =
            RoutePlanState(
                status = RoutePlanStatus.Planning,
                options = emptyList(),
            )

        assertNull(routePlanPreview(state, strings(), Locale.US, ZoneId.of("UTC")))
    }

    @Test
    fun aSingleReturnedRouteStillHasAPreviewButNoAlternativeList() {
        val one =
            READY.copy(
                options = listOf(READY.options.first()),
            )

        val preview = routePlanPreview(one, strings(), Locale.US, ZoneId.of("UTC"))

        assertEquals(4, preview?.entries?.size)
    }

    @Test
    fun theAlternativeChipUsesTheSameDurationRule() {
        assertEquals("25 min", routePlanAlternativeText(1_482.0, strings()))
        assertEquals("2 h 3 min", routePlanAlternativeText(7_380.0, strings()))
        assertEquals("2 h", routePlanAlternativeText(7_200.0, strings()))
    }

    private fun strings(): Strings =
        object : Strings {
            private val templates =
                mapOf(
                    "route_preview_minutes" to "%1\$s min",
                    "route_preview_hours" to "%1\$s h",
                    "route_preview_hours_minutes" to "%1\$s h %2\$s min",
                    "route_preview_metres" to "%1\$s m",
                    "route_preview_kilometres" to "%1\$s km",
                    "route_preview_start" to "Start",
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

        val READY =
            RoutePlanState(
                status = RoutePlanStatus.Ready,
                options =
                    listOf(
                        RoutePlanOption(
                            geometry = listOf(GeoPoint(49.8290, 23.9520), GeoPoint(49.8420, 24.0320)),
                            distanceMeters = DISTANCE_METERS,
                            durationSeconds = DURATION_SECONDS,
                            arrivalEpochMillis = NOW_MILLIS + DURATION_SECONDS.toLong() * 1_000L,
                        ),
                    ),
            )
    }
}
