package nl.r1a.grassmap.plugin.api.contract

import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.StreamDescriptor
import nl.r1a.grassmap.plugin.api.provider.Provider

/**
 * A compiled-in plugin. The host creates an instance on the first activation
 * event, then calls [activate] off the main thread.
 */
interface Plugin {
    suspend fun activate(context: PluginContext): Outcome<Unit>

    fun deactivate() {}
}

/**
 * The activation contribution sink. Contributions are declared in the plugin
 * manifest and validated by the host when [Plugin.activate] returns.
 */
interface PluginContext {
    val pluginId: PluginId

    fun addCommand(command: Command<*, *>)

    fun addStream(stream: StreamDescriptor<*>)

    fun addProvider(provider: Provider)
}
