package nl.r1a.grassmaps.plugins.valhalla

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import nl.r1a.grassmaps.plugin.api.provider.RouteOptions
import nl.r1a.grassmaps.plugin.api.provider.RoutePreference
import nl.r1a.grassmaps.plugin.api.provider.RouteProfile
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
    fun stepsMapToStepPenaltyOnFoot() {
        val expected =
            mapOf(
                RouteProfile.Walk to Triple(480.0, 30.0, 0.0),
                RouteProfile.Hike to Triple(480.0, 20.0, 0.0),
            )

        for ((profile, values) in expected) {
            val avoid = ValhallaProfiles.costingOptions(profile, RouteOptions(steps = RoutePreference.Avoid))
            val normal = ValhallaProfiles.costingOptions(profile, RouteOptions())
            val dontMind = ValhallaProfiles.costingOptions(profile, RouteOptions(steps = RoutePreference.DontMind))

            assertEquals("$profile avoid", values.first, avoid.value("step_penalty"))
            assertEquals("$profile normal", values.second, normal.value("step_penalty"))
            assertEquals("$profile don't mind", values.third, dontMind.value("step_penalty"))
        }
    }

    @Test
    fun trailDifficultyMapsToMaxHikingDifficultyForHikeOnly() {
        val steps =
            mapOf(
                RoutePreference.Avoid to 2.0,
                RoutePreference.Normal to 5.0,
                RoutePreference.DontMind to 6.0,
            )
        for ((step, expected) in steps) {
            val hike = ValhallaProfiles.costingOptions(RouteProfile.Hike, RouteOptions(trailDifficulty = step))
            assertEquals("hike $step", expected, hike.value("max_hiking_difficulty"))
        }

        val walk = ValhallaProfiles.costingOptions(RouteProfile.Walk, RouteOptions(trailDifficulty = RoutePreference.DontMind))
        assertEquals("walk keeps T1", 1.0, walk.value("max_hiking_difficulty"))
    }

    @Test
    fun busyRoadsUseRoadsOnBikeOnly() {
        val bike =
            mapOf(
                RoutePreference.Normal to 0.3,
                RoutePreference.Avoid to 0.0,
                RoutePreference.DontMind to 1.0,
            )
        for ((step, expected) in bike) {
            val options = ValhallaProfiles.costingOptions(RouteProfile.Bike, RouteOptions(busyRoads = step))
            assertEquals("bike $step", expected, options.value("use_roads"))
        }

        for (profile in listOf(RouteProfile.Walk, RouteProfile.Hike)) {
            val options = ValhallaProfiles.costingOptions(profile, RouteOptions(busyRoads = RoutePreference.Avoid))
            assertEquals("$profile must not change", ValhallaProfiles.costingOptions(profile, RouteOptions()), options)
            assertFalse("$profile has no use_living_streets", options.containsKey("use_living_streets"))
        }
    }

    @Test
    fun roughSurfacesUseBadSurfaceAvoidanceOnBikeOnly() {
        val bike =
            mapOf(
                RoutePreference.Normal to 0.5,
                RoutePreference.Avoid to 1.0,
                RoutePreference.DontMind to 0.0,
            )
        for ((step, expected) in bike) {
            val options = ValhallaProfiles.costingOptions(RouteProfile.Bike, RouteOptions(roughSurfaces = step))
            assertEquals("bike $step", expected, options.value("avoid_bad_surfaces"))
        }

        for (profile in listOf(RouteProfile.Walk, RouteProfile.Hike)) {
            val options = ValhallaProfiles.costingOptions(profile, RouteOptions(roughSurfaces = RoutePreference.Avoid))
            assertEquals("$profile must not change", ValhallaProfiles.costingOptions(profile, RouteOptions()), options)
            assertFalse("$profile has no use_tracks", options.containsKey("use_tracks"))
        }
    }

    @Test
    fun bikeIgnoresFootAttributes() {
        val options =
            ValhallaProfiles.costingOptions(
                RouteProfile.Bike,
                RouteOptions(steps = RoutePreference.Avoid, trailDifficulty = RoutePreference.Avoid),
            )

        assertEquals(ValhallaProfiles.costingOptions(RouteProfile.Bike, RouteOptions()), options)
        assertFalse(options.containsKey("step_penalty"))
        assertFalse(options.containsKey("max_hiking_difficulty"))
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
                    steps = RoutePreference.Avoid,
                    trailDifficulty = RoutePreference.Avoid,
                ),
            )

        assertEquals(base, extreme)
        assertNull(base.value("use_hills"))
        assertNull(base.value("use_roads"))
        assertNull(base.value("step_penalty"))
        assertNull(base.value("max_hiking_difficulty"))
        assertNull(base.value("avoid_bad_surfaces"))
    }

    private fun JsonObject.value(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
}
