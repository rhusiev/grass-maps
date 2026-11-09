package nl.r1a.grassmap.plugins.links

import nl.r1a.grassmap.plugin.api.model.GeoPoint

/**
 * The `geo:` form of RFC 5870 (T-0211).
 *
 * The accepted body is `lat,lon`, optionally with an altitude field and with
 * `;` parameters, plus an optional `?q=` query. The query names the position
 * again as `lat,lon(label)`, or carries free text; only its label is kept,
 * because the main coordinate pair is what the map centres on.
 */
internal object GeoUri {
    private const val LATITUDE_FIELD = 0
    private const val LONGITUDE_FIELD = 1
    private const val MIN_COORDINATE_FIELDS = 2
    private const val MAX_COORDINATE_FIELDS = 3
    private const val QUERY_KEY = "q"
    private const val LABEL_OPEN = '('
    private const val LABEL_CLOSE = ')'

    private const val MIN_LATITUDE = -90.0
    private const val MAX_LATITUDE = 90.0
    private const val MIN_LONGITUDE = -180.0
    private const val MAX_LONGITUDE = 180.0

    /** Parses the body after `geo:`. */
    fun parse(body: String): LinkResult {
        val withoutFragment = body.substringBefore('#')
        val query = withoutFragment.substringAfter('?', "")
        val location = withoutFragment.substringBefore('?')
        // `;u=` and `;crs=` are RFC parameters the position does not depend on.
        val fields = location.substringBefore(';').split(',')
        if (fields.size !in MIN_COORDINATE_FIELDS..MAX_COORDINATE_FIELDS) {
            return LinkResult.Failure(LinkFailure.MALFORMED)
        }
        val latitude = LinkParsing.double(fields[LATITUDE_FIELD])
        val longitude = LinkParsing.double(fields[LONGITUDE_FIELD])
        if (latitude == null || longitude == null) {
            return LinkResult.Failure(LinkFailure.MALFORMED)
        }
        if (latitude !in MIN_LATITUDE..MAX_LATITUDE || longitude !in MIN_LONGITUDE..MAX_LONGITUDE) {
            return LinkResult.Failure(LinkFailure.OUT_OF_RANGE)
        }
        return LinkResult.Success(
            LinkTarget.Point(
                point = GeoPoint(latitude, longitude),
                zoom = LinkRouter.DEFAULT_POINT_ZOOM,
                label = label(LinkParsing.queryValue(query, QUERY_KEY)),
            ),
        )
    }

    /** The label inside `lat,lon(label)`, or the whole query when it is free text. */
    private fun label(query: String?): String? {
        val decoded = query?.let(LinkParsing::percentDecode)?.trim().orEmpty()
        if (decoded.isEmpty()) {
            return null
        }
        val open = decoded.indexOf(LABEL_OPEN)
        if (open < 0 || !decoded.endsWith(LABEL_CLOSE)) {
            return decoded
        }
        return decoded.substring(open + 1, decoded.length - 1).trim().ifEmpty { null }
    }
}
