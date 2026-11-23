package nl.r1a.grassmaps.ui.shell

import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.model.RoutePlanOption
import nl.r1a.grassmaps.plugin.api.model.RoutePlanState
import nl.r1a.grassmaps.plugin.api.model.RoutePlanStatus
import nl.r1a.grassmaps.plugin.api.provider.RouteProfile
import nl.r1a.grassmaps.plugin.api.service.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The mode chips and alternative cards of the planner panel (T-0612). A private
 * Composable cannot be rendered in this JVM module, so these tests pin the
 * derivation the panel draws: which modes have a computed time, and what each
 * alternative card says. A rendered assertion would be stronger.
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
    fun everyReturnedOptionBecomesAnAlternativeCardAndOnlyTheSelectedIsLabelled() {
        val slower = option(DURATION_SECONDS + 120.0)
        val state = READY.copy(options = listOf(READY.options.first(), slower), selectedIndex = 1)

        val cards = routeAlternativeCards(state, strings())

        assertEquals(listOf(0, 1), cards.map { it.index })
        assertEquals(
            listOf("Alternative route 1, 25 min", "Alternative route 2, 27 min"),
            cards.map { it.description },
        )
        assertEquals(listOf(false, true), cards.map { it.selected })
        assertEquals(listOf("", "Selected"), cards.map { it.label })
        assertEquals(state.options[state.selectedIndex].durationSeconds, slower.durationSeconds, 0.0)
    }

    @Test
    fun noReturnedOptionsYieldsNoAlternativeCards() {
        assertTrue(routeAlternativeCards(READY.copy(options = emptyList()), strings()).isEmpty())
    }

    private fun strings(): Strings =
        object : Strings {
            private val templates =
                mapOf(
                    "route_preview_minutes" to "%1\$s min",
                    "route_preview_hours" to "%1\$s h",
                    "route_preview_hours_minutes" to "%1\$s h %2\$s min",
                    "route_preferences_profile_walk" to "walk",
                    "route_preferences_profile_bike" to "bike",
                    "route_preferences_profile_hike" to "hike",
                    "route_preferences_profile_car" to "car",
                    "route_planner_alternative_description" to "Alternative route %1\$s, %2\$s",
                    "route_alternative_selected" to "Selected",
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
