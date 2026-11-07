package nl.r1a.grassmap.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.service.LocationService

/**
 * The release location source: the platform source until the development-tools
 * panel starts the committed GPX replay, then the existing mock replay seam,
 * and back to the platform source on stop.
 *
 * The replay is a cold flow that reloads the fixture on each collection, so a
 * stop and a later start begin at the first point again.
 */
internal class ReplayLocationSource(
    private val platform: LocationService,
    replay: () -> LocationService,
    initiallyReplaying: Boolean = false,
) : LocationService,
    ReplayControl {
    private val replaySource: LocationService by lazy(replay)
    private val replaying = MutableStateFlow(initiallyReplaying)

    override val fixes: Flow<LocationFix?> =
        channelFlow {
            replaying.collectLatest { active ->
                val source = if (active) replaySource else platform
                source.fixes.collect { fix -> send(fix) }
            }
        }

    override fun hasPermission(): Boolean = active().hasPermission()

    override fun requiresPermission(): Boolean = active().requiresPermission()

    override fun isReplaying(): Boolean = replaying.value

    override fun startReplay() {
        replaying.value = true
    }

    override fun stopReplay() {
        replaying.value = false
    }

    private fun active(): LocationService = if (replaying.value) replaySource else platform
}
