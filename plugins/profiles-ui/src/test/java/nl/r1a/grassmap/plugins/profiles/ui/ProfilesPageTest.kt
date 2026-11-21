package nl.r1a.grassmaps.plugins.profiles.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfilesPageTest {
    @Test
    fun `back walks up from every page to the list and then closes`() {
        assertNull(ProfilesPage.List.back())
        assertEquals(ProfilesPage.List, ProfilesPage.Editor("bike").back())
        assertEquals(ProfilesPage.List, ProfilesPage.NewProfile.back())
        assertEquals(ProfilesPage.Editor("bike"), ProfilesPage.CopySettings("bike").back())
    }
}
