package nl.r1a.grassmap.plugins.links

import nl.r1a.grassmap.plugin.api.model.GeoPoint

/**
 * The `geo:` form of RFC 5870 (T-0211).
 *
 * The accepted body is `lat,lon`, optionally with an altitude field and with
 * `;` parameters, plus an optional query. The query is the form Android
 * documents for sharing a place: `q=lat,lon(label)` names the point to show,
 * and the coordinate pair before the `?` is then only a fallback (`geo:0,0?q=`
 * is standard). A `q` that is a coordinate pair therefore wins over the pair in
 * the body, and its parenthesised suffix becomes the label. A `q` that is free
 * text is a name this app cannot look up offline, so the body pair is the
 * position and the text is only a label. `z=` sets the zoom when it is in the
 * map's range.
 */
internal object GeoUri {
    private const val LATITUDE_FIELD = 0
    private const val LONGITUDE_FIELD = 1
    private const val MIN_COORDINATE_FIELDS = 2
    private const val MAX_COORDINATE_FIELDS = 3
    private const val QUERY_KEY = "q"
    private const val ZOOM_KEY = "z"
    private const val LABEL_OPEN = '('
    private const val LABEL_CLOSE = ')'

    private const val MIN_LATITUDE = -90.0
    private const val MAX_LATITUDE = 90.0
    private const val MIN_LONGITUDE = -180.0
    private const val MAX_LONGITUDE = 180.0

    /** The zoom range the map accepts; a `z` outside it is ignored, not obeyed. */
    private const val MIN_QUERY_ZOOM = 1.0
    private const val MAX_QUERY_ZOOM = 20.0

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
        val bodyLatitude = LinkParsing.double(fields[LATITUDE_FIELD])
        val bodyLongitude = LinkParsing.double(fields[LONGITUDE_FIELD])
        if (bodyLatitude == null || bodyLongitude == null) {
            return LinkResult.Failure(LinkFailure.MALFORMED)
        }
        val queryText = LinkParsing.queryValue(query, QUERY_KEY)
        val target = queryTarget(queryText)
        // A `q` pair is the point to show, so the body pair is only checked
        // when the query does not name one.
        if (target == null && !inRange(bodyLatitude, bodyLongitude)) {
            return LinkResult.Failure(LinkFailure.OUT_OF_RANGE)
        }
        val point = target?.point ?: GeoPoint(bodyLatitude, bodyLongitude)
        if (!inRange(point.latitude, point.longitude)) {
            return LinkResult.Failure(LinkFailure.OUT_OF_RANGE)
        }
        return LinkResult.Success(
            LinkTarget.Point(
                point = point,
                zoom = queryZoom(LinkParsing.queryValue(query, ZOOM_KEY)),
                label = if (target == null) freeTextLabel(queryText) else target.label,
            ),
        )
    }

    /**
     * The `q` value as a point and its label, or null when [q] is not a
     * coordinate pair and is therefore only a name.
     */
    private fun queryTarget(q: String?): QueryTarget? {
        val decoded = q?.let(LinkParsing::percentDecode)?.trim().orEmpty()
        if (decoded.isEmpty()) {
            return null
        }
        val open = decoded.indexOf(LABEL_OPEN)
        val coordinates = if (open >= 0) decoded.substring(0, open) else decoded
        val fields = coordinates.split(',')
        if (fields.size != MIN_COORDINATE_FIELDS) {
            return null
        }
        val latitude = LinkParsing.double(fields[LATITUDE_FIELD]) ?: return null
        val longitude = LinkParsing.double(fields[LONGITUDE_FIELD]) ?: return null
        val label =
            if (open >= 0 && decoded.endsWith(LABEL_CLOSE)) {
                decoded.substring(open + 1, decoded.length - 1).trim().ifEmpty { null }
            } else {
                null
            }
        return QueryTarget(GeoPoint(latitude, longitude), label)
    }

    /** A `q` that is not a coordinate pair is free text, kept as a label only. */
    private fun freeTextLabel(q: String?): String? {
        val decoded = q?.let(LinkParsing::percentDecode)?.trim().orEmpty()
        return decoded.ifEmpty { null }
    }

    /** The `z` zoom, or the street-level default when it is absent or unusable. */
    private fun queryZoom(z: String?): Double {
        val zoom = z?.let(LinkParsing::double) ?: return LinkRouter.DEFAULT_POINT_ZOOM
        return if (zoom in MIN_QUERY_ZOOM..MAX_QUERY_ZOOM) zoom else LinkRouter.DEFAULT_POINT_ZOOM
    }

    private fun inRange(
        latitude: Double,
        longitude: Double,
    ): Boolean = latitude in MIN_LATITUDE..MAX_LATITUDE && longitude in MIN_LONGITUDE..MAX_LONGITUDE

    private data class QueryTarget(
        val point: GeoPoint,
        val label: String?,
    )
}
