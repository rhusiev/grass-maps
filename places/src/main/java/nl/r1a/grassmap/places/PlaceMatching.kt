package nl.r1a.grassmaps.places

import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * The name and distance comparisons the search merges share (T-0212, T-0633).
 *
 * Both merges ask the same question - are these two rows one place? - so they
 * also fold names and measure distances the same way. Only the radius differs:
 * an online Photon centroid can sit tens of metres from the OSM node, while two
 * pack rows for one place come from the same source.
 */
internal object PlaceMatching {
    /** Metres in one degree of latitude; the planar approximation both merges use. */
    private const val METERS_PER_DEGREE_LATITUDE = 111_320.0

    /** Everything that is not a letter or a digit separates words. */
    private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

    /** Case-folded words with punctuation collapsed, so "Площа Ринок!" meets "площа ринок". */
    fun normalizedName(name: String): String =
        name
            .lowercase(Locale.ROOT)
            .replace(SEPARATORS, " ")
            .trim()

    /** Straight-line metres between two nearby points; fine at a duplicate radius. */
    fun distanceMeters(
        first: GeoPoint,
        second: GeoPoint,
    ): Double {
        val meanLatitude = Math.toRadians((first.latitude + second.latitude) / 2.0)
        val latitude = (second.latitude - first.latitude) * METERS_PER_DEGREE_LATITUDE
        val longitude =
            (second.longitude - first.longitude) *
                METERS_PER_DEGREE_LATITUDE *
                cos(meanLatitude)
        return sqrt(latitude * latitude + longitude * longitude)
    }
}
