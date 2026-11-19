package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.model.RouteNetwork
import nl.r1a.grassmap.plugin.api.model.UnitSystem
import nl.r1a.grassmap.plugin.api.service.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** The relation chart panel's labels and statistics (T-0420). */
class RelationAnalysisContentTest {
    @Test
    fun thePanelNamesTheRelationAndCarriesTheEngineStatistics() {
        val display = display(SUMMARY)

        assertEquals("Zelena stezhka", display.title)
        assertEquals("Local hiking route", display.subtitle)
        assertEquals("14.2 km · ↑410 m · ↓395 m", display.stats)
        assertEquals("ref 12", display.reference)
    }

    @Test
    fun thePanelShowsImperialDistanceAndClimbsWhenImperialIsSet() {
        val display = relationAnalysisDisplay(SUMMARY, MapStrings(ENGLISH), Locale.ENGLISH, UnitSystem.Imperial)

        assertEquals("8.8 mi · ↑1345 ft · ↓1296 ft", display.stats)
    }

    @Test
    fun anUnnamedRelationFallsBackToTheRouteRelationLabel() {
        assertEquals("Route relation", display(SUMMARY.copy(name = null)).title)
    }

    @Test
    fun aRelationWithoutGainKeepsOnlyTheDistance() {
        val display = display(SUMMARY.copy(ascentMeters = 0.0, descentMeters = 0.0, ref = null))

        assertEquals("14.2 km", display.stats)
        assertNull(display.reference)
    }

    @Test
    fun everyNetworkTierNamesItsRouteKind() {
        val expected =
            mapOf(
                RouteNetwork.HikingInternational to "International hiking route",
                RouteNetwork.HikingNational to "National hiking route",
                RouteNetwork.HikingRegional to "Regional hiking route",
                RouteNetwork.HikingLocal to "Local hiking route",
                RouteNetwork.CyclingInternational to "International cycling route",
                RouteNetwork.CyclingNational to "National cycling route",
                RouteNetwork.CyclingRegional to "Regional cycling route",
                RouteNetwork.CyclingLocal to "Local cycling route",
            )

        expected.forEach { (network, subtitle) ->
            assertEquals(subtitle, display(SUMMARY.copy(network = network)).subtitle)
        }
    }

    @Test
    fun thePanelLabelsResolveInUkrainian() {
        val display = relationAnalysisDisplay(SUMMARY, MapStrings(UKRAINIAN), UKRAINIAN_LOCALE)

        assertEquals("Місцевий пішохідний маршрут", display.subtitle)
        assertEquals("14,2 км · ↑410 м · ↓395 м", display.stats)
        assertEquals("№ 12", display.reference)
    }

    private fun display(summary: RelationAnalysisSummary): RelationAnalysisDisplay =
        relationAnalysisDisplay(summary, MapStrings(ENGLISH), Locale.ENGLISH)

    private class MapStrings(
        private val values: Map<String, String>,
    ) : Strings {
        override fun resolve(
            key: String,
            arguments: List<String>,
        ): String {
            val template = values[key] ?: key
            return if (arguments.isEmpty()) template else String.format(Locale.ROOT, template, *arguments.toTypedArray())
        }

        override fun contains(key: String): Boolean = key in values
    }

    private companion object {
        val UKRAINIAN_LOCALE: Locale = Locale.forLanguageTag("uk")

        val SUMMARY =
            RelationAnalysisSummary(
                name = "Zelena stezhka",
                network = RouteNetwork.HikingLocal,
                ref = "12",
                distanceMeters = 14_200.0,
                ascentMeters = 410.0,
                descentMeters = 395.0,
            )

        val ENGLISH =
            mapOf(
                "relation_analysis_untitled" to "Route relation",
                "relation_analysis_ref" to "ref %1\$s",
                "relation_analysis_hiking_international" to "International hiking route",
                "relation_analysis_hiking_national" to "National hiking route",
                "relation_analysis_hiking_regional" to "Regional hiking route",
                "relation_analysis_hiking_local" to "Local hiking route",
                "relation_analysis_cycling_international" to "International cycling route",
                "relation_analysis_cycling_national" to "National cycling route",
                "relation_analysis_cycling_regional" to "Regional cycling route",
                "relation_analysis_cycling_local" to "Local cycling route",
                "elevation_chart_range_ascent" to "↑%1\$s m",
                "elevation_chart_range_ascent_feet" to "↑%1\$s ft",
                "elevation_chart_range_descent" to "↓%1\$s m",
                "elevation_chart_range_descent_feet" to "↓%1\$s ft",
                "route_preview_metres" to "%1\$s m",
                "route_preview_kilometres" to "%1\$s km",
                "route_preview_feet" to "%1\$s ft",
                "route_preview_miles" to "%1\$s mi",
            )

        val UKRAINIAN =
            mapOf(
                "relation_analysis_untitled" to "Маршрут",
                "relation_analysis_ref" to "№ %1\$s",
                "relation_analysis_hiking_local" to "Місцевий пішохідний маршрут",
                "elevation_chart_range_ascent" to "↑%1\$s м",
                "elevation_chart_range_descent" to "↓%1\$s м",
                "route_preview_metres" to "%1\$s м",
                "route_preview_kilometres" to "%1\$s км",
            )
    }
}
