package nl.r1a.grassmaps.ui.shell

import nl.r1a.grassmaps.plugin.api.provider.RouteAttribute
import nl.r1a.grassmaps.plugin.api.provider.RouteOptions
import nl.r1a.grassmaps.plugin.api.provider.RoutePreference
import nl.r1a.grassmaps.plugin.api.provider.RouteProfile
import nl.r1a.grassmaps.plugin.api.service.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** The route preference entry and sheet resolvers (T-0421). */
class RoutePreferencesContentTest {
    @Test
    fun theEntrySaysChangedWhenTheRouteDiffersFromTheProfile() {
        val content =
            routePreferencesEntryContent(
                profile = RouteProfile.Bike,
                profilePreferences = PROFILE,
                overridePreferences = OVERRIDE,
                expanded = false,
                strings = MapStrings(ENGLISH),
            )

        assertEquals("Route preferences", content.title)
        assertEquals("changed", content.summary)
        assertEquals("Open route preferences", content.description)

        val expanded =
            routePreferencesEntryContent(RouteProfile.Bike, PROFILE, OVERRIDE, expanded = true, strings = MapStrings(ENGLISH))

        assertEquals("Close route preferences", expanded.description)
    }

    @Test
    fun theSheetTitleNamesItsRoleInsteadOfRepeatingTheSectionName() {
        val strings = MapStrings(ENGLISH)
        val entry = routePreferencesEntryContent(RouteProfile.Bike, RouteOptions(), null, expanded = false, strings = strings)
        val sheet = routePreferencesSheetContent(RouteProfile.Bike, RouteOptions(), RouteOptions(), strings)

        assertEquals("the collapse row keeps the section name", "Route preferences", entry.title)
        assertEquals("the expanded sheet states its own role", "Edit values", sheet.title)
    }

    @Test
    fun theEntryShowsTheProfileSetCountWithoutARouteOverride() {
        val withSetAttributes =
            routePreferencesEntryContent(
                RouteProfile.Bike,
                PROFILE,
                overridePreferences = null,
                expanded = false,
                strings = MapStrings(ENGLISH),
            )
        val allNormal =
            routePreferencesEntryContent(
                RouteProfile.Bike,
                RouteOptions(),
                overridePreferences = null,
                expanded = false,
                strings = MapStrings(ENGLISH),
            )

        assertEquals("2 set", withSetAttributes.summary)
        assertNull("an all-normal profile has nothing to report", allNormal.summary)
    }

    @Test
    fun theSheetResolvesThreeRowsWithChangedMarkersAndTheDefaultStep() {
        val content =
            routePreferencesSheetContent(
                profile = RouteProfile.Bike,
                profilePreferences = PROFILE,
                draft = OVERRIDE,
                strings = MapStrings(ENGLISH),
            )

        assertEquals("Edit values", content.title)
        assertEquals("These apply to this route only. A dot marks the bike profile defaults.", content.scope)
        assertEquals("Reset to profile", content.reset)
        assertEquals("Apply", content.apply)
        assertEquals(
            listOf(RouteAttribute.Hills, RouteAttribute.BusyRoads, RouteAttribute.RoughSurfaces),
            content.rows.map { row -> row.attribute },
        )
        assertEquals(listOf("Hills", "Busy roads", "Rough surfaces"), content.rows.map { row -> row.title })
        assertTrue(content.rows.all { row -> row.changed })

        val hills = content.rows.first()
        assertEquals(
            listOf("Avoid", "Normal", "Don't mind"),
            hills.steps.map { step -> step.label },
        )
        assertEquals(
            listOf(true, false, false),
            hills.steps.map { step -> step.profileDefault },
        )
        assertEquals(
            listOf(false, false, true),
            hills.steps.map { step -> step.selected },
        )
        assertEquals("Hills: Avoid, profile default", hills.steps.first().description)
        assertEquals("Hills: Don't mind", hills.steps.last().description)

        val busy = content.rows[1]
        assertEquals(
            listOf("Avoid", "Normal", "Don't mind"),
            busy.steps.map { step -> step.label },
        )
        assertEquals("Normal", busy.steps.single { step -> step.profileDefault }.label)
        assertEquals("Avoid", busy.steps.single { step -> step.selected }.label)

        val rough = content.rows[2]
        assertEquals("Don't mind", rough.steps.single { step -> step.profileDefault }.label)
        assertEquals("Normal", rough.steps.single { step -> step.selected }.label)
    }

    @Test
    fun eachProfileSheetShowsItsOwnSetOfRows() {
        val expected =
            mapOf(
                RouteProfile.Walk to listOf(RouteAttribute.Hills, RouteAttribute.Steps),
                RouteProfile.Hike to listOf(RouteAttribute.Hills, RouteAttribute.Steps, RouteAttribute.TrailDifficulty),
                RouteProfile.Bike to listOf(RouteAttribute.Hills, RouteAttribute.BusyRoads, RouteAttribute.RoughSurfaces),
                RouteProfile.Car to emptyList(),
            )

        for ((profile, attributes) in expected) {
            val content = routePreferencesSheetContent(profile, RouteOptions(), RouteOptions(), MapStrings(ENGLISH))

            assertEquals("$profile rows", attributes, content.rows.map { row -> row.attribute })
        }
    }

