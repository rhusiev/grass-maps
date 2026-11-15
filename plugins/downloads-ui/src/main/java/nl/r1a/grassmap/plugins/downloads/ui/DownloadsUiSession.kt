package nl.r1a.grassmap.plugins.downloads.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.RegionDownloadsAccess
import nl.r1a.grassmap.plugin.api.model.DownloadsData
import nl.r1a.grassmap.plugin.api.model.DownloadsState
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix

/**
 * The downloads screen's state and actions inside `plugins/downloads-ui`
 * (T-0504).
 *
 * The store publishes [RegionDownloadsAccess.data]; this session turns it into
 * the screen's rows with [DownloadsScreen], so the screen and the stream always
 * show the same derivation. Every action returns the published state once the
 * store has applied it, which is what the control channel and the Compose
 * content read.
 *
 * The location flow is read only until the first fix: the detected oblast comes
 * from that one position and does not follow the user afterwards, so opening
 * the screen does not keep the provider running.
 */
class DownloadsUiSession(
    private val access: RegionDownloadsAccess,
    private val fixes: Flow<LocationFix?> = emptyFlow(),
) {
    private val current = MutableStateFlow(DownloadsScreen.state(access.data.value))
    val state: StateFlow<DownloadsState> = current.asStateFlow()

    private var selectedRegion: String? = null
    private var lastFix: GeoPoint? = null
    private var following: Boolean = false

    /** Starts following the store and, once, the position the screen opens on. */
    fun follow(scope: CoroutineScope) {
        if (following) {
            return
        }
        following = true
        scope.launch { access.data.collect { data -> publish(data) } }
        scope.launch {
            val fix = fixes.filterNotNull().firstOrNull() ?: return@launch
            lastFix = fix.position
            publish(access.data.value)
        }
    }

    /** Fetches the catalogue on the first screen open; a failure stays in the state. */
    suspend fun load(): Outcome<Unit> = access.refresh()

    suspend fun refresh(): Outcome<DownloadsState> = result(access.refresh())

    suspend fun download(region: String): Outcome<DownloadsState> = result(access.startDownload(region))

    suspend fun cancel(region: String): Outcome<DownloadsState> = result(access.cancel(region))

    suspend fun remove(region: String): Outcome<DownloadsState> = result(access.remove(region))

    suspend fun setServer(
        url: String,
        token: String?,
    ): Outcome<DownloadsState> = result(access.setServer(url, token))

    /** Opens [region]'s sheet, or closes it when [region] is null or blank. */
    suspend fun select(region: String?): Outcome<DownloadsState> {
        selectedRegion = region?.trim()?.takeIf { it.isNotEmpty() }
        publish(access.data.value)
        return Outcome.Success(current.value)
    }

    private fun result(outcome: Outcome<Unit>): Outcome<DownloadsState> =
        when (outcome) {
            is Outcome.Success -> {
                publish(access.data.value)
                Outcome.Success(current.value)
            }

            is Outcome.Failure -> {
                outcome
            }
        }

    private fun publish(data: DownloadsData) {
        val detected = DownloadsScreen.detectRegion(data.catalog, lastFix)
        val state = DownloadsScreen.state(data, selectedRegion, detected)
        if (state.selectedRegion == null) {
            selectedRegion = null
        }
        current.value = state
    }
}
