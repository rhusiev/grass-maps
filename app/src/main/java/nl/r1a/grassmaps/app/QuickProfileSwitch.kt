package nl.r1a.grassmaps.app

import nl.r1a.grassmaps.plugin.api.model.ProfileId
import nl.r1a.grassmaps.plugins.profiles.ui.nextProfile

/**
 * The map switcher's one tap (T-0604). [order] is the catalog's display order,
 * so a user profile joins the cycle after walk, bike and hike. The controller
 * publishes widgets, style, units, routing and alerts as one state.
 */
class QuickProfileSwitch(
    private val controller: ActiveProfileController,
    private val order: () -> List<ProfileId>,
) {
    /** Moves to the next profile and returns the id that was activated. */
    fun next(): ProfileId {
        val target = nextProfile(order(), controller.activeProfileId())
        controller.activate(target)
        return target
    }
}
