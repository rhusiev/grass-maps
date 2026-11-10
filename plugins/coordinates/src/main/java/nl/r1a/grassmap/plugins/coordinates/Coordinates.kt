package nl.r1a.grassmap.plugins.coordinates

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.CoordinateParser
import kotlin.math.abs
import kotlin.math.floor

/**
 * The coordinate notations a search field reads (T-0206).
 *
 * The parser is the input direction of the same three notations
 * `plugins/share` writes out, plus the letter forms a person types:
 *
 * - decimal degrees, with or without a sign: `49.841740, 24.023910`,
 *   `-33.868800, 151.209300`;
 * - hemisphere letters as a prefix or a suffix, on any notation: `N 49.841740,
 *   E 24.023910`, `49.841740 N, 24.023910 E`;
 * - DMS with degree, minute and second symbols or with spaces:
 *   `49°49'18.26"N, 24°01'26.08"E`, `N 49 49 18.26, E 24 1 26.08`;
 * - DDM with a fractional minute: `49°49.3044'N, 24°01.4346'E`,
 *   `N 49 49.3044, E 24 1.4346`.
 *
 * The rules are fixed here rather than guessed per case:
 *
 * - the first component is the latitude and the second the longitude, so a
 *   longitude-first text is refused instead of swapped;
 * - a hemisphere letter decides the sign, and an explicit sign that
 *   contradicts it is refused; `-0.0` stays negative zero;
 * - minutes and seconds are below 60, latitude at most 90 and longitude at
 *   most 180, all measured on the absolute value;
 * - a pair of two plain integers with no sign, decimal point or notation
 *   marker is refused, because `12 45` is as likely a house number as a
 *   coordinate;
 * - text the parser does not understand exactly is `null`, never a guess, so
 *   an ordinary search still reaches the index.
 *
 * The pair is split at a comma, or, without one, at every place a component
 * could begin and the split that yields exactly one valid pair wins. That is
 * what lets `N 49 ... E 24 ...` and `49 ... N, 24 ... E` share one reader.
 * Two different valid readings are ambiguous and are refused. Without a comma
 * and without a hemisphere letter a pair must be two single numbers, so three
 * bare numbers are refused instead of being read as degrees and minutes.
 *
 * The committed vectors in `test-data/search/coordinate-notations.tsv` are the
 * list of notations this object accepts and refuses; `plugins/share` formats
 * the accepted points in the app round-trip test, so the two directions cannot
 * drift apart.
 */
object Coordinates {
    /** The one parser the app hands to search and to a coordinate entry point. */
    val parser: CoordinateParser = CoordinateParser(::parse)

