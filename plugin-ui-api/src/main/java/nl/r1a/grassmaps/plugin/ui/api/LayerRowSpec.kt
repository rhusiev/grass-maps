package nl.r1a.grassmaps.plugin.ui.api

/**
 * Named sizes for the interactive rows of the Layers card.
 *
 * Every row a user taps in the card is at least the phone's minimum tap
 * target. The four marker-shape radios share the same size, so no choice is
 * smaller than its neighbours.
 */
object LayerRowSpec {
    const val ROW_MIN_HEIGHT_DP = 48
    const val RADIO_MIN_SIZE_DP = 48
}

/** One map style choice as the card receives it: a name and its short hint. */
data class LayerStyleOption(
    val name: String,
    val hint: String,
)

/** One style row: the choice is [name], [hint] explains when to use it. */
data class LayerStyleRow(
    val name: String,
    val hint: String,
    val selected: Boolean,
)

/**
 * Builds the style rows in the card's order and marks the active choice.
 *
 * The name and the hint stay separate fields: the card draws them on separate
 * lines, so the choice never reads as part of its own explanation.
 */
fun layerStyleRows(
    options: List<LayerStyleOption>,
    selectedName: String,
): List<LayerStyleRow> =
    options.map { option ->
        LayerStyleRow(
            name = option.name,
            hint = option.hint,
            selected = option.name == selectedName,
        )
    }
