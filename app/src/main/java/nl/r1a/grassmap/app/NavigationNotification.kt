package nl.r1a.grassmap.app

import nl.r1a.grassmap.navigation.NavigationPhase
import nl.r1a.grassmap.navigation.NavigationState
import nl.r1a.grassmap.plugin.api.service.Strings
import java.util.Locale
import kotlin.math.roundToLong

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

fun navigationNotificationContent(
    state: NavigationState,
    strings: Strings,
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
                    meters?.let { distanceText(it, strings, locale) }
                }
            NavigationNotificationContent(title = title, text = text, paused = paused)
        }
    }

private fun distanceText(
    meters: Double,
    strings: Strings,
    locale: Locale,
): String? {
    if (!meters.isFinite() || meters < 0.0) {
        return null
    }
    return if (meters < METRES_PER_KILOMETRE) {
        strings.resolve(
            DISTANCE_METRES,
            listOf(meters.roundToLong().toString()),
        )
    } else {
        strings.resolve(
            DISTANCE_KILOMETRES,
            listOf(String.format(locale, "%.1f", meters / METRES_PER_KILOMETRE)),
        )
    }
}

private const val DISTANCE_METRES = "map_distance_metres"
private const val DISTANCE_KILOMETRES = "map_distance_kilometres"
private const val METRES_PER_KILOMETRE = 1_000.0
