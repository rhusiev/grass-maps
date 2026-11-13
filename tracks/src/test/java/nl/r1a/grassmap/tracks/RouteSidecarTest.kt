package nl.r1a.grassmap.tracks

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.RoutePlanPoint
import nl.r1a.grassmap.plugin.api.provider.RouteOptions
import nl.r1a.grassmap.plugin.api.provider.RoutePreference
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The route sidecar's versioned format (T-0411). It is the only file that makes
 * a stored route requestable again, so a value it cannot read must come back as
 * null and never as a half-filled request.
 */
class RouteSidecarTest {
    @Test
    fun everyProfileAndEveryOptionStepSurvivesTheRoundTrip() {
        for (profile in RouteProfile.entries) {
            for (step in RoutePreference.entries) {
                val recompute =
                    PlannedRouteRecompute(
                        profile = profile,
                        options = RouteOptions(hills = step, busyRoads = RoutePreference.Normal, roughSurfaces = RoutePreference.Avoid),
                        start = RoutePlanPoint(GeoPoint(49.84, 24.03), "Ринок"),
                        destination = RoutePlanPoint(GeoPoint(49.85, 24.01), "Park"),
                        vias = listOf(RoutePlanPoint(GeoPoint(49.845, 24.02), "")),
                    )

                val decoded = RouteSidecarJson.decode(RouteSidecarJson.encode(recompute))

                assertEquals("$profile/$step", recompute, decoded)
            }
        }
    }

    @Test
    fun theChainIsStartThenEveryViaThenTheDestination() {
        val recompute =
            PlannedRouteRecompute(
                start = RoutePlanPoint(GeoPoint(1.0, 1.0), "Start"),
                destination = RoutePlanPoint(GeoPoint(4.0, 4.0), "End"),
                vias =
                    listOf(
                        RoutePlanPoint(GeoPoint(2.0, 2.0), "First"),
                        RoutePlanPoint(GeoPoint(3.0, 3.0), "Second"),
                    ),
            )

        assertEquals(listOf("Start", "First", "Second", "End"), recompute.waypoints.map { point -> point.label })
    }

    @Test
    fun anotherVersionOrABrokenObjectIsUnreadable() {
        val encoded = RouteSidecarJson.encode(recompute())

        assertNull("another version is not guessed", RouteSidecarJson.decode(encoded.replace("\"version\":1", "\"version\":2")))
        assertNull("not JSON at all", RouteSidecarJson.decode("{not json"))
        assertNull("no start", RouteSidecarJson.decode(encoded.replaceFirst(Regex("\"start\":\\{[^}]*\\},"), "")))
        assertNull(
            "a profile this build does not know",
            RouteSidecarJson.decode(encoded.replace("\"profile\":\"walk\"", "\"profile\":\"swim\"")),
        )
        assertNull(
            "an option this build does not know",
            RouteSidecarJson.decode(encoded.replace("\"hills\":\"normal\"", "\"hills\":\"sometimes\"")),
        )
        assertNull(
            "a coordinate outside WGS84",
            RouteSidecarJson.decode(encoded.replace("\"lat\":49.84", "\"lat\":91.0")),
        )
    }

    @Test
    fun theStoredNamesAreTheSharedWireSpellings() {
        val encoded = RouteSidecarJson.encode(recompute(options = RouteOptions(hills = RoutePreference.Avoid)))

        assertTrue(encoded.contains("\"profile\":\"walk\""))
        assertTrue("the option key matches RouteOptions", encoded.contains("\"busy_roads\":\"normal\""))
        assertTrue("the value matches RoutePreference", encoded.contains("\"hills\":\"avoid\""))
    }

    private fun recompute(options: RouteOptions = RouteOptions()): PlannedRouteRecompute =
        PlannedRouteRecompute(
            options = options,
            start = RoutePlanPoint(GeoPoint(49.84, 24.03), "Rynok"),
            destination = RoutePlanPoint(GeoPoint(49.85, 24.01), "Park"),
        )
}
