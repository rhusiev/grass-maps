package nl.r1a.grassmap.app

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The screen sends the map position only when the separate allowance is on. */
class SearchQueryParamsTest {
    private val point = GeoPoint(49.8419, 24.0316)

    @Test
    fun withTheAllowanceOffTheRequestHasNoPosition() {
        val params =
            searchKeystrokeParams(
                query = "Львів",
                sequence = 3,
                index = "lviv",
                limit = 5,
                position = point,
                positionAllowed = false,
            )

        assertFalse(params.containsKey("position"))
    }

    @Test
    fun withTheAllowanceOnTheRequestCarriesTheMapPosition() {
        val params =
            searchKeystrokeParams(
                query = "Львів",
                sequence = 3,
                index = "lviv",
                limit = 5,
                position = point,
                positionAllowed = true,
            )

        val position = params.getValue("position").jsonObject
        assertEquals(
            49.8419,
            position
                .getValue("lat")
                .jsonPrimitive.content
                .toDouble(),
            1e-9,
        )
        assertEquals(
            24.0316,
            position
                .getValue("lon")
                .jsonPrimitive.content
                .toDouble(),
            1e-9,
        )
    }

    @Test
    fun aMissingFixLeavesThePositionOutEvenWhenAllowed() {
        val params =
            searchKeystrokeParams(
                query = "Львів",
                sequence = 3,
                index = "lviv",
                limit = 5,
                position = null,
                positionAllowed = true,
            )

        assertNull(params["position"])
    }

    @Test
    fun aSubmitKeepsTheKeystrokeRequestAndAddsTheStoreFlag() {
        val keystroke =
            searchKeystrokeParams(
                query = "Львів",
                sequence = 3,
                index = "lviv",
                limit = 5,
                position = point,
                positionAllowed = true,
            )
        val submit =
            searchSubmitParams(
                query = "Львів",
                sequence = 3,
                index = "lviv",
                limit = 5,
                position = point,
                positionAllowed = true,
            )

        assertEquals(keystroke.getValue("query"), submit.getValue("query"))
        assertEquals(keystroke.getValue("sequence"), submit.getValue("sequence"))
        assertEquals(keystroke.getValue("position"), submit.getValue("position"))
        assertEquals("true", submit.getValue("submit").jsonPrimitive.content)
        assertFalse(submit.containsKey("pick"))
    }

    @Test
    fun aPickCarriesItsTextAndStartsNoSearch() {
        val params =
            searchPickParams(
                query = "Кава на площі Ринок",
                index = "lviv",
                limit = 5,
                position = point,
                positionAllowed = true,
            )

        assertEquals("Кава на площі Ринок", params.getValue("query").jsonPrimitive.content)
        assertEquals("true", params.getValue("pick").jsonPrimitive.content)
        assertFalse("a pick must not order a search", params.containsKey("sequence"))
        val position = params.getValue("position").jsonObject
        assertEquals(
            point.latitude,
            position
                .getValue("lat")
                .jsonPrimitive.content
                .toDouble(),
            1e-9,
        )
    }
}
