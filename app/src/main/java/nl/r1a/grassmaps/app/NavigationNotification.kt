package nl.r1a.grassmaps.app

import nl.r1a.grassmaps.navigation.NavigationPhase
import nl.r1a.grassmaps.navigation.NavigationState
import nl.r1a.grassmaps.plugin.api.model.UnitSystem
import nl.r1a.grassmaps.plugin.api.service.Strings
import nl.r1a.grassmaps.plugin.api.unit.UnitText
import java.util.Locale

/**
 * What the navigation notification shows, derived from one published state.
 *
 * The next turn is the title and its distance is the text, so a locked screen
 * carries both facts without expanding the notification. Paused and arrived
 * replace the text and keep the turn where there is one. The function is pure:
 * it reads no clock and no location, so the service republishes the same
 * notification for the same state.
 */
data class NavigationNotificationContent(
    val title: String,
    val text: String?,
    val paused: Boolean,
)

object NavigationNotificationStrings {
    const val STARTING = "nav_notification_starting"
    const val FOLLOWING = "nav_notification_following"
    const val ARRIVED = "nav_notification_arrived"
    const val PAUSED = "nav_notification_paused"
    const val STOPPED = "nav_notification_stopped"
}

/** One action the navigation notification offers. */
enum class NavigationNotificationAction {
    Pause,
    Resume,
    Stop,
}

/**
 * The actions the round 5 notification offers in [phase]: Pause and Stop while
 * guidance runs, Resume and Stop while paused, and Stop alone after arrival.
 * The service builds exactly this list, so the lock screen cannot offer a resume
 * of a trip that already ended.
 */
fun navigationNotificationActions(phase: NavigationPhase): List<NavigationNotificationAction> =
    when (phase) {
        NavigationPhase.Arrived -> {
            listOf(NavigationNotificationAction.Stop)
        }

        NavigationPhase.Paused -> {
            listOf(NavigationNotificationAction.Resume, NavigationNotificationAction.Stop)
        }

        NavigationPhase.Idle,
        NavigationPhase.Following,
        NavigationPhase.Rerouting,
        NavigationPhase.Stopped,
        -> {
            listOf(NavigationNotificationAction.Pause, NavigationNotificationAction.Stop)
        }
    }

fun navigationNotificationContent(
    state: NavigationState,
    strings: Strings,
    units: UnitSystem = UnitSystem.Metric,
    locale: Locale = Locale.getDefault(),
): NavigationNotificationContent =
    when (state.phase) {
        NavigationPhase.Idle -> {
            NavigationNotificationContent(strings.resolve(NavigationNotificationStrings.STARTING), null, paused = false)
        }

        NavigationPhase.Stopped -> {
            NavigationNotificationContent(strings.resolve(NavigationNotificationStrings.STOPPED), null, paused = false)
        }

        NavigationPhase.Arrived -> {
            NavigationNotificationContent(strings.resolve(NavigationNotificationStrings.ARRIVED), null, paused = false)
        }

        NavigationPhase.Following, NavigationPhase.Rerouting, NavigationPhase.Paused -> {
            val instruction = state.nextInstruction
            val title = instruction?.text?.takeIf { it.isNotBlank() } ?: strings.resolve(NavigationNotificationStrings.FOLLOWING)
            val paused = state.phase == NavigationPhase.Paused
            val text =
                if (paused) {
                    strings.resolve(NavigationNotificationStrings.PAUSED)
                } else {
                    val meters =
                        instruction?.distanceToInstructionMeters ?: state.remainingDistanceMeters.takeIf { state.activeRoute != null }
                    meters?.let { distanceText(it, units, strings, locale) }
                }
            NavigationNotificationContent(title = title, text = text, paused = paused)
        }
    }

private fun distanceText(
    meters: Double,
    units: UnitSystem,
    strings: Strings,
    locale: Locale,
): String? {
    if (!meters.isFinite() || meters < 0.0) {
        return null
    }
    return UnitText.distance(meters, units, strings, locale)
}
