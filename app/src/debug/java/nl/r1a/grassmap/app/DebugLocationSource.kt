package nl.r1a.grassmap.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.service.LocationService

/**
 * Debug-only source switch. The replay is the default; launching the activity
 * with the platform extra selects [platform] instead. The choice is a state
 * flow because the application builds the container before the first activity
 * intent exists, and a running replay collection must switch to the platform
 * source when the intent arrives.
 */
internal class DebugLocationSource(
    private val platform: LocationService,
    private val replay: LocationService,
    private val usePlatform: StateFlow<Boolean>,
) : LocationService {
    override fun hasPermission(): Boolean = active().hasPermission()

    override fun requiresPermission(): Boolean = active().requiresPermission()

    override val fixes: Flow<LocationFix?> =
        channelFlow {
            usePlatform.collectLatest { platformSelected ->
                val source = if (platformSelected) platform else replay
                source.fixes.collect { fix -> send(fix) }
            }
        }

    private fun active(): LocationService = if (usePlatform.value) platform else replay
}
