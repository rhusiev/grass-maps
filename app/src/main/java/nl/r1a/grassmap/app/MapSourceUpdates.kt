package nl.r1a.grassmap.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import nl.r1a.grassmap.plugin.api.model.InstalledPack

/**
 * The map's sources kept in step with the packs the region store publishes.
 *
 * [installed] is the store's own list. It is republished after a catalogue
 * refresh and on every progress tick as well as after an install or a remove,
 * so only a pack that appeared, left or changed version is a change: a
 * publication that still names the packs [initial] serves resolves nothing.
 *
 * A resolve reads manifests and archive headers, so it stays off every path
 * that runs per fix, per frame or per recomposition. The store publishes on IO
 * after it has moved the pointer, so the pack a publication names is mounted.
 */
internal fun mapSourcesFollowing(
    initial: MapSources,
    installed: Flow<List<InstalledPack>>,
    resolve: suspend () -> MapSources,
): Flow<MapSources> =
    flow {
        var served = initial.packs
        installed.collect { packs ->
            if (servedPacks(packs) == served) {
                return@collect
            }
            val next = resolve()
            if (next.packs == served) {
                return@collect
            }
            served = next.packs
            emit(next)
        }
    }
