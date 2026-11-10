package nl.r1a.grassmap.plugins.valhalla

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import nl.r1a.grassmap.plugin.api.provider.RouteAttributes
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's profile mapping and the committed measurement input are one
 * document. This test is what keeps them from drifting: change an option in
 * [ValhallaProfiles] without changing `test-data/routing/valhalla-profiles.json`
 * (or the other way round) and the build fails here rather than in a run.
 */
class ValhallaProfilesTest {
    @Test
    fun theEngineMappingEqualsTheCommittedDocument() {
        val committed = resource("routing/valhalla-profiles.json")

        assertEquals(committed, ValhallaProfiles.document())
    }

    @Test
    fun everyV1ProfileNamesTheFiveAttributes() {
        val capabilities =
            ValhallaRoutingEngine({ throw AssertionError("no transport needed") }).capabilities()

        assertEquals(RouteAttributes.V1, capabilities.attributeKeys)
        assertEquals(
            setOf(RouteProfile.Walk, RouteProfile.Bike, RouteProfile.Hike),
            capabilities.profiles,
        )
    }

    @Test
    fun walkAndHikeDifferWhereTheTrailDoes() {
        val walk = ValhallaProfiles.of(RouteProfile.Walk).costingOptions
        val hike = ValhallaProfiles.of(RouteProfile.Hike).costingOptions

        // walk stops at T1 hiking; hike admits up to T5.
        assertEquals(1, walk.intValue("max_hiking_difficulty"))
        assertEquals(5, hike.intValue("max_hiking_difficulty"))
        assertTrue(hike.doubleValue("use_hills")!! > walk.doubleValue("use_hills")!!)
    }

    @Test
    fun bikeUsesTheHybridWithTunedRoadAndSurfaceAvoidance() {
        val bike = ValhallaProfiles.of(RouteProfile.Bike)

        assertEquals("bicycle", bike.costing)
        assertEquals("hybrid", bike.costingOptions.stringValue("bicycle_type"))
        assertEquals(18.0, bike.costingOptions.doubleValue("cycling_speed")!!, 0.0)
        assertTrue(bike.costingOptions.doubleValue("use_roads")!! > 0.0)
        assertTrue(bike.costingOptions.doubleValue("avoid_bad_surfaces")!! > 0.0)
    }

    private fun resource(name: String): JsonObject =
        checkNotNull(javaClass.getResourceAsStream("/$name")) { "missing $name" }
            .bufferedReader()
            .use { Json.parseToJsonElement(it.readText()).jsonObject }

    private fun JsonObject.stringValue(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private fun JsonObject.intValue(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.doubleValue(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
}
