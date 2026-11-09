package nl.r1a.grassmap.plugins.favourites.ui

import nl.r1a.grassmap.plugin.api.model.FavouriteBrowseState
import nl.r1a.grassmap.plugin.api.model.FavouriteMatch
import nl.r1a.grassmap.plugin.api.model.FavouritePointRow
import nl.r1a.grassmap.plugin.api.model.FavouritePointSummary
import nl.r1a.grassmap.plugin.api.model.FavouriteSort
import java.text.Normalizer
import java.util.Locale

/**
 * The browsing pipeline of the favourites screen (T-0214): search, sort and the
 * safe renderer for a description.
 *
 * Search is case- and accent-insensitive. A query is trimmed, lowercased with
 * [Locale.ROOT] and decomposed with NFD, and every combining mark is removed
 * from both the query and the text, so `cafe` finds `Café` and `café` finds
 * `Cafe`. A blank query keeps every point. The result is never an error: no
 * match is an empty row list.
 *
 * Sorting is fixed and locale-independent, as [FavouriteSort] documents.
 * Descriptions are rendered to plain text, so the shell never has to trust
 * markup; see [renderDescription].
 */
internal object FavouritesBrowser {
    /**
     * Applies [query] and [sort] to the library's file-order [points] and
     * returns the rows the screen draws.
     */
    fun browse(
        points: List<FavouritePointSummary>,
        query: String,
        sort: FavouriteSort,
    ): FavouriteBrowseState {
        val needle = normalize(query)
        val matches =
            if (needle.isEmpty()) {
                points.map { point -> point to FavouriteMatch.NONE }
            } else {
                points.mapNotNull { point -> match(point, needle)?.let { reason -> point to reason } }
            }
        val reasons = matches.toMap()
        val ordered = order(matches.map { it.first }, sort)
        return FavouriteBrowseState(
            query = query,
            sort = sort,
            rows = ordered.map { point -> row(point, reasons[point] ?: FavouriteMatch.NONE) },
        )
    }

