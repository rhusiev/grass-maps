package nl.r1a.grassmap.plugins.links

/** The strict parsing helpers the link parsers share (T-0211). */
internal object LinkParsing {
    /**
     * A decimal number in the forms a coordinate takes. The pattern is
     * deliberate: `Double.parseDouble` would also accept a hex float, a `d` or
     * `f` suffix and a leading space, none of which a link may carry.
     */
    private val NUMBER = Regex("^[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?$")

    private const val HIGH_NIBBLE_SHIFT = 4
    private const val PERCENT_ESCAPE_LENGTH = 3
    private const val HEX_LETTER_BASE = 10

    /** The finite double [text] spells, or null when it is not a plain decimal. */
    fun double(text: String): Double? = if (NUMBER.matches(text)) text.toDoubleOrNull()?.takeIf { it.isFinite() } else null

    /** The first value of [key] in an `a=b&c=d` [query], or null when absent. */
    fun queryValue(
        query: String,
        key: String,
    ): String? =
        query.split('&').firstNotNullOfOrNull { pair ->
            if (pair.substringBefore('=') == key) pair.substringAfter('=', "") else null
        }

    /**
     * Decodes `%HH` escapes as UTF-8. An escape that is not two hex digits is
     * kept as it is written, so malformed input becomes a label instead of a
     * failure.
     */
    fun percentDecode(text: String): String {
        val result = StringBuilder(text.length)
        val pending = mutableListOf<Byte>()
        var index = 0
        while (index < text.length) {
            val character = text[index]
            val high = if (character == '%' && index + 2 < text.length) hexValue(text[index + 1]) else -1
            val low = if (high >= 0) hexValue(text[index + 2]) else -1
            if (high >= 0 && low >= 0) {
                pending += ((high shl HIGH_NIBBLE_SHIFT) or low).toByte()
                index += PERCENT_ESCAPE_LENGTH
                continue
            }
            appendEscapes(result, pending)
            result.append(character)
            index += 1
        }
        appendEscapes(result, pending)
        return result.toString()
    }

    /** Writes the escaped bytes collected so far as one UTF-8 sequence. */
    private fun appendEscapes(
        result: StringBuilder,
        pending: MutableList<Byte>,
    ) {
        if (pending.isEmpty()) {
            return
        }
        result.append(String(pending.toByteArray(), Charsets.UTF_8))
        pending.clear()
    }

    private fun hexValue(character: Char): Int =
        when (character) {
            in '0'..'9' -> character - '0'
            in 'a'..'f' -> character - 'a' + HEX_LETTER_BASE
            in 'A'..'F' -> character - 'A' + HEX_LETTER_BASE
            else -> -1
        }
}
