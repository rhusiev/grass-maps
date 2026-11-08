package nl.r1a.grassmap.plugins.point.info

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.service.Clock
import nl.r1a.grassmap.plugin.api.widget.WidgetCadence

/**
 * The distance and azimuth from the current fix, or the map centre when there
 * is no fix, to the selected point.
 *
 * The camera runs at display rate, so the fix and the centre are throttled
 * before they are combined: the result allocates at most once per widget
 * interval, not once per map frame. The selection is not throttled, so a tap
 * shows its reading at once.
 */
internal class PointInfoCalculator(
    selection: Flow<GeoPoint?>,
    fixes: Flow<LocationFix?>,
    centres: Flow<GeoPoint?>,
    private val clock: Clock,
    private val intervalMillis: Long = WidgetCadence.DEFAULT_INTERVAL_MILLIS,
) {
    val values: Flow<SelectedPointValue> =
        combine(
            selection,
            fixes.conflate().throttleLatest(intervalMillis, clock),
            centres.conflate().throttleLatest(intervalMillis, clock),
        ) { point, fix, centre ->
            valueOf(point = point, origin = fix?.position ?: centre)
        }.distinctUntilChanged()

    private fun valueOf(
        point: GeoPoint?,
        origin: GeoPoint?,
    ): SelectedPointValue {
        if (point == null) {
            return SelectedPointValue()
        }
        if (origin == null) {
            return SelectedPointValue(point = point)
        }
        val geodesic = Geodesic.inverse(from = origin, to = point)
        return SelectedPointValue(
            point = point,
            distanceMeters = geodesic.distanceMeters,
            azimuthDegrees = geodesic.azimuthDegrees,
        )
    }
}

/**
 * Emits a value only when at least [intervalMillis] passed since the previous
 * emission. The first value always passes. This mirrors the cadence helper the
 * info widgets use, over the same [Clock] contract.
 */
private fun <T> Flow<T>.throttleLatest(
    intervalMillis: Long,
    clock: Clock,
): Flow<T> =
    flow {
        var lastEmitted: Long? = null
        collect { value ->
            val now = clock.nowMillis()
            val previous = lastEmitted
            val elapsed = if (previous == null) null else now - previous
            if (elapsed == null || elapsed >= intervalMillis || elapsed < 0) {
                lastEmitted = now
                emit(value)
            }
        }
    }
