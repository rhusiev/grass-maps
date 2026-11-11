package nl.r1a.grassmap.plugin.api.widget

import kotlinx.serialization.Serializable
import nl.r1a.grassmap.plugin.api.model.GeoPoint

/**
 * One altitude reading for the altitude widget.
 *
 * [altitudeMeters] is the terrain height when [source] is
 * [AltitudeSource.Dem], the fix altitude when it is [AltitudeSource.Gps], and
 * null when neither source has a value. [source] says which one was used, so a
 * user can tell a measured height from a DEM one.
 */
@Serializable
data class AltitudeWidgetValue(
    val altitudeMeters: Double?,
    val source: AltitudeSource,
)

/** Where an [AltitudeWidgetValue] came from. */
@Serializable
enum class AltitudeSource {
    Dem,
    Gps,
    None,
}

/**
 * One coordinates reading: the current fix and the map centre. Either is null
 * while that source has no value yet.
 */
@Serializable
data class CoordinatesWidgetValue(
    val fix: GeoPoint?,
    val mapCenter: GeoPoint?,
)

/**
 * One GPS info reading, taken from a location fix. Every field is null when
 * there is no fix.
 */
@Serializable
data class GpsInfoWidgetValue(
    val accuracyMeters: Double?,
    val speedMetersPerSecond: Double?,
    val bearingDegrees: Double?,
    val fixTimeMillis: Long?,
)

/**
 * One speed reading for a current, average or max speed widget, in metres per
 * second. The field is null until an interval has been measured: one fix has no
 * speed yet, and the shell shows a dash.
 *
 * Which of the three speeds a widget shows is its stream, not a field here, so
 * the shell never has to read a partly empty object.
 */
@Serializable
data class SpeedWidgetValue(
    val speedMetersPerSecond: Double?,
)
