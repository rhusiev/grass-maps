package nl.r1a.grassmap.app

import nl.r1a.grassmap.app.generated.GeneratedPlugins
import nl.r1a.grassmap.app.generated.GeneratedStreams
import nl.r1a.grassmap.core.CommandBus
import nl.r1a.grassmap.core.CommandDispatchers
import nl.r1a.grassmap.core.PluginHost
import nl.r1a.grassmap.core.ProviderRegistry
import nl.r1a.grassmap.core.StreamRegistry
import nl.r1a.grassmap.plugin.api.contract.SwitchId
import nl.r1a.grassmap.plugin.api.host.StreamAccess

/**
 * The one composition root. It wires the generated registries to the core host;
 * modules receive plugin-api interfaces from here.
 */
class AppContainer(
    private val dispatchers: CommandDispatchers = CommandDispatchers(),
    private val switchEnabled: (SwitchId) -> Boolean = { false },
) {
    val pluginHost: PluginHost by lazy {
        PluginHost(
            plugins = GeneratedPlugins.plugins,
            streams = GeneratedStreams.streams,
            commands = GeneratedPlugins.commands,
            dispatcher = dispatchers.default,
        )
    }

    val commandBus: CommandBus by lazy {
        CommandBus(
            host = pluginHost,
            commands = GeneratedPlugins.commands,
            dispatchers = dispatchers,
            switchEnabled = switchEnabled,
        )
    }

    val streamAccess: StreamAccess by lazy {
        StreamRegistry(pluginHost)
    }

    val providerRegistry: ProviderRegistry by lazy {
        ProviderRegistry(pluginHost)
    }
}
