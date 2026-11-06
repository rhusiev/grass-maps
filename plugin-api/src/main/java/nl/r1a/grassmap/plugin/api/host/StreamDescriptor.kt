package nl.r1a.grassmap.plugin.api.host

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import nl.r1a.grassmap.plugin.api.contract.StreamId

/**
 * A named, typed state or event channel. The owner registers the flow; consumers
 * bind it by [id] through [StreamAccess].
 */
class StreamDescriptor<T : Any>(
    val id: StreamId,
    val serializer: KSerializer<T>,
    val flow: Flow<T>,
) {
    val valueType: String = serializer.descriptor.serialName
}
