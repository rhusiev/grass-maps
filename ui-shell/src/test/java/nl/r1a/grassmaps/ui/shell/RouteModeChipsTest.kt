package nl.r1a.grassmaps.ui.shell

import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.model.RoutePlanOption
import nl.r1a.grassmaps.plugin.api.model.RoutePlanState
import nl.r1a.grassmaps.plugin.api.model.RoutePlanStatus
import nl.r1a.grassmaps.plugin.api.provider.RouteProfile
import nl.r1a.grassmaps.plugin.api.service.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/**
 * The mode chips and alternative cards of the planner panel (T-0612). A private
 * Composable cannot be rendered in this JVM module, so these tests pin the
 * derivation the panel draws: which modes have a computed time, the benefit a
 * card names beside the selected route, and the one time source both the chip
 * and the summary read. A rendered assertion would be stronger.
 */
class RouteModeChipsTest {
    @Test
    fun onlyTheProfileTheEngineComputedGetsAModeChip() {
        val state = READY.copy(profile = RouteProfile.Bike)

        val chips = routeModeChips(state, strings())

        assertEquals(listOf(RouteProfile.Bike), chips.map { it.profile })
        assertEquals(listOf("bike · 25 min"), chips.map { it.text })
        assertEquals("25 min", chips.single().time)
        assertEquals(routePlanAlternativeText(DURATION_SECONDS, strings()), chips.single().time)
        assertTrue("no time is invented for walk, hike or car", chips.none { it.profile != RouteProfile.Bike })
    }

    @Test
    fun aPlanWithNoReturnedRouteHasNoModeChips() {
        val state = READY.copy(options = emptyList())

        assertTrue(routeModeChips(state, strings()).isEmpty())
    }

    @Test
    fun theChipUsesTheSameCanonicalTimeAsTheSummary() {
        val time = RoutePreviewTime(seconds = 1_200.0, model = "Tobler walk")
        val preview = routePlanPreview(READY, strings(), Locale.US, ZoneId.of("UTC"), time)

        val chips = routeModeChips(READY, strings(), time)

        assertEquals("20 min", chips.single().time)
        assertTrue("chip time is the preview time", preview!!.time.startsWith(chips.single().time))
        assertEquals("20 min · Tobler walk", preview.time)
    }

    @Test
    fun theSelectedAlternativeCardUsesTheCanonicalTimeToo() {
        val time = RoutePreviewTime(seconds = 1_200.0, model = "Tobler walk")

        val cards = routeAlternativeCards(READY, strings(), time = time)

        assertEquals("20 min", cards.single().time)
        assertEquals("20 min", routePlanPreview(READY, strings(), time = time)?.time?.substringBefore(MODE_SEPARATOR))
    }

    @Test
    fun everyReturnedOptionBecomesACardAndOnlyTheSelectedIsLabelledSelected() {
        val slower = option(DURATION_SECONDS + 120.0)
        val state = READY.copy(options = listOf(READY.options.first(), slower), selectedIndex = 1)

        val cards = routeAlternativeCards(state, strings())

        assertEquals(listOf(0, 1), cards.map { it.index })
        assertEquals(listOf(false, true), cards.map { it.selected })
        assertEquals(listOf("2 min faster", "Selected"), cards.map { it.label })
        assertEquals(listOf("25 min", "27 min"), cards.map { it.time })
        assertEquals(state.options[state.selectedIndex].durationSeconds, slower.durationSeconds, 0.0)
    }

    @Test
    fun aFasterAlternativeNamesTheTimeItSaves() {
        val faster = option(DURATION_SECONDS - 60.0)
        val state = READY.copy(options = listOf(READY.options.first(), faster), selectedIndex = 0)

        val cards = routeAlternativeCards(state, strings())

        assertEquals("Selected", cards[0].label)
        assertEquals("1 min faster", cards[1].label)
        assertEquals("24 min", cards[1].time)
    }

    @Test
    fun aSlowerAlternativeWithLessClimbNamesTheClimbItSaves() {
        val flatter = option(DURATION_SECONDS + 60.0)
        val state = READY.copy(options = listOf(READY.options.first(), flatter), selectedIndex = 0)
        val ascent = mapOf(0 to 62.0, 1 to 48.0)

        val cards = routeAlternativeCards(state, strings(), ascentMeters = ascent)

        assertEquals("14 m less climb", cards[1].label)
        assertEquals("26 min · ↑48 m", cards[1].description)
        assertEquals("↑48 m", cards[1].ascent)
    }

