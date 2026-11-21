package nl.r1a.grassmaps.ui.shell

import nl.r1a.grassmaps.plugin.api.service.Strings
import nl.r1a.grassmaps.plugin.ui.api.NavigationScreenState
import nl.r1a.grassmaps.plugin.ui.api.NavigationScreenStatus
import nl.r1a.grassmaps.plugin.ui.api.NavigationTurnCard

/**
 * One thing the navigation screen says without being asked: the instruction a
 * turn starts, the off-route state or the arrival.
 *
 * [urgent] marks the one state that must interrupt speech already in progress.
 */
data class NavigationAnnouncement(
    val text: String,
    val urgent: Boolean,
)

/**
 * The one announcement a navigation state change earns, or null when it earns
 * none.
 *
 * The distance to the next turn changes on most fixes. Announcing the turn card
 * whenever it changes would repeat the same instruction every few metres, so
 * the card is spoken when the instruction itself changes, and then carries the
 * distance of that moment. Off route and arrival are edges: each is announced
 * once per entry, and only off route interrupts.
 */
fun navigationAnnouncement(
    previous: NavigationScreenState?,
    current: NavigationScreenState,
    strings: Strings,
): NavigationAnnouncement? =
    when {
        current.status == NavigationScreenStatus.Arrived &&
            previous?.status != NavigationScreenStatus.Arrived -> {
            NavigationAnnouncement(
                sentence(
                    current.turn.title ?: strings.resolve(NavigationScreenStrings.ARRIVED),
                    current.turn.detail,
                ),
                urgent = false,
            )
        }

        current.status == NavigationScreenStatus.OffRoute &&
            previous?.status != NavigationScreenStatus.OffRoute -> {
            NavigationAnnouncement(
                sentence(
                    current.turn.title ?: strings.resolve(NavigationScreenStrings.OFF_ROUTE),
                    current.turn.detail,
                ),
                urgent = true,
            )
        }

        current.status == NavigationScreenStatus.Paused &&
            previous?.status != NavigationScreenStatus.Paused -> {
            NavigationAnnouncement(
                sentence(
                    current.turn.title ?: strings.resolve(NavigationScreenStrings.PAUSED),
                    current.turn.detail,
                ),
                urgent = false,
            )
        }

        previous == null || instructionOf(previous.turn) != instructionOf(current.turn) -> {
            instructionText(current.turn)?.let { text -> NavigationAnnouncement(text, urgent = false) }
        }

        else -> {
            null
        }
    }

/** What makes two turn cards the same instruction, whatever their distance. */
private fun instructionOf(card: NavigationTurnCard): String =
    listOfNotNull(
        card.spokenInstruction?.takeIf { it.isNotBlank() },
        card.street.takeIf { it.isNotBlank() },
        card.arrow.name,
    ).joinToString("|")

/** The first turn announcement: the distance, then the instruction. */
private fun instructionText(card: NavigationTurnCard): String? =
    listOfNotNull(
        card.distance.takeIf { it.isNotBlank() },
        card.spokenInstruction?.takeIf { it.isNotBlank() } ?: card.street.takeIf { it.isNotBlank() },
    ).joinToString(", ").takeIf { it.isNotBlank() }

/** Joins the two lines of a status card without a trailing separator. */
private fun sentence(
    title: String?,
    detail: String?,
): String =
    listOfNotNull(
        title?.takeIf { it.isNotBlank() },
        detail?.takeIf { it.isNotBlank() },
    ).joinToString(". ")
