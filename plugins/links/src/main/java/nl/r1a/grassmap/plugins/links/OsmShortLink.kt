package nl.r1a.grassmap.plugins.links

import nl.r1a.grassmap.plugin.api.model.GeoPoint

/**
 * The osm.org short-link decoder (T-0211).
 *
 * The algorithm is osm.org's own (`lib/short_link.rb` in the website): each
 * character carries three bits of the longitude and three of the latitude, the
 * characters interleave a Morton code, and trailing filler characters say how
 * far into a character's three zoom levels the link points. Decoding needs no
 * network and no lookup table.
 */
internal object OsmShortLink {
    /** osm.org's URL-friendly base 64; `~` replaced `@` in 2011. */
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_~"

    private const val LEGACY_TILDE = '@'
    private const val TILDE = '~'
    private const val ZOOM_FILLERS = "-="
    private const val BITS_PER_CHARACTER = 3
    private const val COORDINATE_BITS = 32
    private const val CHAR_BIT_SHIFT = 5
    private const val ZOOM_BASE = 8
    private const val ZOOM_STEP = 3

    /** The coordinate range the 32-bit fields span, `2^32`. */
    private const val WORLD_SPAN = 4_294_967_296.0
    private const val LONGITUDE_SPAN = 360.0
    private const val LATITUDE_SPAN = 180.0
    private const val LONGITUDE_OFFSET = 180.0
    private const val LATITUDE_OFFSET = 90.0

    private const val ONE = 1

    /** One decoded link: a position and the zoom the code carries. */
    data class Decoded(
        val point: GeoPoint,
        val zoom: Double,
    )

    /** Decodes [code], or returns null when a character is not osm.org's. */
    fun decode(code: String): Decoded? {
        var x = 0L
        var y = 0L
        var bits = 0
        var fillers = 0
        for (character in code) {
            val position = ALPHABET.indexOf(if (character == LEGACY_TILDE) TILDE else character)
            if (position < 0) {
                if (character !in ZOOM_FILLERS) {
                    return null
                }
                fillers -= 1
                continue
            }
            var value = position
            repeat(BITS_PER_CHARACTER) {
                x = (x shl 1) or ((value shr CHAR_BIT_SHIFT) and ONE).toLong()
                value = value shl 1
                y = (y shl 1) or ((value shr CHAR_BIT_SHIFT) and ONE).toLong()
                value = value shl 1
            }
            bits += BITS_PER_CHARACTER
        }
        if (bits == 0) {
            return null
        }
        // Pack the coordinates back out to their original 32 bits; a code can
        // carry one bit more than that, so a negative shift drops the extra.
        val shift = COORDINATE_BITS - bits
        val packedX = if (shift >= 0) x shl shift else x shr -shift
        val packedY = if (shift >= 0) y shl shift else y shr -shift
        val longitude = packedX * LONGITUDE_SPAN / WORLD_SPAN - LONGITUDE_OFFSET
        val latitude = packedY * LATITUDE_SPAN / WORLD_SPAN - LATITUDE_OFFSET
        // Ruby's % is a floor modulo: a negative filler count still names a
        // real zoom, so Kotlin must floor as well.
        val zoom = bits - ZOOM_BASE - Math.floorMod(fillers, ZOOM_STEP)
        return Decoded(GeoPoint(latitude, longitude), zoom.toDouble())
    }
}
