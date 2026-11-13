package nl.r1a.grassmap.plugin.api.model

import java.util.Locale

/**
 * The safe renderer for a stored description that may carry simple HTML
 * (T-0214, T-0414).
 *
 * The result is native styled text, never markup and never a document: [renderRich]
 * keeps `b`, `strong`, `i`, `em`, `p`, `br`, `ul`, `ol`, `li` and `a` as
 * [RichText] runs, and every other tag is dropped. A link keeps only its `href`;
 * every other attribute, including an event handler, is ignored. A link is kept
 * only when its address starts with `http://` or `https://`, so a
 * `javascript:` or `data:` address leaves plain text and never opens. `script`,
 * `style`, `iframe`, `svg`, `form` and `img` are dropped with their content, so
 * an import cannot run code, load a remote image or fire a tracking pixel.
 *
 * [render] returns the same text without the style runs, for a caller that only
 * needs plain text. Known entities are decoded; an unknown or malformed entity
 * stays a literal ampersand. A tag that never closes drops only the malformed
 * tail. A null or empty result returns null.
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
    private const val IFRAME_TAG = "iframe"
    private const val SVG_TAG = "svg"
    private const val FORM_TAG = "form"
    private const val IMAGE_TAG = "img"
    private const val BREAK_TAG = "br"
    private const val PARAGRAPH_TAG = "p"
    private const val BOLD_TAG = "b"
    private const val STRONG_TAG = "strong"
    private const val ITALIC_TAG = "i"
    private const val EMPHASIS_TAG = "em"
    private const val LINK_TAG = "a"
    private const val BULLET_LIST_TAG = "ul"
    private const val NUMBERED_LIST_TAG = "ol"
    private const val LIST_ITEM_TAG = "li"
    private const val HREF_ATTRIBUTE = "href"
    private const val COMMENT_TAG = "!--"
    private const val COMMENT_START = "<!--"
    private const val COMMENT_END = "-->"
    private const val CLOSING_PREFIX = "/"
    private const val BANG = '!'
    private const val QUESTION = '?'
    private const val SELF_CLOSING = "/"
    private const val TAG_NAME_SEPARATOR = '-'
    private const val TAG_NAME_NAMESPACE = ':'
    private const val ENTITY_END = ';'
    private const val HEX_ENTITY_PREFIX = "#x"
    private const val DECIMAL_ENTITY_PREFIX = "#"
    private const val HEX_RADIX = 16
    private const val MAX_ENTITY_LENGTH = 12
    private const val MIN_CODE_POINT = 1
    private const val MAX_BASIC_CODE_POINT = 0xD7FF
    private const val MIN_SUPPLEMENTARY_CODE_POINT = 0xE000
    private const val NEWLINE = '\n'
    private const val BULLET_PREFIX = "• "
    private const val NUMBERED_PREFIX = ". "
    private const val LINE_BREAK = 1
    private const val SINGLE_ITEM = 1
    private const val NO_SPACE = 0

    private val SAFE_LINK =
        Regex("^https?://[^\\s/?#]+", RegexOption.IGNORE_CASE)

    private val SUPPRESSED_TAGS =
        setOf(SCRIPT_TAG, STYLE_TAG, IFRAME_TAG, SVG_TAG, FORM_TAG)

    private val BOLD_TAGS = setOf(BOLD_TAG, STRONG_TAG)
    private val ITALIC_TAGS = setOf(ITALIC_TAG, EMPHASIS_TAG)

    /** Renders [raw] to plain text, or null when it holds no text. */
    fun render(raw: String?): String? = renderRich(raw)?.text

    /** Renders [raw] to native styled text, or null when it holds no text. */
    fun renderRich(raw: String?): RichText? {
        if (raw.isNullOrEmpty()) {
            return null
        }
        val input = raw.take(MAX_INPUT_LENGTH)
        val builder = Builder()
        var index = 0
        var suppressed: String? = null
        while (index < input.length && !builder.full) {
            val character = input[index]
            if (suppressed != null) {
                // Inside a dropped element only its closing tag matters; its
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
                        builder.append(character)
                        index += 1
                    } else {
                        val tag = readTag(input, index)
                        if (tag == null) {
                            index = input.length
                        } else {
                            index = tag.end
                            if (!tag.closing && SUPPRESSED_TAGS.contains(tag.name) && !tag.selfClosing) {
                                suppressed = tag.name
                            } else {
                                builder.tag(tag)
                            }
                        }
                    }
                }

                '&' -> {
                    val entity = readEntity(input, index)
                    if (entity == null) {
                        builder.append(character)
                        index += 1
                    } else {
                        builder.append(entity.value)
                        index = entity.end
                    }
                }

                '\r' -> {
                    index += 1
                }

                '\n' -> {
                    builder.lineBreak()
                    index += 1
                }

                else -> {
                    val codePoint = input.codePointAt(index)
                    builder.appendCodePoint(codePoint)
                    index += Character.charCount(codePoint)
                }
            }
        }
        return builder.finish()
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
        var core = body.trim()
        val selfClosing = core.endsWith(SELF_CLOSING)
        if (selfClosing) {
            core = core.removeSuffix(SELF_CLOSING).trimEnd()
        }
        val closing = core.startsWith(CLOSING_PREFIX)
        if (closing) {
            core = core.removePrefix(CLOSING_PREFIX).trimStart()
        }
        val nameLength =
            core
                .indexOfFirst { character ->
                    !(
                        character.isLetterOrDigit() ||
                            character == TAG_NAME_SEPARATOR ||
                            character == TAG_NAME_NAMESPACE
                    )
                }.let { index -> if (index < 0) core.length else index }
        val name = core.take(nameLength).lowercase(Locale.ROOT)
        val attributes = if (closing) emptyMap() else readAttributes(core, nameLength)
        return Tag(name, closing, selfClosing, end, attributes)
    }

    /** Reads `name="value"` pairs; every value is decoded, but a caller keeps only `href`. */
    private fun readAttributes(
        text: String,
        start: Int,
    ): Map<String, String> {
        val attributes = mutableMapOf<String, String>()
        var index = start
        while (index < text.length) {
            while (index < text.length && (text[index].isWhitespace() || text[index] == SELF_CLOSING.first())) {
                index += 1
            }
            val nameStart = index
            while (index < text.length && isAttributeNameCharacter(text[index])) {
                index += 1
            }
            if (index == nameStart) {
                index += 1
                continue
            }
            val name = text.substring(nameStart, index).lowercase(Locale.ROOT)
            while (index < text.length && text[index].isWhitespace()) {
                index += 1
            }
            if (index >= text.length || text[index] != '=') {
                if (!attributes.containsKey(name)) {
                    attributes[name] = ""
                }
                continue
            }
            index += 1
            while (index < text.length && text[index].isWhitespace()) {
                index += 1
            }
            val quote = text.getOrNull(index)?.takeIf { character -> character == '\'' || character == '"' }
            val valueStart =
                if (quote == null) {
                    index
                } else {
                    index += 1
                    index
                }
            val valueEnd =
                if (quote == null) {
                    while (index < text.length && !text[index].isWhitespace() && text[index] != SELF_CLOSING.first()) {
                        index += 1
                    }
                    index
                } else {
                    while (index < text.length && text[index] != quote) {
                        index += 1
                    }
                    val end = index
                    if (index < text.length) {
                        index += 1
                    }
                    end
                }
            if (!attributes.containsKey(name)) {
                attributes[name] = decodeEntities(text.substring(valueStart, valueEnd))
            }
        }
        return attributes
    }

    private fun isAttributeNameCharacter(character: Char): Boolean =
        character.isLetterOrDigit() ||
            character == TAG_NAME_SEPARATOR ||
            character == TAG_NAME_NAMESPACE ||
            character == '_'

    private fun decodeEntities(text: String): String {
        val decoded = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            if (text[index] == '&') {
                val entity = readEntity(text, index)
                if (entity != null) {
                    decoded.append(entity.value)
                    index = entity.end
                    continue
                }
            }
            decoded.append(text[index])
            index += 1
        }
        return decoded.toString()
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
        val attributes: Map<String, String> = emptyMap(),
    )

    private data class Entity(
        val value: String,
        val end: Int,
    )

    private data class OpenSpan(
        val style: RichTextStyle,
        val href: String?,
        val start: Int,
    )

    private data class ListState(
        val numbered: Boolean,
        var next: Int = SINGLE_ITEM,
    )

    /**
     * Appends text while keeping the cap, the line breaks, the list markers and
     * the open style runs. A link run is recorded only with a safe address.
     */
    private class Builder {
        private val text = StringBuilder()
        private val spans = mutableListOf<RichTextSpan>()
        private val openSpans = mutableListOf<OpenSpan>()
        private val lists = ArrayDeque<ListState>()
        var full: Boolean = false
            private set

        fun append(character: Char) {
            if (text.isEmpty() && character.isWhitespace()) {
                return
            }
            appendCharacter(character)
        }

        fun append(value: String) {
            var index = 0
            while (index < value.length && !full) {
                val codePoint = value.codePointAt(index)
                appendCodePoint(codePoint)
                index += Character.charCount(codePoint)
            }
        }

        fun appendCodePoint(codePoint: Int) {
            if (text.isEmpty() && Character.isWhitespace(codePoint)) {
                return
            }
            val width = Character.charCount(codePoint)
            if (text.length + width > MAX_LENGTH) {
                full = true
                return
            }
            text.appendCodePoint(codePoint)
            if (text.length >= MAX_LENGTH) {
                full = true
            }
        }

        fun lineBreak() {
            breakLines(LINE_BREAK)
        }

        fun tag(tag: Tag) {
            when (tag.name) {
                BREAK_TAG -> {
                    if (!tag.closing) {
                        lineBreak()
                    }
                }

                PARAGRAPH_TAG -> {
                    breakLines(LINE_BREAK)
                }

                BULLET_LIST_TAG, NUMBERED_LIST_TAG -> {
                    if (tag.closing) {
                        lists.removeLastOrNull()
                        breakLines(LINE_BREAK)
                    } else if (!tag.selfClosing) {
                        breakLines(LINE_BREAK)
                        lists.addLast(ListState(numbered = tag.name == NUMBERED_LIST_TAG))
                    }
                }

                LIST_ITEM_TAG -> {
                    if (!tag.closing) {
                        listItem()
                    }
                }

                LINK_TAG -> {
                    if (tag.closing) {
                        closeStyle(RichTextStyle.LINK)
                    } else if (!tag.selfClosing) {
                        val href = safeLink(tag.attributes[HREF_ATTRIBUTE])
                        if (href != null) {
                            openStyle(RichTextStyle.LINK, href)
                        }
                    }
                }

                IMAGE_TAG -> {
                    Unit
                }

                else -> {
                    when {
                        BOLD_TAGS.contains(tag.name) -> toggle(tag, RichTextStyle.BOLD)
                        ITALIC_TAGS.contains(tag.name) -> toggle(tag, RichTextStyle.ITALIC)
                        else -> Unit
                    }
                }
            }
        }

        fun finish(): RichText? {
            while (openSpans.isNotEmpty()) {
                val open = openSpans.removeAt(openSpans.lastIndex)
                record(open, text.length)
            }
            var end = text.length
            while (end > NO_SPACE && text[end - 1].isWhitespace()) {
                end -= 1
            }
            text.setLength(end)
            val kept =
                spans.mapNotNull { span ->
                    when {
                        span.start >= end -> null
                        span.end > end -> span.copy(end = end)
                        span.start >= span.end -> null
                        else -> span
                    }
                }
            if (end == NO_SPACE) {
                return null
            }
            return RichText(text = text.toString(), spans = kept)
        }

        private fun toggle(
            tag: Tag,
            style: RichTextStyle,
        ) {
            if (tag.closing) {
                closeStyle(style)
            } else if (!tag.selfClosing) {
                openStyle(style, null)
            }
        }

        private fun openStyle(
            style: RichTextStyle,
            href: String?,
        ) {
            openSpans += OpenSpan(style = style, href = href, start = text.length)
        }

        private fun closeStyle(style: RichTextStyle) {
            val index = openSpans.indexOfLast { open -> open.style == style }
            if (index < 0) {
                return
            }
            record(openSpans.removeAt(index), text.length)
        }

        private fun record(
            open: OpenSpan,
            end: Int,
        ) {
            if (end > open.start) {
                spans += RichTextSpan(start = open.start, end = end, style = open.style, href = open.href)
            }
        }

        private fun listItem() {
            lineBreak()
            val list = lists.lastOrNull() ?: return
            val marker =
                if (list.numbered) {
                    "${list.next}$NUMBERED_PREFIX".also { list.next += 1 }
                } else {
                    BULLET_PREFIX
                }
            append(marker)
        }

        private fun breakLines(count: Int) {
            if (text.isEmpty()) {
                return
            }
            var existing = NO_SPACE
            var index = text.length - 1
            while (index >= 0 && text[index] == NEWLINE) {
                existing += 1
                index -= 1
            }
            var missing = count - existing
            while (missing > NO_SPACE && !full) {
                appendCharacter(NEWLINE)
                missing -= 1
            }
        }

        private fun appendCharacter(character: Char) {
            if (text.length >= MAX_LENGTH) {
                full = true
                return
            }
            text.append(character)
            if (text.length >= MAX_LENGTH) {
                full = true
            }
        }

        private fun safeLink(value: String?): String? {
            val address = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (address.any { character -> character.isWhitespace() || character.isISOControl() }) {
                return null
            }
            return address.takeIf { SAFE_LINK.containsMatchIn(it) }
        }
    }
}
