package nl.r1a.grassmap.plugins.share

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.OsmType
import java.util.Locale

/** The canonical OSM element a place stands for. */
data class PlaceIdentity(
    val type: OsmType,
    val id: Long,
)

/**
 * Where a shared place points (T-0210).
 *
 * A place with a canonical OSM identity shares the osm.org element, which
 * names the same point on a laptop. A place with no identity, and the current
 * location, have no element to link, so the choice is a typed [Geo] link
 * instead of a guessed or marker-less osm.org URL.
 */
sealed interface PlaceLink {
    val uri: String

    data class Osm(
        val url: String,
    ) : PlaceLink {
        override val uri: String
            get() = url
    }

    data class Geo(
        val geoUri: String,
    ) : PlaceLink {
        override val uri: String
            get() = geoUri
    }
}

/**
 * The link and coordinate builders behind sharing and the card (T-0210).
 *
 * Both builders are pure: no clock, no network and no platform call. The
 * osm.org form is the one the OSM website accepts,
 * `https://www.openstreetmap.org/<type>/<id>`; the `geo:` form follows RFC
 * 5870, `geo:lat,lon?q=lat,lon(label)`.
 */
object ShareLinks {
    const val OSM_BASE: String = "https://www.openstreetmap.org"

    private const val GEO_SCHEME = "geo:"

    /** The element URL for [type] and [id], for example `.../node/4815162342`. */
    fun osmUrl(
        type: OsmType,
        id: Long,
    ): String = "$OSM_BASE/${type.name.lowercase(Locale.ROOT)}/$id"

    /**
     * The `geo:` URI for [point]. [label] is percent-encoded, including the
     * parentheses around it, so a label can never end the query early.
     */
    fun geoUri(
        point: GeoPoint,
        label: String? = null,
    ): String {
        val pair = CoordinateFormats.decimalPair(point)
        val trimmed = label?.trim()?.takeIf { it.isNotEmpty() } ?: return "$GEO_SCHEME$pair"
        return "$GEO_SCHEME$pair?q=$pair(${percentEncode(trimmed)})"
    }

    /** The element identity in [details], or null when the place has none. */
    fun identityOf(
        type: OsmType?,
        id: Long?,
    ): PlaceIdentity? =
        when {
            type == null || id == null || id <= 0L -> null
            else -> PlaceIdentity(type, id)
        }

    /**
     * The link a shared place carries: the osm.org element when it has an
     * identity, otherwise the `geo:` URI for the same point.
     */
    fun placeLink(
        identity: PlaceIdentity?,
        point: GeoPoint,
        label: String? = null,
    ): PlaceLink =
        if (identity == null) {
            PlaceLink.Geo(geoUri(point, label))
        } else {
            PlaceLink.Osm(osmUrl(identity.type, identity.id))
        }

    private fun percentEncode(text: String): String =
        buildString {
            for (byte in text.toByteArray(Charsets.UTF_8)) {
                val value = byte.toInt() and BYTE_MASK
                if (value.toChar() in UNRESERVED) {
                    append(value.toChar())
                } else {
                    append('%')
                    append(HEX[value shr 4])
                    append(HEX[value and LOW_NIBBLE])
                }
            }
        }

    private const val BYTE_MASK = 0xFF
    private const val LOW_NIBBLE = 0x0F
    private const val HEX = "0123456789ABCDEF"

    /** RFC 3986 unreserved characters, the only ones left as they are. */
    private val UNRESERVED: Set<Char> =
        (('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '.', '_', '~')).toSet()
}
