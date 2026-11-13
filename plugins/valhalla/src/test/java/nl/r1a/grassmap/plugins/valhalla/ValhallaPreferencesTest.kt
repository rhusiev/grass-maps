package nl.r1a.grassmap.plugins.valhalla

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import nl.r1a.grassmap.plugin.api.provider.RouteOptions
import nl.r1a.grassmap.plugin.api.provider.RoutePreference
import nl.r1a.grassmap.plugin.api.provider.RouteProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The three-step preference mapping onto Valhalla costing (T-0421). */
class ValhallaPreferencesTest {
    @Test
    fun hillsMapToUseHillsForWalkHikeAndBike() {
        val expected =
            mapOf(
                RouteProfile.Walk to Triple(0.5, 0.0, 1.0),
                RouteProfile.Hike to Triple(0.9, 0.0, 1.0),
                RouteProfile.Bike to Triple(0.4, 0.0, 1.0),
            )

        for ((profile, values) in expected) {
            val normal = ValhallaProfiles.costingOptions(profile, RouteOptions())
            val avoid = ValhallaProfiles.costingOptions(profile, RouteOptions(hills = RoutePreference.Avoid))
            val dontMind = ValhallaProfiles.costingOptions(profile, RouteOptions(hills = RoutePreference.DontMind))

            assertEquals("$profile normal", values.first, normal.value("use_hills"))
            assertEquals("$profile avoid", values.second, avoid.value("use_hills"))
            assertEquals("$profile don't mind", values.third, dontMind.value("use_hills"))
        }
    }

    @Test
    fun busyRoadsUseRoadsOnBikeAndLivingStreetsOnFoot() {
        val bike =
            mapOf(
                RoutePreference.Normal to 0.3,
                RoutePreference.Avoid to 0.0,
                RoutePreference.DontMind to 1.0,
            )
        for ((step, expected) in bike) {
            val options = ValhallaProfiles.costingOptions(RouteProfile.Bike, RouteOptions(busyRoads = step))
            assertEquals("bike $step", expected, options.value("use_roads"))
            assertFalse("a bike step must not add a pedestrian key", options.containsKey("use_living_streets"))
        }

        val foot =
            mapOf(
                RoutePreference.Normal to null,
                RoutePreference.Avoid to 1.0,
                RoutePreference.DontMind to 0.0,
            )
        for (profile in listOf(RouteProfile.Walk, RouteProfile.Hike)) {
            for ((step, expected) in foot) {
                val options = ValhallaProfiles.costingOptions(profile, RouteOptions(busyRoads = step))
                assertEquals("$profile $step", expected, options.value("use_living_streets"))
                assertFalse("a foot step must not add a bicycle key", options.containsKey("use_roads"))
            }
        }
    }

    @Test
    fun roughSurfacesUseBadSurfaceAvoidanceOnBikeAndTracksOnFoot() {
        val bike =
            mapOf(
                RoutePreference.Normal to 0.5,
                RoutePreference.Avoid to 1.0,
                RoutePreference.DontMind to 0.0,
            )
        for ((step, expected) in bike) {
            val options = ValhallaProfiles.costingOptions(RouteProfile.Bike, RouteOptions(roughSurfaces = step))
            assertEquals("bike $step", expected, options.value("avoid_bad_surfaces"))
            assertFalse("a bike step must not add a pedestrian key", options.containsKey("use_tracks"))
        }

        val foot =
            mapOf(
                RoutePreference.Normal to null,
                RoutePreference.Avoid to 0.0,
                RoutePreference.DontMind to 1.0,
            )
        for (profile in listOf(RouteProfile.Walk, RouteProfile.Hike)) {
            for ((step, expected) in foot) {
                val options = ValhallaProfiles.costingOptions(profile, RouteOptions(roughSurfaces = step))
                assertEquals("$profile $step", expected, options.value("use_tracks"))
                assertFalse("a foot step must not add a bicycle key", options.containsKey("avoid_bad_surfaces"))
            }
        }
    }

    @Test
    fun oneChangedStepLeavesTheOtherKeysAlone() {
        val base = ValhallaProfiles.costingOptions(RouteProfile.Bike, RouteOptions())
        val hillsOnly =
            ValhallaProfiles.costingOptions(
                RouteProfile.Bike,
                RouteOptions(hills = RoutePreference.DontMind),
            )

        assertEquals(1.0, hillsOnly.value("use_hills"))
        assertEquals(base.value("use_roads"), hillsOnly.value("use_roads"))
        assertEquals(base.value("avoid_bad_surfaces"), hillsOnly.value("avoid_bad_surfaces"))
        assertEquals(base.value("bicycle_type"), hillsOnly.value("bicycle_type"))
    }

    @Test
    fun carKeepsItsProfileForEveryStep() {
        val base = ValhallaProfiles.costingOptions(RouteProfile.Car, RouteOptions())
        val extreme =
            ValhallaProfiles.costingOptions(
                RouteProfile.Car,
                RouteOptions(
                    hills = RoutePreference.Avoid,
                    busyRoads = RoutePreference.Avoid,
                    roughSurfaces = RoutePreference.Avoid,
                ),
            )

        assertEquals(base, extreme)
        assertNull(base.value("use_hills"))
        assertNull(base.value("use_roads"))
        assertNull(base.value("use_living_streets"))
        assertNull(base.value("use_tracks"))
        assertNull(base.value("avoid_bad_surfaces"))
    }

    private fun JsonObject.value(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
}
