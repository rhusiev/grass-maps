package nl.r1a.grassmaps.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user-facing event a refused navigation start turns into (T-0639).
 *
 * Only a permission refusal on a session that reads real location needs the
 * notice. The debug replay and the other refusals keep the existing silent
 * teardown.
 */
class NavigationRefusalNoticeTest {
    @Test
    fun aLocationRefusalNeedsThePermissionNotice() {
        assertEquals(
            NavigationStartNotice.LocationPermissionRequired,
            navigationRefusalNotice(ForegroundStartResult.PermissionRefused, locationPermissionRequired = true),
        )
    }

    @Test
    fun aFixtureRefusalNeedsNoNotice() {
        assertNull(
            navigationRefusalNotice(ForegroundStartResult.PermissionRefused, locationPermissionRequired = false),
        )
    }

    @Test
    fun aBackgroundOrStartedStartNeedsNoPermissionNotice() {
        assertNull(
            navigationRefusalNotice(ForegroundStartResult.NotAllowedRefused, locationPermissionRequired = true),
        )
        assertNull(
            navigationRefusalNotice(ForegroundStartResult.Started, locationPermissionRequired = true),
        )
    }

    @Test
    fun theSettingsIntentUsesThePackageScheme() {
        assertEquals("package:nl.r1a.grassmap", permissionSettingsData("nl.r1a.grassmap"))
    }

    @Test
    fun aNoticeWithoutABoundActivityAsksForTheFallback() {
        val host = NavigationNoticeHost()
        val notice = NavigationStartNotice.LocationPermissionRequired

        assertFalse("no activity", host.publish(notice))

        val activity = Any()
        host.bind(activity)
        assertTrue("bound activity", host.publish(notice))

        host.unbind(Any())
        assertTrue("another owner cannot unbind", host.publish(notice))
        host.unbind(activity)
        assertFalse("unbound again", host.publish(notice))
    }
}
