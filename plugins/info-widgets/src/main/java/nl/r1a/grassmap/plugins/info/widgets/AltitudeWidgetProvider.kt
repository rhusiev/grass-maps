package nl.r1a.grassmap.plugins.info.widgets

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.ElevationAccess
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.service.Clock
import nl.r1a.grassmap.plugin.api.widget.AltitudeSource
import nl.r1a.grassmap.plugin.api.widget.AltitudeWidgetValue
import nl.r1a.grassmap.plugin.api.widget.WidgetCadence

/**
 * Altitude at the current position: the terrain height at the fix when the
 * mounted DEM covers it, otherwise the fix's own altitude.
 *
 * A DEM read that fails or has no data is not an error the user sees; it just
 * selects the fallback. The value names its source so the widget can show
 * which one it used.
 */
internal class AltitudeWidgetProvider(
    fixes: Flow<LocationFix?>,
    private val elevation: ElevationAccess,
    private val clock: Clock,
    private val intervalMillis: Long = WidgetCadence.DEFAULT_INTERVAL_MILLIS,
) {
    val values: Flow<AltitudeWidgetValue> =
        fixes
            .conflate()
            .throttleLatest(intervalMillis, clock)
            .map { fix -> readingFor(fix) }
            .distinctUntilChanged()

    internal suspend fun readingFor(fix: LocationFix?): AltitudeWidgetValue {
        if (fix == null) {
            return AltitudeWidgetValue(altitudeMeters = null, source = AltitudeSource.None)
        }
        val terrain = terrainMeters(fix)
        if (terrain != null) {
            return AltitudeWidgetValue(altitudeMeters = terrain, source = AltitudeSource.Dem)
        }
        val gpsAltitude = fix.altitudeMeters
        return AltitudeWidgetValue(
            altitudeMeters = gpsAltitude,
            source = if (gpsAltitude == null) AltitudeSource.None else AltitudeSource.Gps,
        )
    }

    private suspend fun terrainMeters(fix: LocationFix): Double? =
        try {
            when (val outcome = elevation.elevationMeters(fix.position)) {
                is Outcome.Success -> outcome.value
                is Outcome.Failure -> null
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            // The DEM is an enhancement; a broken reader must not cost the fix's altitude.
            null
        }
}
