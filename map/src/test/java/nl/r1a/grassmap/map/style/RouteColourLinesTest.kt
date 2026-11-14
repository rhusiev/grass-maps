package nl.r1a.grassmap.map.style

import androidx.compose.ui.graphics.toArgb
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.RouteLinePattern
import nl.r1a.grassmap.plugin.api.model.RouteLineRun
import nl.r1a.grassmap.plugin.api.model.RouteLineStyle
import nl.r1a.grassmap.plugin.api.provider.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteColourLinesTest {
    @Test
    fun everyRunBecomesItsOwnLineStringWithItsColourAndPattern() {
        val route = route()
        val style =
            RouteLineStyle(
                runs =
                    listOf(
                        RouteLineRun(0, 1, "#4A3AA7", RouteLinePattern.Solid),
                        RouteLineRun(1, 2, "#EDA100", RouteLinePattern.Dashed),
                        RouteLineRun(2, 3, "#008300", RouteLinePattern.Striped),
                    ),
                stripeColour = "#202124",
            )

        val root = Json.parseToJsonElement(RouteColourLines.featureCollection(route, style)).jsonObject

        val features = root.getValue("features") as JsonArray
        assertEquals(3, features.size)
        val first = features[0].jsonObject
        assertEquals(
            "LineString",
            first
                .getValue("geometry")
                .jsonObject
                .getValue("type")
                .toStringValue(),
        )
        assertEquals(2, (first.getValue("geometry").jsonObject.getValue("coordinates") as JsonArray).size)
        assertEquals(
            "#4A3AA7",
            first
                .getValue("properties")
                .jsonObject
                .getValue("colour")
                .toStringValue(),
        )
        assertEquals(
            "solid",
            first
                .getValue("properties")
                .jsonObject
                .getValue("pattern")
                .toStringValue(),
        )
        assertEquals(
            "dashed",
            features[1]
                .jsonObject
                .getValue("properties")
                .jsonObject
                .getValue("pattern")
                .toStringValue(),
        )
        assertEquals(
            "striped",
            features[2]
                .jsonObject
                .getValue("properties")
                .jsonObject
                .getValue("pattern")
                .toStringValue(),
        )
    }

    @Test
    fun aRunIsClippedToTheGeometryAndAShortRunIsDropped() {
        val route = route()
        val style =
            RouteLineStyle(
                runs =
                    listOf(
                        RouteLineRun(0, 99, "#4A3AA7"),
                        RouteLineRun(2, 2, "#EDA100"),
                    ),
            )

        val root = Json.parseToJsonElement(RouteColourLines.featureCollection(route, style)).jsonObject

        val features = root.getValue("features") as JsonArray
        assertEquals(1, features.size)
        val coordinates =
            features[0]
                .jsonObject
                .getValue("geometry")
                .jsonObject
                .getValue("coordinates") as JsonArray
        assertEquals(4, coordinates.size)
    }

    @Test
    fun theLayersFilterOnTheDesignsThreePatterns() {
        assertEquals("""["!=",["get","pattern"],"dashed"]""", RouteColourLines.solidFilter().toString())
        assertEquals("""["==",["get","pattern"],"dashed"]""", RouteColourLines.dashedFilter().toString())
        assertEquals("""["==",["get","pattern"],"striped"]""", RouteColourLines.stripedFilter().toString())
    }

    @Test
    fun theDashKeepsTheDesignsFourDpOnAndTwoDpGapInLineWidthUnits() {
        assertEquals(listOf<Number>(0.8f, 0.4f), RouteColourLines.dashSpec(widthDp = 5f))
    }

    @Test
    fun theStripeInkIsParsedAndAMalformedValueIsRefused() {
        assertEquals(0xFF202124.toInt(), RouteColourLines.stripeColour("#202124")?.toArgb())
        assertNull(RouteColourLines.stripeColour("green"))
        assertNull(RouteColourLines.stripeColour(null))
    }

    private fun route(): Route =
        Route(
            geometry =
                listOf(
                    GeoPoint(49.8400, 24.0300),
                    GeoPoint(49.8401, 24.0301),
                    GeoPoint(49.8402, 24.0302),
                    GeoPoint(49.8403, 24.0303),
                ),
            distanceMeters = 40.0,
            durationSeconds = 28.0,
        )

    private fun kotlinx.serialization.json.JsonElement.toStringValue(): String = (this as JsonPrimitive).content
}
