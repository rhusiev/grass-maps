package nl.r1a.grassmap.tracks

import nl.r1a.grassmap.plugin.api.model.TrackStyle

/**
 * The deterministic colour a new track gets. The palette index comes from the
 * last byte of the content hash plus the track index, so the same file always
 * draws in the same colour on every device and after a reinstall. A user colour
 * is written to the sidecar and replaces this value.
 *
 * [COLOURS] is also the card's colour choice (T-0415), so a track always draws
 * in one of the six values the card offers. The values are the round-6 track
 * look screen's six swatches, not a second palette.
 */
object TrackPalette {
    /** Every track colour, chosen to read on both the light and the dark style. */
    val COLOURS: List<String> =
        listOf(
            "#1B8A3A",
            "#2A78D6",
            "#D93025",
            "#8A5CC7",
            "#EB6834",
            "#D08A00",
        )

    /** The default styling for a track with [id]. */
    fun styleFor(id: TrackId): TrackStyle {
        val hash = TrackIdentity.documentHashOf(id)
        val index = id.value.substringAfter(':', "0").toIntOrNull() ?: 0
        val seed = (hash.takeLast(2).toIntOrNull(radix = 16) ?: 0) + index
        return TrackStyle(colour = COLOURS[seed % COLOURS.size])
    }
}
