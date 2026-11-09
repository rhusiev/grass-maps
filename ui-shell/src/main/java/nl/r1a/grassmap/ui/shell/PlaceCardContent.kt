package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.model.PlaceCard
import nl.r1a.grassmap.plugin.api.model.PlaceCardEntry
import nl.r1a.grassmap.plugin.api.model.PlaceCardLink
import nl.r1a.grassmap.plugin.api.model.PlaceCardShortcut
import nl.r1a.grassmap.plugin.api.model.PlaceCardValue
import nl.r1a.grassmap.plugin.api.model.PlaceOpeningState
import nl.r1a.grassmap.plugin.api.service.Strings

/**
 * One drawn row: the label and the value the active language resolved, plus the
 * link the row launches when it has one (T-0210 completes the T-0208 links).
 */
data class PlaceCardRow(
    val label: String,
    val value: String,
    val link: PlaceCardLink? = null,
)

/**
 * The card content with every label resolved and every absent field already
 * dropped, so the composable never draws an empty row.
 */
data class PlaceCardContent(
    val name: String?,
    val kind: String?,
    val address: String?,
    val openingHours: PlaceCardRow?,
    val openingState: PlaceCardRow?,
    val contacts: List<PlaceCardRow>,
    val accessibility: List<PlaceCardRow>,
    val attributes: List<PlaceCardRow>,
    val coordinates: List<PlaceCardRow>,
    val shortcuts: List<PlaceCardShortcutRow>,
) {
    val isEmpty: Boolean
        get() =
            name == null &&
                kind == null &&
                address == null &&
                openingHours == null &&
                openingState == null &&
                contacts.isEmpty() &&
                accessibility.isEmpty() &&
                attributes.isEmpty() &&
                coordinates.isEmpty() &&
                shortcuts.isEmpty()
}

/** One resolved action button: the label and the effect the host launches. */
data class PlaceCardShortcutRow(
    val label: String,
    val link: PlaceCardLink,
)

/**
 * Resolves a card's label keys through [strings] and drops blank fields.
 *
 * This is the whole display decision, kept out of the composable so a JVM test
 * can prove that a field the source does not carry is omitted and never shown
 * empty. The coordinate rows and the action buttons arrive on the card model
 * (T-0210); an absent list draws no section and no button.
 */
fun placeCardContent(
    card: PlaceCard,
    strings: Strings,
): PlaceCardContent =
    PlaceCardContent(
        name = card.name.nonBlank(),
        kind = card.kind.nonBlank(),
        address = card.address.nonBlank(),
        openingHours =
            card.openingHours.nonBlank()?.let { hours ->
                PlaceCardRow(label = strings.resolve(OPENING_HOURS_LABEL), value = hours)
            },
        openingState =
            card.openingState?.let { state ->
                val label = strings.resolve(OPENING_STATE_LABEL).nonBlank()
                val value = strings.resolve(openingStateKey(state)).nonBlank()
                if (label == null || value == null) null else PlaceCardRow(label = label, value = value)
            },
        contacts = card.contacts.rows(strings),
        accessibility = card.accessibility.rows(strings),
        attributes = card.attributes.rows(strings),
        coordinates = card.coordinates.rows(strings),
        shortcuts = card.shortcuts.shortcutRows(strings),
    )

private fun List<PlaceCardEntry>.rows(strings: Strings): List<PlaceCardRow> =
    mapNotNull { entry ->
        val label = strings.resolve(entry.labelKey).nonBlank()
        val value = entry.value.text(strings)
        if (label == null || value == null) {
            null
        } else {
            PlaceCardRow(label = label, value = value, link = entry.link)
        }
    }

private fun List<PlaceCardShortcut>.shortcutRows(strings: Strings): List<PlaceCardShortcutRow> =
    mapNotNull { shortcut ->
        strings.resolve(shortcut.labelKey).nonBlank()?.let { label ->
            PlaceCardShortcutRow(label = label, link = shortcut.link)
        }
    }

private fun PlaceCardValue.text(strings: Strings): String? =
    when (this) {
        is PlaceCardValue.Text -> text.nonBlank()
        is PlaceCardValue.Label -> strings.resolve(key).nonBlank()
    }

private fun String?.nonBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/** The shell-owned chrome label for the raw opening-hours string. */
internal const val OPENING_HOURS_LABEL = "place_card_opening_hours"

/** The shell-owned chrome label for the open state. */
internal const val OPENING_STATE_LABEL = "place_card_opening_state"

/** The shell-owned chrome label above the coordinate rows. */
internal const val COORDINATES_TITLE = "place_card_coordinates"

/** The i18n key for one open state. */
internal fun openingStateKey(state: PlaceOpeningState): String =
    when (state) {
        PlaceOpeningState.OPEN -> "place_card_open"
        PlaceOpeningState.CLOSED -> "place_card_closed"
        PlaceOpeningState.UNKNOWN -> "place_card_unknown"
    }
