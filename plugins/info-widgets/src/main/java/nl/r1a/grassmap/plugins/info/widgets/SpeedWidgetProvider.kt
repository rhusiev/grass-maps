package nl.r1a.grassmap.plugins.info.widgets

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.service.Clock
import nl.r1a.grassmap.plugin.api.widget.SpeedWidgetValue
import nl.r1a.grassmap.plugin.api.widget.WidgetCadence

/** Which of the three speeds a widget shows. */
internal enum class SpeedMetric {
    Current,
    Average,
    Max,
}

/**
 * Current, average and max speed from the fix stream.
 *
 * One [SpeedTracker] feeds all three widgets, so the fixes are sampled once and
 * the max survives a widget that stops and starts collecting again. The values
 * are metres per second; the shell formats them in the user's units.
 */
internal class SpeedWidgetProvider(
    fixes: Flow<LocationFix?>,
    clock: Clock,
    scope: CoroutineScope,
    intervalMillis: Long = WidgetCadence.DEFAULT_INTERVAL_MILLIS,
    windowMillis: Long = SpeedTracker.AVERAGE_WINDOW_MILLIS,
) {
    private val tracker = SpeedTracker(windowMillis)

    private val readings: StateFlow<SpeedReading> =
        fixes
            .conflate()
            .throttleLatest(intervalMillis, clock)
            .map { fix -> tracker.offer(fix) }
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(InfoWidgetsContract.SHARE_STOP_MILLIS),
                initialValue = SpeedReading(),
            )

    fun values(metric: SpeedMetric): Flow<SpeedWidgetValue> =
        readings
            .map { reading -> SpeedWidgetValue(reading.metersPerSecond(metric)) }
            .distinctUntilChanged()
}

/** The value [metric] names, in metres per second. */
internal fun SpeedReading.metersPerSecond(metric: SpeedMetric): Double? =
    when (metric) {
        SpeedMetric.Current -> currentMetersPerSecond
        SpeedMetric.Average -> averageMetersPerSecond
        SpeedMetric.Max -> maxMetersPerSecond
    }
