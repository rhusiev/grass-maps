package nl.r1a.grassmap.plugin.api.model

/**
 * One position report from a location source.
 *
 * [position] is WGS84. [accuracyMeters] is the horizontal radius the source
 * reports. Altitude, bearing and speed are null when the source has none.
 * [timeMillis] is the fix time in epoch milliseconds.
 */
data class LocationFix(
    val position: GeoPoint,
    val accuracyMeters: Double,
    val altitudeMeters: Double? = null,
    val bearingDegrees: Double? = null,
    val speedMetersPerSecond: Double? = null,
    val timeMillis: Long,
)
