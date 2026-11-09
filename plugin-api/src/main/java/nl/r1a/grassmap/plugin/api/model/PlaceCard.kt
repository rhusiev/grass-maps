package nl.r1a.grassmap.plugin.api.model

/**
 * The card content behind one place (T-0208), already mapped from the place's
 * raw OSM tags.
 *
 * Every field is optional and every list is empty when the source carries
 * nothing for it, so the shell omits an absent field instead of drawing an
 * empty row. [name], [kind] and [address] are data, not authored English.
 */
data class PlaceCard(
    val name: String? = null,
    val kind: String? = null,
    val address: String? = null,
    val openingHours: String? = null,
    val openingState: PlaceOpeningState? = null,
    val contacts: List<PlaceCardEntry> = emptyList(),
    val accessibility: List<PlaceCardEntry> = emptyList(),
    val attributes: List<PlaceCardEntry> = emptyList(),
    val coordinates: List<PlaceCardEntry> = emptyList(),
    val shortcuts: List<PlaceCardShortcut> = emptyList(),
) {
    /** True when the card has no field or action to draw at all. */
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

/**
 * The open state at the moment the card was built (T-0209).
 *
 * The provider evaluates the raw `opening_hours` value; a value it cannot
 * parse is [UNKNOWN], never an error. [PlaceCard.openingHours] keeps the raw
 * string, so a reader can still see the source text.
 */
enum class PlaceOpeningState {
    OPEN,
    CLOSED,
    UNKNOWN,
}

/**
 * One labelled row. [labelKey] and [PlaceCardValue.Label] name i18n resource
 * keys, so the value types carry no authored English; the shell resolves them
 * through the `Strings` service in the active language.
 */
data class PlaceCardEntry(
    val labelKey: String,
    val value: PlaceCardValue,
    val link: PlaceCardLink? = null,
)

/** A row value: free text from the source, or a label resolved through `Strings`. */
sealed interface PlaceCardValue {
    data class Text(
        val text: String,
    ) : PlaceCardValue

    data class Label(
        val key: String,
    ) : PlaceCardValue
}

/**
 * What a row or a shortcut would do when the shell can launch it. [target] is
 * the value the platform receives: a URI for the `OPEN_*` actions, the text for
 * [PlaceCardAction.COPY_COORDINATES] and [PlaceCardAction.SHARE]. A `tel:` or
 * `mailto:` scheme is added by the mapper, so the model never guesses at a site
 * the source did not state.
 */
data class PlaceCardLink(
    val action: PlaceCardAction,
    val target: String,
)

/**
 * One action button the card shows below its rows (T-0210).
 *
 * The formatting and the link building belong to `plugins/share`; this model
 * only carries the resolved i18n label key and the effect the host launches, so
 * `ui-shell` draws the button without knowing how a coordinate or an osm.org
 * link is built.
 */
data class PlaceCardShortcut(
    val labelKey: String,
    val link: PlaceCardLink,
)

enum class PlaceCardAction {
    CALL,
    OPEN_WEBSITE,
    SEND_EMAIL,
    OPEN_SOCIAL,
    COPY_COORDINATES,
    SHARE,
    OPEN_IN,
}
