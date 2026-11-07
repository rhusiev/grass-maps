package nl.r1a.grassmap.plugin.api.contract

import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.PluginServices
import nl.r1a.grassmap.plugin.api.host.StreamDescriptor
import nl.r1a.grassmap.plugin.api.provider.Provider
import nl.r1a.grassmap.plugin.api.widget.WidgetDescriptor

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

    /** The platform inputs the plugin reads while it is active. */
    val services: PluginServices

    fun addCommand(command: Command<*, *>)

    fun addStream(stream: StreamDescriptor<*>)

    fun addProvider(provider: Provider)

    /**
     * Declares a widget over a stream this plugin also registers. The host
     * rejects a widget that its manifest does not declare or whose stream is
     * missing or of another value type.
     */
    fun addWidget(widget: WidgetDescriptor)
}
