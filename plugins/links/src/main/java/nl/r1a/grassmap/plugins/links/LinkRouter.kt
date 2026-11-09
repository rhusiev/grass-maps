package nl.r1a.grassmap.plugins.links

import java.util.Locale

/**
 * The routing decision for one link that another app sent (T-0211).
 *
 * The parse is pure: no network, no Android type, no clock. A link that cannot
 * become a [LinkTarget] is a typed [LinkResult.Failure], never an exception.
 *
 * Two link families are accepted. A `geo:` URI is RFC 5870. An http(s) link is
 * an osm.org address: an element page (`/node/<id>`, `/way/<id>`,
 * `/relation/<id>`), a short link (`/go/<code>`), or a permalink whose
 * `#map=zoom/lat/lon` fragment carries the position.
 */
object LinkRouter {
    /** Unknown schemes are refused instead of being handed to another parser. */
    private const val GEO_SCHEME = "geo"

    private val WEB_SCHEMES = setOf("http", "https")

    /**
     * The zoom a `geo:` link opens at. RFC 5870 has no zoom field, and a shared
     * place is a street-level target, so the map opens at street scale.
     */
    const val DEFAULT_POINT_ZOOM: Double = 16.0

    /** Routes [uri], or fails when it is empty or has no scheme. */
    fun route(uri: String?): LinkResult {
        val trimmed = uri?.trim().orEmpty()
        val colon = trimmed.indexOf(':')
        if (colon <= 0) {
            return LinkResult.Failure(LinkFailure.MALFORMED)
        }
        val scheme = trimmed.substring(0, colon).lowercase(Locale.ROOT)
        val body = trimmed.substring(colon + 1)
        return when {
            scheme == GEO_SCHEME -> GeoUri.parse(body)
            scheme in WEB_SCHEMES -> OsmUrl.parse(body)
            else -> LinkResult.Failure(LinkFailure.UNSUPPORTED)
        }
    }
}
