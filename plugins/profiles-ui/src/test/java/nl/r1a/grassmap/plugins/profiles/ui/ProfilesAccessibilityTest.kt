package nl.r1a.grassmap.plugins.profiles.ui

import nl.r1a.grassmap.plugin.api.model.ProfileAction
import nl.r1a.grassmap.plugin.api.model.ProfileActionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The snackbar's polite announcement. The message names the profile the action
 * touched; the undo action beside it keeps its own label.
 */
class ProfilesAccessibilityTest {
    @Test
    fun aCreatedProfileIsNamed() {
        val notice = profileNotice(ProfileAction(ProfileActionKind.CREATED, profile = "walk"))

        assertEquals(R.string.profiles_notice_created, notice.key)
        assertEquals(listOf("walk"), notice.profileIds)
    }

    @Test
    fun aCopiedProfileNamesTheSource() {
        val notice = profileNotice(ProfileAction(ProfileActionKind.COPIED, profile = "hike", source = "walk"))

        assertEquals(R.string.profiles_notice_copied, notice.key)
        assertEquals(listOf("walk"), notice.profileIds)
    }

    @Test
    fun aResetHasNoName() {
        val notice = profileNotice(ProfileAction(ProfileActionKind.RESET, profile = "walk"))

        assertEquals(R.string.profiles_notice_reset, notice.key)
        assertTrue(notice.profileIds.isEmpty())
    }
}
