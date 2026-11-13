package nl.r1a.grassmap.plugin.api.provider

import kotlinx.serialization.json.Json
import nl.r1a.grassmap.plugin.api.model.RoutePlanState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The three-step routing preferences and their stable serialized values (T-0421). */
class RouteOptionsTest {
    @Test
    fun eachStepSerializesToItsStableValue() {
        val expected =
            mapOf(
                RoutePreference.Avoid to "\"avoid\"",
                RoutePreference.Normal to "\"normal\"",
                RoutePreference.DontMind to "\"dont_mind\"",
            )

        for ((preference, json) in expected) {
            assertEquals(json, Json.encodeToString(RoutePreference.serializer(), preference))
            assertEquals(preference, RoutePreference.ofSerialized(preference.serializedValue))
        }
        assertNull(RoutePreference.ofSerialized("sometimes"))
    }

    @Test
    fun allThreeStepsRoundTripTogether() {
        val options =
            RouteOptions(
                hills = RoutePreference.Avoid,
                busyRoads = RoutePreference.Normal,
                roughSurfaces = RoutePreference.DontMind,
            )

        val encoded = Json.encodeToString(RouteOptions.serializer(), options)

        // Normal is the default, so the encoder omits it and the decoder fills
        // it back in; the non-default steps keep their stable spellings.
        assertEquals("""{"hills":"avoid","rough_surfaces":"dont_mind"}""", encoded)
        assertEquals(options, Json.decodeFromString(RouteOptions.serializer(), encoded))
    }

    @Test
    fun theStepsAreIndependentAndCounted() {
        assertEquals(0, RouteOptions().setCount)
        assertTrue(RouteOptions().isDefault)
        val options = RouteOptions(hills = RoutePreference.Avoid, roughSurfaces = RoutePreference.DontMind)

        assertEquals(RoutePreference.Avoid, options.hills)
        assertEquals(RoutePreference.Normal, options.busyRoads)
        assertEquals(RoutePreference.DontMind, options.roughSurfaces)
        assertEquals(2, options.setCount)
    }

    @Test
    fun anOlderPlanStateWithoutPreferencesReadsAsAllNormal() {
        val state = Json.decodeFromString(RoutePlanState.serializer(), """{"status":"Ready"}""")

        assertEquals(RouteProfile.Walk, state.profile)
        assertEquals(RouteOptions(), state.profilePreferences)
        assertNull(state.overridePreferences)
        assertEquals(RouteOptions(), state.preferences)
    }
}