    /**
     * Trims, lowercases and strips combining marks, so `cafe` and `Café` fold
     * to the same search text. The sort compares lowercased names without this
     * accent folding, as [FavouriteSort] documents.
     */
    internal fun normalize(text: String): String {
        val lower = text.trim().lowercase(Locale.ROOT)
        if (lower.isEmpty()) {
            return ""
        }
        val decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD)
        val builder = StringBuilder(decomposed.length)
        var index = 0
        while (index < decomposed.length) {
            val codePoint = decomposed.codePointAt(index)
            if (!isMark(codePoint)) {
                builder.appendCodePoint(codePoint)
            }
            index += Character.charCount(codePoint)
        }
        return builder.toString()
    }

    /**
     * Renders a stored description to plain text (T-0214).
     *
     * Simple formatting is accepted but does not survive as markup: `b`,
     * `strong`, `i`, `em` and `u` keep their text, `br` and `p` become a line
     * break, and every other tag is dropped with its attributes while its text
     * content stays. `script` and `style` drop their content as well, so a
     * hostile description cannot inject code. A tag that never closes drops
     * only the malformed tail. Known entities are decoded; an unknown or
     * malformed entity stays a literal ampersand. A null or empty result
     * returns null.
     *
     * The result is capped at [MAX_DESCRIPTION_LENGTH] characters so one
     * pathological note cannot grow the screen's state without bound.
     */
    internal fun renderDescription(raw: String?): String? {
        if (raw.isNullOrEmpty()) {
            return null
        }
        val rendered = StringBuilder()
        var index = 0
        var suppressed: String? = null
        var full = false
        while (index < raw.length && !full) {
            when (val character = raw[index]) {
                '<' -> {
                    if (!isTagStart(raw, index)) {
                        full = append(rendered, character)
                        index += 1
                    } else {
                        val tag = readTag(raw, index)
                        if (tag == null) {
                            index = raw.length
                        } else {
                            index = tag.end
                            if (suppressed != null) {
                                if (tag.closing && tag.name == suppressed) {
                                    suppressed = null
                                }
                            } else {
                                when (tag.name) {
                                    SCRIPT_TAG, STYLE_TAG -> {
                                        if (!tag.closing && !tag.selfClosing) {
                                            suppressed = tag.name
                                        }
                                    }

                                    BREAK_TAG, PARAGRAPH_TAG -> {
                                        full = breakLine(rendered)
                                    }

                                    else -> {
                                        Unit
                                    }
                                }
                            }
                        }
                    }
                }

                '&' -> {
                    val entity = readEntity(raw, index)
                    if (entity == null) {
                        full = append(rendered, character)
                        index += 1
                    } else {
                        full = append(rendered, entity.value)
                        index = entity.end
                    }
                }

                else -> {
                    if (suppressed == null) {
                        val codePoint = raw.codePointAt(index)
                        full = appendCodePoint(rendered, codePoint)
                        index += Character.charCount(codePoint)
                    } else {
                        index += 1
                    }
                }
            }
        }
        val text =
            rendered
                .toString()
                .replace(NEWLINE_RUN, "\n\n")
                .trim()
        return text.ifEmpty { null }
    }

    private fun row(
        point: FavouritePointSummary,
        reason: FavouriteMatch,
    ): FavouritePointRow =
        FavouritePointRow(
            id = point.id,
            name = point.name,
            list = point.list,
            special = point.special,
            description = renderDescription(point.description),
            match = reason,
        )

    private fun match(
        point: FavouritePointSummary,
        needle: String,
    ): FavouriteMatch? {
        val byName = normalize(point.name).contains(needle)
        val byDescription = normalize(renderDescription(point.description).orEmpty()).contains(needle)
        return when {
            byName && byDescription -> FavouriteMatch.BOTH
            byName -> FavouriteMatch.NAME
            byDescription -> FavouriteMatch.DESCRIPTION
            else -> null
        }
    }

    private fun order(
        points: List<FavouritePointSummary>,
        sort: FavouriteSort,
    ): List<FavouritePointSummary> {
        if (sort == FavouriteSort.INSERTION) {
            return points
        }
        val indexed = points.withIndex()
        val comparator = comparatorFor(sort)
        return indexed.sortedWith(comparator).map { entry -> entry.value }
    }

    private fun comparatorFor(sort: FavouriteSort): Comparator<IndexedValue<FavouritePointSummary>> =
        when (sort) {
            FavouriteSort.INSERTION -> {
                compareBy { entry -> entry.index }
            }

            FavouriteSort.NAME_ASCENDING -> {
                compareBy<IndexedValue<FavouritePointSummary>> { entry -> nameKey(entry.value) }.thenBy { entry -> entry.index }
            }

            FavouriteSort.NAME_DESCENDING -> {
                Comparator<IndexedValue<FavouritePointSummary>> { first, second ->
                    val byName = nameKey(second.value).compareTo(nameKey(first.value))
                    if (byName != 0) byName else first.index.compareTo(second.index)
                }
            }

            FavouriteSort.LIST_THEN_NAME -> {
                compareBy<IndexedValue<FavouritePointSummary>> { entry -> entry.value.list != null }
                    .thenBy { entry ->
                        entry.value.list
                            ?.lowercase(Locale.ROOT)
                            .orEmpty()
                    }.thenBy { entry -> nameKey(entry.value) }
                    .thenBy { entry -> entry.index }
            }
        }

    private fun nameKey(point: FavouritePointSummary): String = point.name.lowercase(Locale.ROOT)

    private fun isMark(codePoint: Int): Boolean =
        when (Character.getType(codePoint)) {
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(),
            -> true

            else -> false
        }

    /** Appends one character and reports whether the length cap is reached. */
    private fun append(
        target: StringBuilder,
        value: Char,
    ): Boolean {
        if (target.length >= MAX_DESCRIPTION_LENGTH) {
            return true
        }
        target.append(value)
        return target.length >= MAX_DESCRIPTION_LENGTH
    }

    /** Appends one decoded entity, keeping surrogate pairs whole. */
    private fun append(
        target: StringBuilder,
        value: String,
    ): Boolean {
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            if (appendCodePoint(target, codePoint)) {
                return true
            }
            index += Character.charCount(codePoint)
        }
        return target.length >= MAX_DESCRIPTION_LENGTH
    }

    /** Appends one code point whole, or reports that the length cap is reached. */
    private fun appendCodePoint(
        target: StringBuilder,
        codePoint: Int,
    ): Boolean {
        val width = Character.charCount(codePoint)
        if (target.length + width > MAX_DESCRIPTION_LENGTH) {
            return true
        }
        target.appendCodePoint(codePoint)
        return target.length >= MAX_DESCRIPTION_LENGTH
    }

    /** A `<` opens a tag only when a name, a close mark, a comment or a PI follows. */
    private fun isTagStart(
        text: String,
        start: Int,
    ): Boolean {
        if (start + 1 >= text.length) {
            return false
        }
        val next = text[start + 1]
        return next.isLetter() || next == CLOSING_PREFIX.first() || next == BANG || next == QUESTION
    }

    /** Adds one line break, skipping a leading one and repeated ones. */
    private fun breakLine(target: StringBuilder): Boolean {
        if (target.isEmpty()) {
            return false
        }
        if (target.last() != NEWLINE) {
            return append(target, NEWLINE)
        }
        return false
    }

    private fun readTag(
        text: String,
        start: Int,
    ): Tag? {
        if (text.startsWith(COMMENT_START, start)) {
            val end = text.indexOf(COMMENT_END, start + COMMENT_START.length)
            val commentEnd = if (end < 0) text.length else end + COMMENT_END.length
            return Tag(name = COMMENT_TAG, closing = false, selfClosing = false, end = commentEnd)
        }
        var index = start + 1
        var quote: Char? = null
        while (index < text.length) {
            val character = text[index]
            if (quote != null) {
                if (character == quote) {
                    quote = null
                }
            } else {
                when (character) {
                    '\'', '"' -> quote = character
                    '>' -> return parseTag(text.substring(start + 1, index), index + 1)
                }
            }
            index += 1
        }
        return null
    }

    private fun parseTag(
        body: String,
        end: Int,
    ): Tag {
        val trimmed = body.trim()
        val selfClosing = trimmed.endsWith(SELF_CLOSING)
        val core = trimmed.removeSuffix(SELF_CLOSING).trim()
        val closing = core.startsWith(CLOSING_PREFIX)
        val name =
            core.removePrefix(CLOSING_PREFIX).trim().takeWhile { character ->
                character.isLetterOrDigit() ||
                    character == TAG_NAME_SEPARATOR
            }
        return Tag(name.lowercase(Locale.ROOT), closing, selfClosing, end)
    }

    private fun readEntity(
        text: String,
        start: Int,
    ): Entity? {
        val semicolon = text.indexOf(ENTITY_END, start + 1)
        if (semicolon < 0 || semicolon > start + MAX_ENTITY_LENGTH) {
            return null
        }
        val body = text.substring(start + 1, semicolon)
        if (body.isEmpty()) {
            return null
        }
        val value =
            when {
                body == "amp" -> "&"

                body == "lt" -> "<"

                body == "gt" -> ">"

                body == "quot" -> "\""

                body == "apos" -> "'"

                body == "nbsp" -> "\u00A0"

                body.startsWith(
                    HEX_ENTITY_PREFIX,
                    ignoreCase = true,
                ) -> body.drop(HEX_ENTITY_PREFIX.length).toIntOrNull(HEX_RADIX)?.toCodePoint()

                body.startsWith(DECIMAL_ENTITY_PREFIX) -> body.drop(DECIMAL_ENTITY_PREFIX.length).toIntOrNull()?.toCodePoint()

                else -> null
            } ?: return null
        return Entity(value, semicolon + 1)
    }

    private fun Int.toCodePoint(): String? =
        if (this in MIN_CODE_POINT..Character.MAX_CODE_POINT && (this <= MAX_BASIC_CODE_POINT || this >= MIN_SUPPLEMENTARY_CODE_POINT)) {
            Character.toChars(this).concatToString()
        } else {
            null
        }

    private data class Tag(
        val name: String,
        val closing: Boolean,
        val selfClosing: Boolean,
        val end: Int,
    )

    private data class Entity(
        val value: String,
        val end: Int,
    )

    internal const val MAX_DESCRIPTION_LENGTH: Int = 2000

    private const val SCRIPT_TAG = "script"
    private const val STYLE_TAG = "style"
    private const val BREAK_TAG = "br"
    private const val PARAGRAPH_TAG = "p"
    private const val COMMENT_TAG = "!--"
    private const val COMMENT_START = "<!--"
    private const val COMMENT_END = "-->"
    private const val CLOSING_PREFIX = "/"
    private const val BANG = '!'
    private const val QUESTION = '?'
    private const val SELF_CLOSING = "/"
    private const val TAG_NAME_SEPARATOR = '-'
    private const val ENTITY_END = ';'
    private const val HEX_ENTITY_PREFIX = "#x"
    private const val DECIMAL_ENTITY_PREFIX = "#"
    private const val HEX_RADIX = 16
    private const val MAX_ENTITY_LENGTH = 12
    private const val MIN_CODE_POINT = 1
    private const val MAX_BASIC_CODE_POINT = 0xD7FF
    private const val MIN_SUPPLEMENTARY_CODE_POINT = 0xE000
    private const val NEWLINE = '\n'
    private val NEWLINE_RUN = Regex("\n{3,}")
}