    @Test
    fun theFootSheetsTitleTheirOwnRows() {
        val walk = routePreferencesSheetContent(RouteProfile.Walk, RouteOptions(), RouteOptions(), MapStrings(ENGLISH))
        val hike = routePreferencesSheetContent(RouteProfile.Hike, RouteOptions(), RouteOptions(), MapStrings(ENGLISH))

        assertEquals(listOf("Hills", "Steps"), walk.rows.map { row -> row.title })
        assertEquals(listOf("Hills", "Steps", "Trail difficulty"), hike.rows.map { row -> row.title })
        assertEquals(
            "Steps: Don't mind",
            hike.rows[1]
                .steps
                .last()
                .description,
        )
    }

    @Test
    fun theEntryCountsOnlyTheAttributesTheProfileShows() {
        val bikeOnly = RouteOptions(busyRoads = RoutePreference.Avoid, roughSurfaces = RoutePreference.Avoid)

        val walk =
            routePreferencesEntryContent(
                RouteProfile.Walk,
                bikeOnly,
                overridePreferences = null,
                expanded = false,
                strings = MapStrings(ENGLISH),
            )
        val bike =
            routePreferencesEntryContent(
                RouteProfile.Bike,
                bikeOnly,
                overridePreferences = null,
                expanded = false,
                strings = MapStrings(ENGLISH),
            )

        assertNull("walk has no busy roads or rough surfaces row", walk.summary)
        assertEquals("2 set", bike.summary)
    }

    @Test
    fun anUnchangedDraftMarksEveryStepWithoutAChangedBadge() {
        val content =
            routePreferencesSheetContent(
                profile = RouteProfile.Walk,
                profilePreferences = PROFILE,
                draft = PROFILE,
                strings = MapStrings(ENGLISH),
            )

        assertTrue(content.rows.none { row -> row.changed })
        for (row in content.rows) {
            assertEquals(1, row.steps.count { step -> step.profileDefault })
            assertEquals(
                row.steps.single { step -> step.profileDefault }.preference,
                row.steps.single { step -> step.selected }.preference,
            )
        }
    }

    /**
     * Final blind review 8 P2-2: the collapsed row rendered at 27 dp because
     * vertical padding gave it no minimum. It takes the shared 48 dp tap
     * target as a minimum, so a wrapped title at a large font scale still
     * fits. Rendered size is checked on the AVD, not by this test.
     */
    @Test
    fun theCollapsedPreferencesRowHasATapTargetMinimum() {
        val source = File("src/main/java/nl/r1a/grassmaps/ui/shell/RoutePreferencesContent.kt").readText()
        val entry = source.substringAfter("fun RoutePreferencesEntry(").substringBefore("\n}")
        assertTrue(
            "the collapsed row has no 48 dp minimum height",
            Regex("""\.heightIn\(min\s*=\s*MapControlMetrics\.TAP_TARGET_DP\.dp\)""").containsMatchIn(entry),
        )
        assertTrue(
            "a fixed height clips the title at large font scales",
            !Regex("""\.height\(""").containsMatchIn(entry),
        )
    }

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
        val PROFILE =
            RouteOptions(
                hills = RoutePreference.Avoid,
                busyRoads = RoutePreference.Normal,
                roughSurfaces = RoutePreference.DontMind,
            )

        val OVERRIDE =
            RouteOptions(
                hills = RoutePreference.DontMind,
                busyRoads = RoutePreference.Avoid,
                roughSurfaces = RoutePreference.Normal,
            )

        val ENGLISH =
            mapOf(
                "route_preferences_title" to "Route preferences",
                "route_preferences_sheet_title" to "Edit values",
                "route_preferences_set_count" to "%1\$s set",
                "route_preferences_changed" to "changed",
                "route_preferences_scope" to "These apply to this route only. A dot marks the %1\$s profile defaults.",
                "route_preferences_open_description" to "Open route preferences",
                "route_preferences_close_description" to "Close route preferences",
                "route_preferences_hills" to "Hills",
                "route_preferences_hills_detail" to "Climbs on the way",
                "route_preferences_busy_roads" to "Busy roads",
                "route_preferences_busy_roads_detail" to "Streets with car traffic",
                "route_preferences_rough_surfaces" to "Rough surfaces",
                "route_preferences_rough_surfaces_detail" to "Gravel, cobbles, dirt",
                "route_preferences_steps" to "Steps",
                "route_preferences_steps_detail" to "Stairs on the way",
                "route_preferences_trail_difficulty" to "Trail difficulty",
                "route_preferences_trail_difficulty_detail" to "Hard trails and rough paths",
                "route_preferences_avoid" to "Avoid",
                "route_preferences_normal" to "Normal",
                "route_preferences_dont_mind" to "Don't mind",
                "route_preferences_step_description" to "%1\$s: %2\$s",
                "route_preferences_step_default_description" to "%1\$s: %2\$s, profile default",
                "route_preferences_reset" to "Reset to profile",
                "route_preferences_apply" to "Apply",
                "route_preferences_profile_walk" to "walk",
                "route_preferences_profile_bike" to "bike",
                "route_preferences_profile_hike" to "hike",
                "route_preferences_profile_car" to "car",
            )
    }
}
