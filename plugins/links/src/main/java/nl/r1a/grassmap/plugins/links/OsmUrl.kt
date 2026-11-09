package nl.r1a.grassmap.plugins.links

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.OsmType
import java.util.Locale

/**
 * The osm.org web link parser (T-0211).
 *
 * Accepted forms, with or without `www.`, over `http` or `https`:
 *
 * - `/node/<id>`, `/way/<id>` and `/relation/<id>` name an element;
 * - `/go/<code>` is a short link, decoded by [OsmShortLink];
 * - `/#map=zoom/lat/lon` is the current permalink to a position;
 * - `/?lat=&lon=&zoom=` is the pre-2013 permalink, still linked widely.
 *
 * An element URL can also carry the `#map=` fragment, which osm.org adds when
 * the page is shared at a view; that fragment supplies the position, and a bare
 * element URL has none. The page query (`?m`, `?layers=T`) changes the view,
 * never the position, so it is accepted and not read.
 */
internal object OsmUrl {
    private val HOSTS =
        setOf(
            "www.openstreetmap.org",
            "openstreetmap.org",
            "www.osm.org",
            "osm.org",
        )

    private const val SHORT_LINK_SEGMENT = "go"
    private const val MAP_FRAGMENT_PREFIX = "map="
    private const val FRAGMENT_SEPARATOR = '&'
    private const val MAP_FRAGMENT_FIELDS = 3
    private const val ZOOM_FIELD = 0
    private const val LATITUDE_FIELD = 1
    private const val LONGITUDE_FIELD = 2
    private const val SECOND_SEGMENT = 1
    private const val ELEMENT_SEGMENT_COUNT = 2

    private const val LATITUDE_KEY = "lat"
    private const val LONGITUDE_KEY = "lon"
    private const val ZOOM_KEY = "zoom"

    private const val MIN_LINK_ZOOM = 0.0

    /** The deepest zoom an osm.org permalink can express. */
    private const val MAX_LINK_ZOOM = 25.0
    private const val MIN_LATITUDE = -90.0
    private const val MAX_LATITUDE = 90.0
    private const val MIN_LONGITUDE = -180.0
    private const val MAX_LONGITUDE = 180.0

    private val ELEMENT_SEGMENTS =
        mapOf(
            "node" to OsmType.NODE,
            "way" to OsmType.WAY,
            "relation" to OsmType.RELATION,
        )

    /** Parses the part of an http(s) link after its scheme. */
    fun parse(body: String): LinkResult {
        if (!body.startsWith("//")) {
            return LinkResult.Failure(LinkFailure.MALFORMED)
        }
        val rest = body.substring(2)
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (authorityEnd < 0) rest else rest.substring(0, authorityEnd)
        if (authority.isEmpty() || authority.contains('@')) {
            return LinkResult.Failure(LinkFailure.UNSUPPORTED)
        }
        val host = authority.substringBefore(':').lowercase(Locale.ROOT)
        if (host !in HOSTS) {
            return LinkResult.Failure(LinkFailure.UNSUPPORTED)
        }
        val tail = if (authorityEnd < 0) "" else rest.substring(authorityEnd)
        val fragment = tail.substringAfter('#', "")
        val withoutFragment = tail.substringBefore('#')
        val query = withoutFragment.substringAfter('?', "")
        val path = withoutFragment.substringBefore('?')
        val segments = path.split('/').filter { it.isNotEmpty() }
        return when (val first = segments.firstOrNull()) {
            null -> permalink(fragment, query)
            SHORT_LINK_SEGMENT -> shortLink(segments.getOrNull(SECOND_SEGMENT))
            in ELEMENT_SEGMENTS.keys -> element(ELEMENT_SEGMENTS.getValue(first), segments, fragment)
            else -> LinkResult.Failure(LinkFailure.UNSUPPORTED)
        }
    }

