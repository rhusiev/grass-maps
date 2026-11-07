package nl.r1a.grassmap.app

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import nl.r1a.grassmap.core.services.LocationSource
import nl.r1a.grassmap.plugin.api.model.LocationFix

/**
 * Debug-only log of the replay, so an emulator run can show that fixes keep
 * arriving while the camera stays free. Coordinates never reach the release
 * path or the diagnostic logger.
 */
internal class LoggingLocationSource(
    private val delegate: LocationSource,
) : LocationSource {
    override fun hasPermission(): Boolean = delegate.hasPermission()

    override fun requiresPermission(): Boolean = delegate.requiresPermission()

    override val fixes: Flow<LocationFix?> =
        delegate.fixes.onEach { fix ->
            if (fix == null) {
                Log.i(TAG, "fix none")
            } else {
                Log.i(
                    TAG,
                    "fix lat=${fix.position.latitude} lon=${fix.position.longitude} t=${fix.timeMillis}",
                )
            }
        }

    private companion object {
        const val TAG = "T0022Location"
    }
}
