package nl.r1a.grassmap.tracks

import nl.r1a.grassmap.plugin.api.model.TrackStyle

/**
 * The deterministic colour a new track gets. The palette index comes from the
 * last byte of the content hash plus the track index, so the same file always
 * draws in the same colour on every device and after a reinstall. A user colour
 * is written to the sidecar and replaces this value.
 */
object TrackPalette {
    /** Every track colour, chosen to read on both the light and the dark style. */
    val COLOURS: List<String> =
        listOf(
            "#1E88E5",
            "#E53935",
            "#8E24AA",
            "#00897B",
            "#F4511E",
            "#3949AB",
            "#6D4C41",
            "#C2185B",
        )

    /** The default styling for a track with [id]. */
    fun styleFor(id: TrackId): TrackStyle {
        val hash = TrackIdentity.documentHashOf(id)
        val index = id.value.substringAfter(':', "0").toIntOrNull() ?: 0
        val seed = (hash.takeLast(2).toIntOrNull(radix = 16) ?: 0) + index
        return TrackStyle(colour = COLOURS[seed % COLOURS.size])
    }
}
