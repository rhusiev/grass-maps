package nl.r1a.grassmaps.ui.shell

import nl.r1a.grassmaps.plugin.api.model.UnitSystem
import nl.r1a.grassmaps.plugin.api.provider.RouteProfile
import nl.r1a.grassmaps.plugin.api.service.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** The warning row and list resolvers (T-0409). */
class RouteWarningsContentTest {
    @Test
    fun theRowCountsTheWarningsAndSummarisesTheKindsInDistanceOrder() {
        val content = routeWarningsEntryContent(ROWS, MapStrings(ENGLISH, ENGLISH_PLURALS))

        assertEquals("3 warnings on this route", content?.title)
        assertEquals("Ford, private way, steps", content?.summary)
    }

    @Test
    fun aSingleWarningReadsInTheSingular() {
        val content = routeWarningsEntryContent(listOf(row(RouteWarningGlyph.Ford, 1200.0)), MapStrings(ENGLISH, ENGLISH_PLURALS))

        assertEquals("1 warning on this route", content?.title)
        assertEquals("Ford", content?.summary)
    }

    @Test
    fun noWarningsMeanNoRow() {
        assertNull(routeWarningsEntryContent(emptyList(), MapStrings(ENGLISH, ENGLISH_PLURALS)))
    }

    @Test
    fun theSummaryNamesEachKindOnceEvenWhenItRepeats() {
        val repeated =
            listOf(
                row(RouteWarningGlyph.Ford, 100.0),
                row(RouteWarningGlyph.Steps, 200.0),
                row(RouteWarningGlyph.Ford, 300.0),
            )

        val content = routeWarningsEntryContent(repeated, MapStrings(ENGLISH, ENGLISH_PLURALS))

        assertEquals("3 warnings on this route", content?.title)
        assertEquals("Ford, steps", content?.summary)
    }

    @Test
    fun theRowReadsInUkrainianAndLowercasesTheLaterKinds() {
        val content = routeWarningsEntryContent(ROWS, MapStrings(UKRAINIAN, UKRAINIAN_PLURALS, "uk"))

        assertEquals("3 попередження на маршруті", content?.title)
        assertEquals("Брід, приватна дорога, сходи", content?.summary)
    }

    @Test
    fun theListKeepsTheScannerOrderAndReadsKindDetailAndDistance() {
        val content = routeWarningsSheetContent(RouteProfile.Bike, 1140.0, 4600.0, ROWS, MapStrings(ENGLISH, ENGLISH_PLURALS))

        assertEquals("On this route", content.title)
        assertEquals("Bike profile · 19 min, 4.6 km · by distance", content.scope)
        assertEquals("Back", content.back)
        assertEquals(
            listOf(RouteWarningGlyph.Ford, RouteWarningGlyph.PrivateWay, RouteWarningGlyph.Steps),
            content.rows.map { row -> row.glyph },
        )
        assertEquals(listOf("Ford", "Private way", "Steps"), content.rows.map { row -> row.kind })
        assertEquals(listOf(null, "access=private", "push the bike"), content.rows.map { row -> row.detail })
        assertEquals(listOf("1.2 km", "3.4 km", "4.5 km"), content.rows.map { row -> row.distance })
    }

    @Test
    fun theWarningsSheetUsesTheActiveUnitSystem() {
        val content =
            routeWarningsSheetContent(
                RouteProfile.Bike,
                1140.0,
                4600.0,
                ROWS,
                MapStrings(ENGLISH, ENGLISH_PLURALS),
                units = UnitSystem.Imperial,
            )

        assertEquals("Bike profile · 19 min, 2.9 mi · by distance", content.scope)
        assertEquals(listOf("3937 ft", "2.1 mi", "2.8 mi"), content.rows.map { row -> row.distance })
    }

    @Test
    fun aWalkOnStepsGetsNoPushTheBikeDetail() {
        val content = routeWarningsSheetContent(RouteProfile.Walk, 900.0, 850.0, ROWS, MapStrings(ENGLISH, ENGLISH_PLURALS))

        assertEquals(
            listOf(null, "access=private", null),
            content.rows.map { row -> row.detail },
        )
        assertEquals("Walk profile · 15 min, 850 m · by distance", content.scope)
    }

    @Test
    fun everyKindResolvesToItsEnglishLabel() {
        val expected =
            mapOf(
                RouteWarningGlyph.Ford to "Ford",
                RouteWarningGlyph.Steps to "Steps",
                RouteWarningGlyph.Barrier to "Barrier",
                RouteWarningGlyph.RailwayCrossing to "Railway crossing",
                RouteWarningGlyph.PrivateWay to "Private way",
            )

        for ((glyph, label) in expected) {
            assertEquals(label, routeWarningKindText(glyph, MapStrings(ENGLISH, ENGLISH_PLURALS)))
        }
    }

