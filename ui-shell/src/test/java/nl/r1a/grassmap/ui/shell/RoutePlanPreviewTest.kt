package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.RoutePlanOption
import nl.r1a.grassmap.plugin.api.model.RoutePlanPoint
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
 * The preview rule (T-0302, Save in T-0411): the three values and the two
 * actions, and nothing else. Elevation, slope, surface and way type are other
 * tasks.
 */
class RoutePlanPreviewTest {
    @Test
    fun thePreviewIsExactlyTimeDistanceArrivalSaveAndStart() {
        val preview = routePlanPreview(READY, strings(), Locale.US, ZoneId.of("UTC")) ?: error("no preview")

        assertEquals(
            listOf(
                RoutePreviewField.Time,
                RoutePreviewField.Distance,
                RoutePreviewField.Arrival,
                RoutePreviewField.Save,
                RoutePreviewField.Start,
            ),
            preview.entries.map { it.field },
        )
        assertEquals(listOf("25 min", "7.1 km", "22:38", "Save", "Start"), preview.entries.map { it.text })
        assertTrue(
            preview.entries.none { entry ->
                listOf("elevation", "slope", "surface", "way type").any { word ->
                    entry.text.contains(word, ignoreCase = true)
                }
            },
        )
    }

    @Test
    fun aSixthPreviewItemCannotBeAdded() {
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

        assertEquals(5, preview?.entries?.size)
    }

    @Test
    fun thePreviewNamesTheModelThatProducedTheTime() {
        val preview =
            routePlanPreview(
                READY,
                strings(),
                Locale.US,
                ZoneId.of("UTC"),
                time = RoutePreviewTime(seconds = 1_200.0, model = "Tobler walk"),
            ) ?: error("no preview")

        assertEquals("20 min · Tobler walk", preview.time)
        assertEquals("22:33", preview.arrival)
        assertEquals(5, preview.entries.size)
    }

    @Test
    fun anEstimateThatEqualsTheRecordedDurationKeepsTheRecordedArrival() {
        val preview =
            routePlanPreview(
                READY,
                strings(),
                Locale.US,
                ZoneId.of("UTC"),
                time = RoutePreviewTime(seconds = DURATION_SECONDS, model = "Flat speed"),
            ) ?: error("no preview")

        assertEquals("25 min · Flat speed", preview.time)
        assertEquals("22:38", preview.arrival)
    }

    @Test
    fun theModelNameDoesNotAddAPreviewField() {
        val preview =
            routePlanPreview(
                READY,
                strings(),
                Locale.US,
                ZoneId.of("UTC"),
                time = RoutePreviewTime(seconds = 1_200.0, model = "Bike power"),
            ) ?: error("no preview")

        assertEquals(
            listOf(
                RoutePreviewField.Time,
                RoutePreviewField.Distance,
                RoutePreviewField.Arrival,
                RoutePreviewField.Save,
                RoutePreviewField.Start,
            ),
            preview.entries.map { it.field },
        )
        assertEquals("Save", preview.save)
    }

    @Test
    fun theAlternativeChipUsesTheSameDurationRule() {
        assertEquals("25 min", routePlanAlternativeText(1_482.0, strings()))
        assertEquals("2 h 3 min", routePlanAlternativeText(7_380.0, strings()))
        assertEquals("2 h", routePlanAlternativeText(7_200.0, strings()))
    }

    @Test
    fun aRouteWithViasStillShowsOnlyTheFiveEntries() {
        val withVias =
            READY.copy(
                vias =
                    listOf(
                        RoutePlanPoint(GeoPoint(49.8330, 23.9700), "Cafe"),
                        RoutePlanPoint(GeoPoint(49.8370, 23.9900), "Museum"),
                        RoutePlanPoint(GeoPoint(49.8400, 24.0100), "Park"),
                    ),
            )

        val preview = routePlanPreview(withVias, strings(), Locale.US, ZoneId.of("UTC")) ?: error("no preview")

        assertEquals(
            listOf(
                RoutePreviewField.Time,
                RoutePreviewField.Distance,
                RoutePreviewField.Arrival,
                RoutePreviewField.Save,
                RoutePreviewField.Start,
            ),
            preview.entries.map { it.field },
        )
        assertEquals(listOf("25 min", "7.1 km", "22:38", "Save", "Start"), preview.entries.map { it.text })
    }

    @Test
    fun thePreviewOffersSaveBesideStartAndAPlanWithoutARouteHasNoCard() {
        val preview = routePlanPreview(READY, strings(), Locale.US, ZoneId.of("UTC")) ?: error("no preview")

        assertEquals(
            "the two actions sit together, Save before Start",
            listOf(RoutePreviewField.Save, RoutePreviewField.Start),
            preview.entries.takeLast(2).map { entry -> entry.field },
        )
        assertTrue("Save has a label", preview.save.isNotBlank())
        assertTrue("Start has a label", preview.start.isNotBlank())

        // A plan with no selected route has no card at all, so the Save action
        // cannot be tapped with nothing behind it. The save path reports the same
        // absence as a typed failure (RouteSaveTest in `app`).
        assertNull(routePlanPreview(RoutePlanState(), strings(), Locale.US, ZoneId.of("UTC")))
        assertNull(routePlanPreview(READY.copy(options = emptyList()), strings(), Locale.US, ZoneId.of("UTC")))
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
                    "route_preview_save" to "Save",
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
