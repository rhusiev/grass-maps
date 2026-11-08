package nl.r1a.grassmap.plugin.api.model

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow

/**
 * The Web-Mercator ground scale, the one definition the map geometry and the
 * scale bar share (T-0107).
 *
 * The engine projects the sphere of [EARTH_RADIUS_METERS] onto [TILE_SIZE_PX]
 * tiles, so a map pixel covers `2 * PI * R / (tile * 2^zoom)` metres at the
 * equator, multiplied by the cosine of the latitude. maplibre-compose lays a
 * map out in dp, so one map pixel is one dp and this value is metres per dp; it
 * needs no density conversion. MapLibre computes `getMetersPerPixelAtLatitude`
 * from exactly these two values; do not add a second Earth model or an
 * independent metres-per-pixel constant.
 */
object WebMercator {
    /** The sphere radius Web-Mercator projects; not the mean radius used for ground distance. */
    const val EARTH_RADIUS_METERS: Double = 6_378_137.0

    /** MapLibre's vector tile size, in map pixels (one dp each in maplibre-compose). */
    const val TILE_SIZE_PX: Double = 512.0

    /** Ground metres one map pixel covers at the equator and zoom 0. */
    val EQUATOR_METERS_PER_PIXEL: Double = 2.0 * PI * EARTH_RADIUS_METERS / TILE_SIZE_PX

    /**
     * Ground metres one map pixel covers at [latitude] and [zoom]. One map
     * pixel is one dp in maplibre-compose, so this is metres per dp.
     *
     * A non-finite input or a latitude near a pole still returns a finite,
     * positive value: the result is clamped to the smallest positive scale.
     */
    fun metersPerPixel(
        latitude: Double,
        zoom: Double,
    ): Double {
        val latitudeCosine = cos(latitude.toRadians()).coerceAtLeast(MIN_LATITUDE_COSINE)
        val scale = EQUATOR_METERS_PER_PIXEL * latitudeCosine / 2.0.pow(zoom)
        return if (scale.isFinite()) scale.coerceAtLeast(MIN_METERS_PER_PIXEL) else MIN_METERS_PER_PIXEL
    }

    private fun Double.toRadians(): Double = this * PI / 180.0

    private const val MIN_LATITUDE_COSINE = 1e-6
    private const val MIN_METERS_PER_PIXEL = 1e-6
}