    private fun shortLink(code: String?): LinkResult {
        if (code.isNullOrEmpty()) {
            return LinkResult.Failure(LinkFailure.MALFORMED)
        }
        val decoded = OsmShortLink.decode(code) ?: return LinkResult.Failure(LinkFailure.MALFORMED)
        return LinkResult.Success(LinkTarget.Point(decoded.point, decoded.zoom))
    }

    private fun element(
        type: OsmType,
        segments: List<String>,
        fragment: String,
    ): LinkResult {
        if (segments.size != ELEMENT_SEGMENT_COUNT) {
            return LinkResult.Failure(LinkFailure.UNSUPPORTED)
        }
        val id = segments[SECOND_SEGMENT].toLongOrNull()
        if (id == null || id <= 0L) {
            return LinkResult.Failure(LinkFailure.MALFORMED)
        }
        val view = mapView(fragment)
        return LinkResult.Success(LinkTarget.Element(type, id, view?.point, view?.zoom))
    }

    /** A root URL: the `#map=` fragment first, then the legacy query form. */
    private fun permalink(
        fragment: String,
        query: String,
    ): LinkResult {
        if (fragment.startsWith(MAP_FRAGMENT_PREFIX)) {
            val view = mapView(fragment) ?: return LinkResult.Failure(LinkFailure.MALFORMED)
            return LinkResult.Success(LinkTarget.Point(view.point, view.zoom))
        }
        return queryPermalink(query) ?: LinkResult.Failure(LinkFailure.UNSUPPORTED)
    }

    /**
     * The `#map=zoom/lat/lon` view, or null when the fragment is absent or
     * unusable. A layer code after `&` belongs to the view, not the position.
     */
    private fun mapView(fragment: String): MapView? {
        if (!fragment.startsWith(MAP_FRAGMENT_PREFIX)) {
            return null
        }
        val fields =
            fragment
                .removePrefix(MAP_FRAGMENT_PREFIX)
                .substringBefore(FRAGMENT_SEPARATOR)
                .split('/')
        if (fields.size != MAP_FRAGMENT_FIELDS) {
            return null
        }
        val zoom = LinkParsing.double(fields[ZOOM_FIELD]) ?: return null
        val latitude = LinkParsing.double(fields[LATITUDE_FIELD]) ?: return null
        val longitude = LinkParsing.double(fields[LONGITUDE_FIELD]) ?: return null
        return viewOrNull(latitude, longitude, zoom)
    }

    /** The pre-2013 `?lat=&lon=&zoom=` permalink, or null when the query is not one. */
    private fun queryPermalink(query: String): LinkResult? {
        val latitudeText = LinkParsing.queryValue(query, LATITUDE_KEY) ?: return null
        val longitudeText = LinkParsing.queryValue(query, LONGITUDE_KEY) ?: return null
        val zoomText = LinkParsing.queryValue(query, ZOOM_KEY) ?: return null
        val latitude = LinkParsing.double(latitudeText) ?: return LinkResult.Failure(LinkFailure.MALFORMED)
        val longitude = LinkParsing.double(longitudeText) ?: return LinkResult.Failure(LinkFailure.MALFORMED)
        val zoom = LinkParsing.double(zoomText) ?: return LinkResult.Failure(LinkFailure.MALFORMED)
        val view = viewOrNull(latitude, longitude, zoom) ?: return LinkResult.Failure(LinkFailure.MALFORMED)
        return LinkResult.Success(LinkTarget.Point(view.point, view.zoom))
    }

    private fun viewOrNull(
        latitude: Double,
        longitude: Double,
        zoom: Double,
    ): MapView? {
        if (zoom !in MIN_LINK_ZOOM..MAX_LINK_ZOOM) {
            return null
        }
        if (latitude !in MIN_LATITUDE..MAX_LATITUDE || longitude !in MIN_LONGITUDE..MAX_LONGITUDE) {
            return null
        }
        return MapView(GeoPoint(latitude, longitude), zoom)
    }

    private data class MapView(
        val point: GeoPoint,
        val zoom: Double,
    )
}
