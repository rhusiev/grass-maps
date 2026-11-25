package nl.r1a.grassmaps.ui.shell

import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.model.PlaceCard
import nl.r1a.grassmaps.plugin.api.model.PlaceCardAction
import nl.r1a.grassmaps.plugin.api.model.PlaceCardEntry
import nl.r1a.grassmaps.plugin.api.model.PlaceCardLink
import nl.r1a.grassmaps.plugin.api.model.PlaceCardShortcut
import nl.r1a.grassmaps.plugin.api.model.PlaceCardValue
import nl.r1a.grassmaps.plugin.api.model.PlaceOpeningState
import nl.r1a.grassmaps.plugin.api.model.UnitSystem
import nl.r1a.grassmaps.plugin.api.service.Strings
import java.util.Locale

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
    /** The place point, used with the caller's position for the subtitle estimate (T-0612). */
    val position: GeoPoint? = null,
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

/** Where the place card sits over the map (T-0612). */
enum class SheetAnchor {
    Bottom,
}

/** The one action pinned to the bottom of the place sheet (T-0612). */
enum class PlaceCardPrimaryAction {
    Route,
}

/** What one tile in the sheet's single action row launches (review 4 P1 2). */
enum class PlaceCardTileAction {
    Save,
    Share,
    Open,
}

/**
 * One tile in the action row. Save keeps the host callback; Share and Open keep
 * the link the card model supplied, so the row draws nothing the card cannot do.
 */
data class PlaceCardTile(
    val action: PlaceCardTileAction,
    val label: String,
    val link: PlaceCardLink? = null,
)

/**
 * The sheet structure the host draws, resolved here so a JVM test can check it
 * without a Compose renderer (T-0612). The host lays out every part from this
 * value: the anchor, the handle, the pinned action, the tile row and the copy
 * action that sits beside the decimal coordinate row.
 */
data class PlaceCardSheet(
    val anchor: SheetAnchor,
    val hasHandle: Boolean,
    val subtitle: String?,
    val coordinates: List<PlaceCardRow>,
    val shortcuts: List<PlaceCardShortcutRow>,
    val tiles: List<PlaceCardTile>,
    val copyLink: PlaceCardLink?,
    val saveLabel: String,
    val routeLabel: String,
    val primaryAction: PlaceCardPrimaryAction,
)

/** Resolves the bottom sheet for one place card. */
fun placeCardSheet(
    content: PlaceCardContent,
    strings: Strings,
    origin: GeoPoint? = null,
    units: UnitSystem = UnitSystem.Metric,
    locale: Locale = Locale.getDefault(),
): PlaceCardSheet {
    val eta = placeCardEta(origin, content.position, units, strings, locale)
    val shortcutLink = { action: PlaceCardAction ->
        content.shortcuts.firstOrNull { it.link.action == action }?.link
    }
    val tiles =
        buildList {
            add(PlaceCardTile(PlaceCardTileAction.Save, strings.resolve(SAVE_ACTION_LABEL)))
            shortcutLink(PlaceCardAction.SHARE)?.let { link ->
                add(PlaceCardTile(PlaceCardTileAction.Share, strings.resolve(SHARE_ACTION_LABEL), link))
            }
            shortcutLink(PlaceCardAction.OPEN_IN)?.let { link ->
                add(PlaceCardTile(PlaceCardTileAction.Open, strings.resolve(OPEN_ACTION_LABEL), link))
            }
        }
    return PlaceCardSheet(
        anchor = SheetAnchor.Bottom,
        hasHandle = true,
        subtitle =
            placeCardSubtitle(content.kind, origin, content.position, units, strings, locale),
        coordinates = content.coordinates,
        shortcuts = content.shortcuts,
        tiles = tiles,
        copyLink = shortcutLink(PlaceCardAction.COPY_COORDINATES),
        saveLabel = strings.resolve(SAVE_ACTION_LABEL),
        routeLabel =
            if (eta == null) {
                strings.resolve(ROUTE_ACTION_LABEL)
            } else {
                strings.resolve(PLACE_CARD_ROUTE_ETA, listOf(eta.walkText))
            },
        primaryAction = PlaceCardPrimaryAction.Route,
    )
}

/** Runs the sheet's pinned action through its caller. */
fun invokePlaceCardPrimary(
    action: PlaceCardPrimaryAction,
    onRoute: () -> Unit,
) {
    when (action) {
        PlaceCardPrimaryAction.Route -> onRoute()
    }
}

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
        position = card.position,
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

/** The primary action pinned to the bottom of the sheet. */
internal const val ROUTE_ACTION_LABEL = "place_card_route"

/** The save tile beside Share and Open. */
internal const val SAVE_ACTION_LABEL = "place_card_save"

/** The share tile beside Save and Open. */
internal const val SHARE_ACTION_LABEL = "place_card_share"

/** The open-in tile beside Save and Share. */
internal const val OPEN_ACTION_LABEL = "place_card_open_action"

/** The label of the copy action beside the decimal coordinate row. */
internal const val COPY_ACTION_LABEL = "place_card_copy"

/** The pinned Route label, timed from the same straight-line estimate as the subtitle. */
internal const val PLACE_CARD_ROUTE_ETA = "place_card_route_eta"

/** The i18n key for one open state. */
internal fun openingStateKey(state: PlaceOpeningState): String =
    when (state) {
        PlaceOpeningState.OPEN -> "place_card_open"
        PlaceOpeningState.CLOSED -> "place_card_closed"
        PlaceOpeningState.UNKNOWN -> "place_card_unknown"
    }
