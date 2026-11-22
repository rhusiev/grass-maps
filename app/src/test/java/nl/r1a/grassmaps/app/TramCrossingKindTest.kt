package nl.r1a.grassmaps.app

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.host.RegionAccess
import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.model.WayAlertElementType
import nl.r1a.grassmaps.plugin.api.model.WayAlertFeature
import nl.r1a.grassmaps.plugin.api.model.WayAlertFeatureKind
import nl.r1a.grassmaps.plugin.api.model.WayAlertHit
import nl.r1a.grassmaps.plugin.api.provider.Route
import nl.r1a.grassmaps.plugin.api.provider.RouteProfile
import nl.r1a.grassmaps.plugins.route.warnings.RouteWarningKind
import nl.r1a.grassmaps.plugins.route.warnings.RouteWarningScanner
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * A tram level crossing is its own warning (Q50). The old-town walk reported
 * 28 tram nodes under "Railway crossing", so the label hid what a walker meets.
 * This reads the committed labels and feeds two nodes through the real scanner
 * to keep the two kinds distinct.
 */
class TramCrossingKindTest {
    private val english = resources("src/main/res/values/strings.xml")
    private val ukrainian = resources("src/main/res/values-uk/strings.xml")

    @Test
    fun aTramCrossingNodeGetsItsOwnKindAndLabel() =
        runTest {
            assertEquals("Tram crossing", english[TRAM_LABEL])
            assertEquals("Трамвайний переїзд", ukrainian[TRAM_LABEL])
            assertEquals("Railway crossing", english[RAILWAY_LABEL])

            val region =
                object : RegionAccess {
                    override suspend fun wayAlertFeatures(
                        route: Route,
                        corridorMeters: Double,
                    ): Outcome<List<WayAlertHit>> =
                        Outcome.Success(
                            listOf(
                                hit(node(WayAlertFeatureKind.TramLevelCrossing, 1001L), 100.0),
                                hit(node(WayAlertFeatureKind.RailwayCrossing, 1002L), 200.0),
                            ),
                        )
                }

            val warnings = RouteWarningScanner(region).warnings(route(), RouteProfile.Walk)
            val kinds = (warnings as? Outcome.Success)?.value?.map { it.kind } ?: error("the scan failed: $warnings")

            assertEquals(listOf(RouteWarningKind.TramLevelCrossing, RouteWarningKind.RailwayCrossing), kinds)
        }

    private fun node(
        kind: WayAlertFeatureKind,
        osmId: Long,
    ): WayAlertFeature =
        WayAlertFeature(
            kind = kind,
            elementType = WayAlertElementType.Node,
            osmId = osmId,
            geometry = listOf(GeoPoint(49.95, 23.94)),
        )

    private fun hit(
        feature: WayAlertFeature,
        meters: Double,
    ): WayAlertHit =
        WayAlertHit(
            feature = feature,
            alongRouteMeters = meters,
            distanceFromRouteMeters = 1.0,
        )

    private fun route(): Route =
        Route(
            geometry = listOf(GeoPoint(49.95, 23.94), GeoPoint(49.95, 23.95)),
            distanceMeters = 500.0,
            durationSeconds = 300.0,
        )

    private fun resources(path: String): Map<String, String> =
        Regex("""<string name="([^"]+)">([^<]*)</string>""")
            .findAll(File(path).readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private companion object {
        const val TRAM_LABEL = "route_warnings_kind_tram_level_crossing"
        const val RAILWAY_LABEL = "route_warnings_kind_railway_crossing"
    }
}
