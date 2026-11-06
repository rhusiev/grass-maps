package nl.r1a.grassmap.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import nl.r1a.grassmap.plugin.api.contract.StreamId
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.StreamAccess

/**
 * Binds a declared stream id to the flow its owner registered on activation.
 * Activation happens on first collection, so subscription is also an activation
 * event.
 */
class StreamRegistry(
    private val host: PluginHost,
) : StreamAccess {
    override fun <T : Any> subscribe(streamId: StreamId): Flow<T> =
        flow {
            when (val stream = host.stream(streamId)) {
                is Outcome.Success -> {
                    @Suppress("UNCHECKED_CAST")
                    emitAll(stream.value.flow as Flow<T>)
                }

                is Outcome.Failure -> {
                    Unit
                }
            }
        }
}
