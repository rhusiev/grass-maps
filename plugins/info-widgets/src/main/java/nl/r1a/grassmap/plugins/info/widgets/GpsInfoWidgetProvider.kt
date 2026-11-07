package nl.r1a.grassmap.plugins.info.widgets

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.service.Clock
import nl.r1a.grassmap.plugin.api.widget.GpsInfoWidgetValue
import nl.r1a.grassmap.plugin.api.widget.WidgetCadence

/**
 * The fix's GPS fields: horizontal accuracy, speed, bearing and fix time.
 *
 * Values are passed through unchanged; the widget formats them. Without a fix
 * every field is null, which the widget renders as dashes.
 */
internal class GpsInfoWidgetProvider(
    fixes: Flow<LocationFix?>,
    private val clock: Clock,
    private val intervalMillis: Long = WidgetCadence.DEFAULT_INTERVAL_MILLIS,
) {
    val values: Flow<GpsInfoWidgetValue> =
        fixes
            .conflate()
            .throttleLatest(intervalMillis, clock)
            .map { fix -> readingOf(fix) }
            .distinctUntilChanged()

    internal fun readingOf(fix: LocationFix?): GpsInfoWidgetValue =
        GpsInfoWidgetValue(
            accuracyMeters = fix?.accuracyMeters,
            speedMetersPerSecond = fix?.speedMetersPerSecond,
            bearingDegrees = fix?.bearingDegrees,
            fixTimeMillis = fix?.timeMillis,
        )
}
