package nl.r1a.grassmap.core.services

/**
 * Removes coordinates, search queries and credentials from log text.
 *
 * The ring logs every call site, including ones added later, so the guarantee
 * that a diagnostic bundle never holds a position, a query or a token cannot
 * rest on each caller remembering [nl.r1a.grassmap.plugin.api.service.Logger]'s
 * contract. Redaction happens once, before the record enters the ring: the raw
 * text is never stored, and the bundle writer copies only stored records.
 */
class LogScrubber {
    fun scrub(text: String): String {
        var result = BEARER.replace(text) { match -> "${match.groupValues[1]} [redacted]" }
        result = JWT.replace(result, REDACTED)
        result = LABELLED_SECRET.replace(result) { match -> "${match.groupValues[1]}=$REDACTED" }
        result = LABELLED_QUERY.replace(result) { match -> "${match.groupValues[1]}=$REDACTED" }
        result = LABELLED_COORDINATE.replace(result) { match -> "${match.groupValues[1]}=$REDACTED" }
        result = GEO_URI.replace(result, "geo:$REDACTED")
        result = COORDINATE_PAIR.replace(result, REDACTED)
        return result
    }

    private companion object {
        const val REDACTED = "[redacted]"

        /**
         * `Bearer` followed by the credential, so an `Authorization: Bearer`
         * header is redacted before the header-name rule runs.
         */
        val BEARER: Regex = Regex("""(?i)\b(bearer)\s+[A-Za-z0-9._~+/=-]+""")

        /** A JWT anywhere in the text, even without a label. */
        val JWT: Regex = Regex("""\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""")

        /** `token=...`, `authorization: ...`, `api_key=...` and their variants. */
        val LABELLED_SECRET: Regex =
            Regex(
                """(?i)\b(access[_-]?token|refresh[_-]?token|auth[_-]?token|api[_-]?key|token|""" +
                    """authorization|secret|password)\b\s*[=:]\s*("[^"]*"|'[^']*'|[^\s,;]+)""",
            )

        /** `query=...`, `q=...`, `search=...` and the keystroke form. */
        val LABELLED_QUERY: Regex =
            Regex(
                """(?i)\b(query|q|search|keystroke)\b\s*[=:]\s*("[^"]*"|'[^']*'|[^\s,;]+)""",
            )

        /** `lat=...`, `longitude: ...` and the other spellings of a coordinate. */
        val LABELLED_COORDINATE: Regex =
            Regex(
                """(?i)\b(lat|latitude|lon|lng|long|longitude)\b\s*[=:]\s*""" +
                    """"?(-?\d{1,3}(?:\.\d+)?)"?""",
            )

        /** A geo URI, with or without an altitude and a query part. */
        val GEO_URI: Regex =
            Regex("""(?i)\bgeo:-?\d{1,3}(?:\.\d+)?,-?\d{1,3}(?:\.\d+)?(?:,[\d.]+)?(?:;[\w=;.-]+)?""")

        /**
         * A bare decimal pair that reads as a latitude and longitude. Four
         * decimal places is roughly eleven metres, so ordinary log numbers do
         * not match.
         */
        val COORDINATE_PAIR: Regex =
            Regex("""(?<![\d.])-?\d{1,3}\.\d{4,}\s*[,;]\s*-?\d{1,3}\.\d{4,}(?![\d.])""")
    }
}
