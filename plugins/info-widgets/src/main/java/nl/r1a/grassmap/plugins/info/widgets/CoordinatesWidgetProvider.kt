package nl.r1a.grassmap.plugins.info.widgets

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.service.Clock
import nl.r1a.grassmap.plugin.api.widget.CoordinatesWidgetValue
import nl.r1a.grassmap.plugin.api.widget.WidgetCadence

/**
 * The current fix and the map centre.
 *
 * The camera runs at display rate, so its centre is throttled before it is
 * combined: the result allocates at most once per widget interval, not once
 * per map frame. The fix and the centre are independent, so a missing camera
 * still shows the fix.
 */
internal class CoordinatesWidgetProvider(
    fixes: Flow<LocationFix?>,
    camera: Flow<Camera>,
    private val clock: Clock,
    private val intervalMillis: Long = WidgetCadence.DEFAULT_INTERVAL_MILLIS,
) {
    val values: Flow<CoordinatesWidgetValue> =
        combine(
            fixes.conflate().throttleLatest(intervalMillis, clock),
            centres(camera, intervalMillis),
        ) { fix, centre ->
            CoordinatesWidgetValue(fix = fix?.position, mapCenter = centre)
        }.distinctUntilChanged()

    private fun centres(
        camera: Flow<Camera>,
        intervalMillis: Long,
    ): Flow<GeoPoint?> =
        camera
            .map<Camera, GeoPoint?> { it.center }
            .conflate()
            .throttleLatest(intervalMillis, clock)
            .onStart { emit(null) }
}