    @Test
    fun everyKindResolvesToItsUkrainianLabel() {
        val expected =
            mapOf(
                RouteWarningGlyph.Ford to "Брід",
                RouteWarningGlyph.Steps to "Сходи",
                RouteWarningGlyph.Barrier to "Бар'єр",
                RouteWarningGlyph.RailwayCrossing to "Залізничний переїзд",
                RouteWarningGlyph.PrivateWay to "Приватна дорога",
            )

        for ((glyph, label) in expected) {
            assertEquals(label, routeWarningKindText(glyph, MapStrings(UKRAINIAN, UKRAINIAN_PLURALS, "uk")))
        }
    }

    private fun row(
        glyph: RouteWarningGlyph,
        meters: Double,
    ): RouteWarningRow = RouteWarningRow(glyph = glyph, distanceAlongRouteMeters = meters)

    private class MapStrings(
        private val values: Map<String, String>,
        private val plurals: Map<String, Map<String, String>> = emptyMap(),
        private val language: String = "en",
    ) : Strings {
        override fun resolve(
            key: String,
            arguments: List<String>,
        ): String {
            val template = values[key] ?: key
            return format(template, arguments)
        }

        override fun resolveQuantity(
            key: String,
            quantity: Int,
            arguments: List<String>,
        ): String {
            val items = plurals[key] ?: return resolve(key, arguments)
            val template = items[pluralCategory(language, quantity)] ?: items["other"] ?: return key
            return format(template, arguments)
        }

        override fun contains(key: String): Boolean = key in values || key in plurals

        private fun format(
            template: String,
            arguments: List<String>,
        ): String = if (arguments.isEmpty()) template else String.format(Locale.ROOT, template, *arguments.toTypedArray())
    }

    private companion object {
        val ROWS =
            listOf(
                RouteWarningRow(RouteWarningGlyph.Ford, 1200.0),
                RouteWarningRow(RouteWarningGlyph.PrivateWay, 3400.0),
                RouteWarningRow(RouteWarningGlyph.Steps, 4500.0),
            )

        val ENGLISH =
            mapOf(
                "route_warnings_title" to "On this route",
                "route_warnings_scope" to "%1\$s profile · %2\$s, %3\$s · by distance",
                "route_warnings_back" to "Back",
                "route_warnings_kind_ford" to "Ford",
                "route_warnings_kind_steps" to "Steps",
                "route_warnings_kind_barrier" to "Barrier",
                "route_warnings_kind_railway_crossing" to "Railway crossing",
                "route_warnings_kind_private_way" to "Private way",
                "route_warnings_detail_private" to "access=private",
                "route_warnings_detail_push_bike" to "push the bike",
                "route_preferences_profile_walk" to "walk",
                "route_preferences_profile_bike" to "bike",
                "route_preferences_profile_hike" to "hike",
                "route_preferences_profile_car" to "car",
                "route_preview_minutes" to "%1\$s min",
                "route_preview_hours" to "%1\$s h",
                "route_preview_hours_minutes" to "%1\$s h %2\$s min",
                "unit_distance_metres" to "%1\$s m",
                "unit_distance_kilometres" to "%1\$s km",
                "unit_distance_feet" to "%1\$s ft",
                "unit_distance_miles" to "%1\$s mi",
            )

        val ENGLISH_PLURALS =
            mapOf(
                "route_warnings_count" to
                    mapOf(
                        "one" to "%1\$s warning on this route",
                        "other" to "%1\$s warnings on this route",
                    ),
            )

        val UKRAINIAN =
            ENGLISH +
                mapOf(
                    "route_warnings_title" to "На цьому маршруті",
                    "route_warnings_scope" to "%1\$s · %2\$s, %3\$s · за відстанню",
                    "route_warnings_back" to "Назад",
                    "route_warnings_kind_ford" to "Брід",
                    "route_warnings_kind_steps" to "Сходи",
                    "route_warnings_kind_barrier" to "Бар'єр",
                    "route_warnings_kind_railway_crossing" to "Залізничний переїзд",
                    "route_warnings_kind_private_way" to "Приватна дорога",
                    "route_warnings_detail_private" to "access=private",
                    "route_warnings_detail_push_bike" to "пішки з велосипедом",
                )

        val UKRAINIAN_PLURALS =
            mapOf(
                "route_warnings_count" to
                    mapOf(
                        "one" to "%1\$s попередження на маршруті",
                        "few" to "%1\$s попередження на маршруті",
                        "many" to "%1\$s попереджень на маршруті",
                        "other" to "%1\$s попередження на маршруті",
                    ),
            )
    }
}
