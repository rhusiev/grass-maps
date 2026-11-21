package nl.r1a.grassmaps.app

import nl.r1a.grassmaps.plugin.api.error.AppError
import org.junit.Assert.assertEquals
import org.junit.Test

/** The copy a refused guidance start shows, shared by the planner and a track. */
class GuidanceStartMessageTest {
    @Test
    fun aDeniedPermissionNamesThePermission() {
        assertEquals(R.string.location_permission_denied, guidanceStartFailureMessage(AppError.PermissionDenied))
    }

    @Test
    fun everyOtherRefusalKeepsTheGenericMessage() {
        assertEquals(R.string.route_planner_start_unavailable, guidanceStartFailureMessage(AppError.Network))
        assertEquals(R.string.route_planner_start_unavailable, guidanceStartFailureMessage(AppError.NotReady))
    }
}