    @Test
    fun aCardShowsAscentOnlyWhenTheEngineSuppliesIt() {
        val state = READY.copy(options = listOf(READY.options.first(), option(DURATION_SECONDS - 60.0)))
        val ascent = mapOf(0 to 62.0, 1 to 66.0)

        val cards = routeAlternativeCards(state, strings(), ascentMeters = ascent)

        assertEquals("1 min faster", cards[1].label)
        assertEquals("↑62 m", cards[0].ascent)
        assertEquals("↑66 m", cards[1].ascent)
        assertEquals("25 min · ↑62 m", cards[0].description)
    }

    @Test
    fun noBenefitOrAscentIsInventedWhenTheEngineSuppliesNoData() {
        val slower = option(DURATION_SECONDS + 60.0)
        val state = READY.copy(options = listOf(READY.options.first(), slower), selectedIndex = 0)

        val cards = routeAlternativeCards(state, strings())

        assertEquals("", cards[1].label)
        assertNull(cards[1].ascent)
        assertEquals("26 min", cards[1].description)
    }

    @Test
    fun aBenefitSmallerThanOneMinuteIsNotRoundedUp() {
        val slightlyFaster = option(DURATION_SECONDS - 30.0)
        val state = READY.copy(options = listOf(READY.options.first(), slightlyFaster), selectedIndex = 0)

        val cards = routeAlternativeCards(state, strings())

        assertEquals("", cards[1].label)
    }

    @Test
    fun noReturnedOptionsYieldsNoAlternativeCards() {
        assertTrue(routeAlternativeCards(READY.copy(options = emptyList()), strings()).isEmpty())
    }

    @Test
    fun theBenefitAndAscentCopyExistsInBothLocales() {
        val english = TestStringCatalog.read("../app/src/main/res/values/strings.xml").resolver()
        val ukrainian = TestStringCatalog.read("../app/src/main/res/values-uk/strings.xml").resolver()
        val flatter = option(DURATION_SECONDS + 60.0)
        val state = READY.copy(options = listOf(READY.options.first(), flatter), selectedIndex = 0)
        val ascent = mapOf(0 to 62.0, 1 to 48.0)

        val englishCards = routeAlternativeCards(state, english, ascentMeters = ascent)
        val ukrainianCards = routeAlternativeCards(state, ukrainian, ascentMeters = ascent)

        assertEquals("14 m less climb", englishCards[1].label)
        assertEquals("↑48 м", ukrainianCards[1].ascent)
        assertEquals("14 м менше підйому", ukrainianCards[1].label)
        assertEquals("26 min · ↑48 m", englishCards[1].description)
        assertEquals("26 хв · ↑48 м", ukrainianCards[1].description)
    }

    private fun strings(): Strings =
        object : Strings {
            private val templates =
                mapOf(
                    "route_preview_minutes" to "%1\$s min",
                    "route_preview_hours" to "%1\$s h",
                    "route_preview_hours_minutes" to "%1\$s h %2\$s min",
                    "route_preview_start" to "Start",
                    "route_preview_save" to "Save",
                    "route_preferences_profile_walk" to "walk",
                    "route_preferences_profile_bike" to "bike",
                    "route_preferences_profile_hike" to "hike",
                    "route_preferences_profile_car" to "car",
                    "route_alternative_selected" to "Selected",
                    "route_alternative_faster" to "%1\$s min faster",
                    "route_alternative_less_climb" to "%1\$s m less climb",
                    "route_alternative_ascent" to "↑%1\$s",
                    "unit_elevation_metres" to "%1\$s m",
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
        const val MODE_SEPARATOR = " · "
        const val NOW_MILLIS = 1_700_000_000_000L
        const val DISTANCE_METERS = 7_052.0
        const val DURATION_SECONDS = 1_482.0

        fun option(seconds: Double) =
            RoutePlanOption(
                geometry = listOf(GeoPoint(49.8290, 23.9520), GeoPoint(49.8420, 24.0320)),
                distanceMeters = DISTANCE_METERS,
                durationSeconds = seconds,
                arrivalEpochMillis = NOW_MILLIS + seconds.toLong() * 1_000L,
            )

        val READY =
            RoutePlanState(
                status = RoutePlanStatus.Ready,
                options = listOf(option(DURATION_SECONDS)),
            )
    }
}
