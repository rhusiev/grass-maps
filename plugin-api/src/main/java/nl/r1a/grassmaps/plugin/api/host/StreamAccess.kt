package nl.r1a.grassmaps.plugin.api.host

import kotlinx.coroutines.flow.Flow
import nl.r1a.grassmaps.plugin.api.contract.StreamId

/**
 * The only way UI and other plugins read observable state.
 */
interface StreamAccess {
    fun <T : Any> subscribe(streamId: StreamId): Flow<T>
}
