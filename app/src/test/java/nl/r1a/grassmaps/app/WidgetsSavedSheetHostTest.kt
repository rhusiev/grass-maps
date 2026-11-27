package nl.r1a.grassmaps.app

import nl.r1a.grassmaps.plugin.api.model.BuiltInProfiles
import nl.r1a.grassmaps.plugin.api.model.ProfileId
import nl.r1a.grassmaps.plugin.api.model.ProfileSummary
import nl.r1a.grassmaps.plugin.api.model.ProfilesState
import nl.r1a.grassmaps.plugin.api.service.Strings
import org.junit.Assert.assertEquals
import org.junit.Test

/** The profile name in the chevron sheet's "WALK WIDGETS" heading. */
class WidgetsSavedSheetHostTest {
    @Test
    fun aUserProfileShowsItsOwnName() {
        val profiles = ProfilesState(listOf(ProfileSummary(id = "mine", name = "Dog walk")))

        assertEquals("Dog walk", profileDisplayName(ProfileId("mine"), profiles, FakeStrings))
    }

    @Test
    fun aBuiltInProfileShowsItsTranslatedName() {
        assertEquals("Walk", profileDisplayName(BuiltInProfiles.WALK, ProfilesState(), FakeStrings))
        assertEquals("Hike", profileDisplayName(BuiltInProfiles.HIKE, ProfilesState(), FakeStrings))
    }

    @Test
    fun anUnknownProfileWithoutANameFallsBackToItsId() {
        assertEquals("other", profileDisplayName(ProfileId("other"), ProfilesState(), FakeStrings))
    }

    private object FakeStrings : Strings {
        override fun resolve(
            key: String,
            arguments: List<String>,
        ): String =
            when (key) {
                "profiles_walk" -> "Walk"
                "profiles_hike" -> "Hike"
                else -> key
            }

        override fun contains(key: String): Boolean = true
    }
}
