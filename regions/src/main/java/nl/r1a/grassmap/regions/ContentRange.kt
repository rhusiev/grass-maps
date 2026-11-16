package nl.r1a.grassmap.regions

/**
 * A parsed `Content-Range` header of the `bytes` unit. [first] and [last] are
 * both -1 for the `bytes star/<size>` form a `416` carries. [total] is null when
 * the server wrote `*` for the size.
 */
internal data class ContentRange(
    val first: Long,
    val last: Long,
    val total: Long?,
) {
    companion object {
        private val SATISFIED = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""")
        private val UNSATISFIED = Regex("""bytes \*/(\d+)""")

        /** Returns null for a header that is absent, malformed or not in bytes. */
        fun parse(header: String?): ContentRange? {
            val value = header?.trim() ?: return null
            UNSATISFIED.matchEntire(value)?.let { match ->
                val total = match.groupValues[1].toLongOrNull() ?: return null
                return ContentRange(first = -1, last = -1, total = total)
            }
            val match = SATISFIED.matchEntire(value) ?: return null
            val first = match.groupValues[1].toLongOrNull() ?: return null
            val last = match.groupValues[2].toLongOrNull() ?: return null
            if (last < first) {
                return null
            }
            val total = match.groupValues[3].takeIf { it != "*" }?.toLongOrNull()
            return ContentRange(first, last, total)
        }
    }
}
