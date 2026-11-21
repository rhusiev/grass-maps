package nl.r1a.grassmaps.plugins.profiles.ui

import nl.r1a.grassmaps.plugin.api.model.BuiltInProfiles
import nl.r1a.grassmaps.plugin.api.model.ProfileId
import nl.r1a.grassmaps.plugin.ui.api.DarkShellColors
import nl.r1a.grassmaps.plugin.ui.api.LightShellColors
import nl.r1a.grassmaps.plugin.ui.api.contrastRatio
import nl.r1a.grassmaps.plugin.ui.api.inkOn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The map switcher keeps the prototype's pinned order, role and theme tokens (T-0604). */
class ProfileSwitchBarTest {
    private val labels =
        ProfileSwitchLabels(walk = "Walk", bike = "Bike", hike = "Hike", all = "All profiles")

    @Test
    fun thePinnedOrderAndTheNextProfileFollowThePrototype() {
        assertEquals(BuiltInProfiles.ALL, PINNED_SWITCH_PROFILES)
        assertEquals(BuiltInProfiles.BIKE, nextProfile(PINNED_SWITCH_PROFILES, BuiltInProfiles.WALK))
        assertEquals(BuiltInProfiles.HIKE, nextProfile(PINNED_SWITCH_PROFILES, BuiltInProfiles.BIKE))
        assertEquals(BuiltInProfiles.WALK, nextProfile(PINNED_SWITCH_PROFILES, BuiltInProfiles.HIKE))
    }

    @Test
    fun aUserProfileJoinsTheCycleAfterThePinnedOnes() {
        val gravel = ProfileId("gravel")
        val order = PINNED_SWITCH_PROFILES + gravel

        assertEquals(gravel, nextProfile(order, BuiltInProfiles.HIKE))
        assertEquals(BuiltInProfiles.WALK, nextProfile(order, gravel))
    }

    @Test
    fun everyPinnedControlNamesItsProfileAndKeepsTheButtonRole() {
        val items = profileSwitchItems(BuiltInProfiles.BIKE, labels, profileSwitchPalette(LightShellColors))

        assertEquals(listOf("Walk", "Bike", "Hike"), items.map { it.label })
        assertEquals(listOf(SWITCH_ROLE, SWITCH_ROLE, SWITCH_ROLE), items.map { it.role })
        assertEquals(listOf(false, true, false), items.map { it.selected })
    }

    @Test
    fun theActiveControlUsesTheProfileColorInBothThemes() {
        listOf(LightShellColors, DarkShellColors).forEach { colors ->
            val palette = profileSwitchPalette(colors)
            val items = profileSwitchItems(BuiltInProfiles.WALK, labels, palette)

            assertEquals("bar", colors.glass, palette.bar)
            assertEquals("border", colors.ring, palette.border)
            assertEquals("active fill", colors.profiles.walk, items.first().fill)
            assertEquals("active icon", inkOn(colors.profiles.walk, colors), items.first().content)
            assertEquals("quiet fill", colors.soft, items[1].fill)
            assertEquals("quiet icon", quietIconOn(colors.profiles.bike, colors.soft, colors), items[1].content)
            assertTrue(
                "the quiet bike icon reads on soft",
                contrastRatio(items[1].content, colors.soft) >= UI_ICON_CONTRAST,
            )
        }
    }

    @Test
    fun theBarDrawsTheRoundNineVectorPictogramsInsteadOfLetters() {
        val bar = File("src/main/java/nl/r1a/grassmaps/plugins/profiles/ui/ProfileSwitchBar.kt").readText()
        val pictograms =
            mapOf(
                "ic_profile_walk" to "M12.6,7.6L11,13.6",
                "ic_profile_bike" to "M5.5,16.5L9.5,10",
                "ic_profile_hike" to "M2.5,20L9.5,8",
                "ic_profiles_grid" to "M16.75,14L16.75,19.5",
            )
        pictograms.forEach { (name, path) ->
            val drawable = File("src/main/res/drawable/$name.xml")
            assertTrue("$drawable is missing", drawable.exists())
            assertTrue("$drawable misses the round 9 path", drawable.readText().contains(path))
            assertTrue("$drawable is not drawn by the bar", bar.contains("R.drawable.$name"))
        }
        assertFalse("the bar still draws letter initials", bar.contains("switchGlyph") || bar.contains("GRID_GLYPH"))
        assertFalse("the bar still draws text glyphs", bar.contains("BasicText"))
    }

    @Test
    fun englishAndUkrainianNameTheSwitchAndTheNewGroups() {
        val keys =
            listOf(
                "profiles_group_style",
                "profiles_group_units",
                "profiles_switch_description",
                "profiles_switch_all",
            )
        listOf("src/main/res/values/strings.xml", "src/main/res/values-uk/strings.xml").forEach { path ->
            val present =
                Regex("""<string name="([^"]+)">([^<]+)</string>""")
                    .findAll(File(path).readText())
                    .associate { it.groupValues[1] to it.groupValues[2] }
            keys.forEach { key -> assertTrue("$path misses $key", present[key]?.isNotBlank() == true) }
            assertEquals(
                "the switch description names two profiles",
                2,
                present.getValue("profiles_switch_description").let { text ->
                    listOf("%1\$s", "%2\$s").count { text.contains(it) }
                },
            )
        }
    }
}
