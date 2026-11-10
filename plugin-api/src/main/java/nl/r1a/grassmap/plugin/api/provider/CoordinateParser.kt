package nl.r1a.grassmap.plugin.api.provider

import nl.r1a.grassmap.plugin.api.model.GeoPoint

/**
 * Reads a coordinate typed into a search field (T-0206).
 *
 * The search host asks this before it opens an index, so a typed coordinate is
 * one result row at that point on a device with no region installed. `null`
 * means "this text is not a coordinate" and the text goes to the index
 * instead; a parser never guesses and never throws, because the text is user
 * input.
 *
 * The contract has four promises, and an implementation that breaks one is not
 * a coordinate parser for search:
 *
 * - the result is finite and inside WGS84: `|latitude| <= 90`, `|longitude| <=
 *   180`;
 * - the first component is the latitude and the second the longitude, as the
 *   `geo:` URI order and `plugins/share`'s output both use;
 * - a two-number pair with no sign, decimal point or notation marker is
 *   refused, because "12 45" is as likely a house number as a coordinate;
 * - the notation set is the implementation's own. `plugins/coordinates` is the
 *   reference one and its committed vectors name every notation it accepts.
 */
fun interface CoordinateParser {
    fun parse(text: String): GeoPoint?

    companion object {
        /**
         * The parser used where none is wired: it recognizes nothing, so a
         * caller that never set one searches exactly as it did before T-0206.
         */
        val None: CoordinateParser = CoordinateParser { null }
    }
}