    /** The point [text] denotes, or null when it is not a coordinate. */
    fun parse(text: String): GeoPoint? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || isBareIntegerPair(trimmed)) {
            return null
        }
        val readings =
            candidates(trimmed).mapNotNull { candidate ->
                val latitude = parseHalf(candidate.first, candidate.allowParts) ?: return@mapNotNull null
                val longitude = parseHalf(candidate.second, candidate.allowParts) ?: return@mapNotNull null
                pair(latitude, longitude)
            }
        return readings.toSet().singleOrNull()
    }

    /**
     * A coordinate pair is two integers with nothing marking it as a
     * coordinate; that is the shape a house number pair takes too.
     */
    private fun isBareIntegerPair(text: String): Boolean {
        if (text.any { it.isLetter() } || text.any { it in MARKERS }) {
            return false
        }
        val tokens =
            text
                .replace(',', ' ')
                .trim()
                .split(WHITESPACE)
                .filter { it.isNotEmpty() }
        return tokens.size == PAIR_PARTS && tokens.all { token -> token.all { it.isDigit() } }
    }

    /** One way [text] could become two components. */
    private data class Candidate(
        val first: String,
        val second: String,
        /** DMS and DDM need a comma or a hemisphere letter to be unambiguous. */
        val allowParts: Boolean,
    )

    /**
     * The ways [text] can become two components. A comma is the one explicit
     * pair separator, so it is the only split point when present. Without one,
     * a hemisphere letter marks where the second component begins, and a pair
     * with no letter at all must be two whitespace-separated single numbers.
     */
    private fun candidates(text: String): List<Candidate> {
        if (',' in text) {
            val parts = text.split(',')
            if (parts.size != PAIR_PARTS) {
                return emptyList()
            }
            val first = parts[0].trim()
            val second = parts[1].trim()
            return if (first.isEmpty() || second.isEmpty()) {
                emptyList()
            } else {
                listOf(Candidate(first, second, allowParts = true))
            }
        }
        if (text.none { character -> character.isLetter() && hemisphereOf(character) != null }) {
            val tokens = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }
            return if (tokens.size == PAIR_PARTS) {
                listOf(Candidate(tokens[0], tokens[1], allowParts = false))
            } else {
                emptyList()
            }
        }
        val splits = mutableListOf<Candidate>()
        for (index in 1 until text.length) {
            if (!text[index].isWhitespace() && !text[index].isLetter()) {
                continue
            }
            val first = text.substring(0, index).trim()
            val second = text.substring(index).trim()
            if (first.isEmpty() || second.isEmpty()) {
                continue
            }
            splits += Candidate(first, second, allowParts = true)
        }
        return splits
    }

    /** One component before the axis is known: its hemisphere and signed value. */
    private data class Half(
        val hemisphere: Char?,
        val value: Double,
    )

    private fun parseHalf(
        raw: String,
        allowParts: Boolean,
    ): Half? {
        var text = raw.trim()
        if (text.isEmpty()) {
            return null
        }
        var hemisphere: Char? = null
        if (text.first().isLetter()) {
            hemisphere = hemisphereOf(text.first()) ?: return null
            text = text.substring(1).trim()
        }
        if (text.isEmpty()) {
            return null
        }
        if (text.last().isLetter()) {
            val suffix = hemisphereOf(text.last()) ?: return null
            if (hemisphere != null && hemisphere != suffix) {
                return null
            }
            hemisphere = suffix
            text = text.dropLast(1).trim()
        }
        var hasSign = false
        var negativeSign = false
        if (text.startsWith("-") || text.startsWith("+")) {
            hasSign = true
            negativeSign = text.startsWith("-")
            text = text.substring(1).trim()
        }
        val cleaned = buildString { for (character in text) append(if (character in SEPARATORS) ' ' else character) }
        if (cleaned.any { it.isLetter() }) {
            return null
        }
        val tokens = cleaned.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.isEmpty() || tokens.size > MAX_COMPONENT_PARTS) {
            return null
        }
        if (!allowParts && tokens.size > 1) {
            return null
        }
        if (tokens.any { token -> token.any { it == '+' || it == '-' } }) {
            return null
        }
        val numbers = tokens.map { token -> token.toDoubleOrNull() ?: return null }
        if (numbers.any { !it.isFinite() || it < 0.0 }) {
            return null
        }
        val magnitude =
            when (numbers.size) {
                1 -> numbers[0]
                2 -> degreesAndMinutes(numbers[0], numbers[1]) ?: return null
                else -> degreesMinutesSeconds(numbers[0], numbers[1], numbers[2]) ?: return null
            }
        val hemisphereNegative = hemisphere == 'S' || hemisphere == 'W'
        if (hemisphere != null && hasSign && negativeSign != hemisphereNegative) {
            return null
        }
        val negative = if (hemisphere != null) hemisphereNegative else negativeSign
        return Half(hemisphere = hemisphere, value = if (negative) -magnitude else magnitude)
    }

    private fun degreesAndMinutes(
        degrees: Double,
        minutes: Double,
    ): Double? {
        if (!isWholeDegrees(degrees) || minutes !in 0.0..<MINUTES_PER_DEGREE) {
            return null
        }
        return degrees + minutes / MINUTES_PER_DEGREE
    }

    private fun degreesMinutesSeconds(
        degrees: Double,
        minutes: Double,
        seconds: Double,
    ): Double? {
        if (!isWholeDegrees(degrees) ||
            minutes !in 0.0..<MINUTES_PER_DEGREE ||
            seconds !in 0.0..<SECONDS_PER_MINUTE
        ) {
            return null
        }
        return degrees + minutes / MINUTES_PER_DEGREE + seconds / SECONDS_PER_MINUTE / MINUTES_PER_DEGREE
    }

    private fun isWholeDegrees(degrees: Double): Boolean = degrees == floor(degrees)

    /** Latitudes first, longitudes second; a longitude-first pair is refused. */
    private fun pair(
        first: Half,
        second: Half,
    ): GeoPoint? {
        val firstAxis = axisOf(first.hemisphere) ?: axisOf(second.hemisphere)?.let { opposite(it) } ?: Axis.LATITUDE
        val secondAxis = axisOf(second.hemisphere) ?: opposite(firstAxis)
        if (firstAxis != Axis.LATITUDE || secondAxis != Axis.LONGITUDE) {
            return null
        }
        val latitude = first.value
        val longitude = second.value
        if (abs(latitude) > MAX_LATITUDE || abs(longitude) > MAX_LONGITUDE) {
            return null
        }
        return GeoPoint(latitude = latitude, longitude = longitude)
    }

    private enum class Axis {
        LATITUDE,
        LONGITUDE,
    }

    private fun axisOf(hemisphere: Char?): Axis? =
        when (hemisphere) {
            'N', 'S' -> Axis.LATITUDE
            'E', 'W' -> Axis.LONGITUDE
            else -> null
        }

    private fun opposite(axis: Axis): Axis = if (axis == Axis.LATITUDE) Axis.LONGITUDE else Axis.LATITUDE

    private fun hemisphereOf(letter: Char): Char? =
        when (val upper = letter.uppercaseChar()) {
            'N', 'S', 'E', 'W' -> upper
            else -> null
        }

    private val WHITESPACE = Regex("\\s+")

    /** Degree, minute and second marks, in the plain and typographic forms. */
    private val SEPARATORS = setOf('°', 'º', '\'', '′', '"', '″')

    /** A character that, on its own, marks the text as a coordinate. */
    private val MARKERS = SEPARATORS + setOf('+', '-', '.')

    private const val PAIR_PARTS = 2
    private const val MAX_COMPONENT_PARTS = 3
    private const val MINUTES_PER_DEGREE = 60.0
    private const val SECONDS_PER_MINUTE = 60.0
    private const val MAX_LATITUDE = 90.0
    private const val MAX_LONGITUDE = 180.0
}
