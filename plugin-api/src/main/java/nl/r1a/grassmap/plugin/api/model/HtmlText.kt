package nl.r1a.grassmap.plugin.api.model

import java.util.Locale

/**
 * The safe renderer for a stored description that may carry simple HTML
 * (T-0214, T-0414).
 *
 * Formatting does not survive as markup: `b`, `strong`, `i`, `em` and `u` keep
 * their text, `br` and `p` become a line break, and every other tag is dropped
 * with its attributes while its text content stays. `script` and `style` drop
 * their content as well, so a hostile description cannot inject code. A tag
 * that never closes drops only the malformed tail. Known entities are decoded;
 * an unknown or malformed entity stays a literal ampersand. A null or empty
 * result returns null.
 *
 * The untrusted input is capped before any tag or entity scan and the output is
 * capped at [MAX_LENGTH] characters, so one pathological note cannot make the
 * scan or the screen's state grow without bound.
 */
object HtmlText {
    const val MAX_LENGTH: Int = 2000

    private const val MAX_INPUT_LENGTH: Int = 20_000

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

    /** Renders [raw] to plain text, or null when it holds no text. */
    fun render(raw: String?): String? {
        if (raw.isNullOrEmpty()) {
            return null
        }
        val input = raw.take(MAX_INPUT_LENGTH)
        val rendered = StringBuilder()
        var index = 0
        var suppressed: String? = null
        var full = false
        while (index < input.length && !full) {
            val character = input[index]
            if (suppressed != null) {
                // Inside script or style only the closing tag matters; its
                // words, entities and comparisons are all dropped.
                if (character == '<') {
                    val tag = readTag(input, index)
                    if (tag == null) {
                        index = input.length
                    } else {
                        index = tag.end
                        if (tag.closing && tag.name == suppressed) {
                            suppressed = null
                        }
                    }
                } else {
                    index += 1
                }
                continue
            }
            when (character) {
                '<' -> {
                    if (!isTagStart(input, index)) {
                        full = append(rendered, character)
                        index += 1
                    } else {
                        val tag = readTag(input, index)
                        if (tag == null) {
                            index = input.length
                        } else {
                            index = tag.end
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

                '&' -> {
                    val entity = readEntity(input, index)
                    if (entity == null) {
                        full = append(rendered, character)
                        index += 1
                    } else {
                        full = append(rendered, entity.value)
                        index = entity.end
                    }
                }

                else -> {
                    val codePoint = input.codePointAt(index)
                    full = appendCodePoint(rendered, codePoint)
                    index += Character.charCount(codePoint)
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

    /** Appends one character and reports whether the length cap is reached. */
    private fun append(
        target: StringBuilder,
        value: Char,
    ): Boolean {
        if (target.length >= MAX_LENGTH) {
            return true
        }
        target.append(value)
        return target.length >= MAX_LENGTH
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
        return target.length >= MAX_LENGTH
    }

    /** Appends one code point whole, or reports that the length cap is reached. */
    private fun appendCodePoint(
        target: StringBuilder,
        codePoint: Int,
    ): Boolean {
        val width = Character.charCount(codePoint)
        if (target.length + width > MAX_LENGTH) {
            return true
        }
        target.appendCodePoint(codePoint)
        return target.length >= MAX_LENGTH
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
}
