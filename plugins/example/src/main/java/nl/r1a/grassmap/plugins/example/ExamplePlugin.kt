package nl.r1a.grassmap.plugins.example

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.builtins.serializer
import nl.r1a.grassmap.plugin.api.contract.Plugin
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.contract.StreamId
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.StreamDescriptor

/**
 * The example plugin: one command, one stream and one provider. It depends only
 * on `:plugin-api` and contains no UI.
 */
class ExamplePlugin : Plugin {
    private val value = MutableStateFlow(INITIAL_VALUE)

    override suspend fun activate(context: PluginContext): Outcome<Unit> {
        context.addCommand(SetValueCommand(value))
        context.addStream(
            StreamDescriptor(
                id = StreamId("example.value"),
                serializer = Int.serializer(),
                flow = value,
            ),
        )
        context.addProvider(ThrowingProvider())
        return Outcome.Success(Unit)
    }

    private companion object {
        const val INITIAL_VALUE = 0
    }
}
