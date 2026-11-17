package nl.r1a.grassmap.plugins.region.outlines

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.error.onFailure
import nl.r1a.grassmap.plugin.api.host.MapHost
import nl.r1a.grassmap.plugin.api.model.DownloadsData
import nl.r1a.grassmap.plugin.api.model.RegionOutline

/**
 * Highlights the installed regions on the world map (T-0506).
 *
 * [data] is the downloads store's state. A region is highlighted when a pack
 * for it is installed and the store has its border. A region whose border is
 * not known is left out: the layer draws nothing it cannot place. [run] keeps
 * the map in step until its coroutine is cancelled and [refresh] draws once,
 * which a newly bound surface needs. A missing surface is
 * [AppError.NotReady], never an exception.
 */
class RegionOutlinesLayer(
    private val data: StateFlow<DownloadsData>,
    private val host: () -> MapHost?,
    private val log: (String) -> Unit = {},
) {
    /** Draws the current highlights on the bound host. */
    suspend fun refresh(): Outcome<Unit> = draw(highlights(data.value))

    /** Redraws whenever the set of highlighted regions changes. */
    suspend fun run() {
        data.map(::highlights).distinctUntilChanged().collect { draw(it) }
    }

    private suspend fun draw(regions: List<RegionOutline>): Outcome<Unit> {
        val map = host() ?: return Outcome.Failure(AppError.NotReady)
        return map.setRegionHighlights(regions).onFailure { error -> log("region outlines not drawn: $error") }
    }

    private fun highlights(state: DownloadsData): List<RegionOutline> {
        val installed = state.installed.mapTo(mutableSetOf()) { it.region }
        return state.outlines.filter { it.region in installed }
    }
}
