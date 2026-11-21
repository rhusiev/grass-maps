package nl.r1a.grassmaps.app

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.model.UnitSystem
import nl.r1a.grassmaps.plugin.api.provider.Route
import nl.r1a.grassmaps.plugin.api.provider.RouteProfile
import nl.r1a.grassmaps.plugin.api.service.Clock
import nl.r1a.grassmaps.plugin.api.service.Strings
import nl.r1a.grassmaps.routing.RouteJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/**
 * T-0661: the debug screen fixture must derive its ETA from the wall clock, not
 * from a fixed epoch left over from an old design capture. The assertion uses a
 * fixed clock so an impossible arrival cannot pass by accident.
 */
class NavigationFixtureEtaTest {
    @Test
    fun fixtureEtaIsTheClockNowPlusTheRemainingDuration() =
        runBlocking {
            val route = route()
            val store = NavigationScreenFixtureStore()
            val command = ShowNavigationScreenCommand(store, clock = Clock { CLOCK_NOW_MILLIS }, log = {})

            val result =
                command.execute(
                    JsonObject(
                        mapOf(
                            "route" to JsonPrimitive(RouteJson.encode(route)),
                            "phase" to JsonPrimitive("following"),
                            "along_m" to JsonPrimitive(0.0),
                        ),
                    ),
                )

            assertTrue(result.toString(), result is nl.r1a.grassmaps.plugin.api.error.Outcome.Success)
            val state = requireNotNull(store.state.value).state
            assertEquals(route.durationSeconds, state.remainingDurationSeconds, 0.001)
            val expectedEta = CLOCK_NOW_MILLIS + (route.durationSeconds * 1_000.0).toLong()
            assertEquals(expectedEta, state.etaEpochMillis)
        }

    @Test
    fun fixtureEtaFormatsInTheActiveLocaleAndUnitSystem() =
        runBlocking {
            val route = route()
            val store = NavigationScreenFixtureStore()
            val command = ShowNavigationScreenCommand(store, clock = Clock { CLOCK_NOW_MILLIS }, log = {})
            command.execute(
                JsonObject(
                    mapOf(
                        "route" to JsonPrimitive(RouteJson.encode(route)),
                        "along_m" to JsonPrimitive(0.0),
                    ),
                ),
            )
            val fixture = requireNotNull(store.state.value)
            val input =
                NavigationScreenInput(
                    state = fixture.state,
                    panel = null,
                    units = UnitSystem.Metric,
                )

            val metric = navigationScreenState(input, TestStrings, Locale.US, ZoneId.of("UTC"))
            assertEquals("09:09", metric.trip.arrivalTime)
            assertEquals("1.2 km", metric.trip.remainingDistance)

            val imperial = navigationScreenState(input.copy(units = UnitSystem.Imperial), TestStrings, Locale.US, ZoneId.of("UTC"))
            assertEquals("09:09", imperial.trip.arrivalTime)
            assertEquals("3937 ft", imperial.trip.remainingDistance)
        }

    private fun route() =
        Route(
            geometry = listOf(GeoPoint(49.84, 24.02), GeoPoint(49.84, 24.03), GeoPoint(49.84, 24.04)),
            distanceMeters = 1_200.0,
            durationSeconds = 960.0,
        )

    private object TestStrings : Strings {
        private val templates =
            mapOf(
                "unit_distance_metres" to "%1\$s m",
                "unit_distance_kilometres" to "%1\$s km",
                "unit_distance_feet" to "%1\$s ft",
                "unit_distance_miles" to "%1\$s mi",
                "guidance_panel_minutes" to "%1\$s min",
                "guidance_panel_hours" to "%1\$s h",
                "guidance_panel_hours_minutes" to "%1\$s h %2\$s min",
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
        const val CLOCK_NOW_MILLIS = 1_760_000_000_000L
    }
}
