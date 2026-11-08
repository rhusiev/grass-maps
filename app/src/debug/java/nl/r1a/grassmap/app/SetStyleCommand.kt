package nl.r1a.grassmap.app

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import nl.r1a.grassmap.core.ProviderRegistry
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.command.CommandDispatcher
import nl.r1a.grassmap.plugin.api.command.CommandPolicy
import nl.r1a.grassmap.plugin.api.command.CommandResult
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.MapEvent
import nl.r1a.grassmap.plugin.api.model.StyleDiff
import nl.r1a.grassmap.plugin.api.model.StyleId
import nl.r1a.grassmap.plugin.api.provider.MapLayerContract
import nl.r1a.grassmap.plugin.api.provider.MapLayerProvider

/** The debug-only command that selects one style variant for the goldens. */
internal val SET_STYLE_COMMAND: CommandId = CommandId("core.map.set_style")

/**
 * Loads a style by id from the Standard style plugin and applies its layers in
 * place. It exists only in the debug source set: the release app has no style
 * command, and theme-driven selection is T-0111.
 */
internal class SetStyleCommand(
    private val session: MapSessionHolder,
    private val providers: () -> ProviderRegistry,
    private val idleTimeoutMillis: Long = DEFAULT_IDLE_TIMEOUT_MS,
) : Command<JsonElement, String>(
        id = SET_STYLE_COMMAND,
        paramsSerializer = JsonElement.serializer(),
        resultSerializer = String.serializer(),
        doc = "Selects a map style id and waits for it to load.",
        policy =
            CommandPolicy(
                allowlistedForControl = true,
                dispatcher = CommandDispatcher.IO,
            ),
    ) {
    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val styleId = parse(params) ?: return Outcome.Failure(AppError.InvalidInput)
        val host = session.hostOrNull() ?: return Outcome.Failure(AppError.NotReady)
        val requested = StyleId(styleId)
        val bundle =
            providers().invoke(STYLE_PLUGIN, MapLayerContract.REFERENCE) { provider ->
                val mapLayers = provider as? MapLayerProvider ?: return@invoke null
                val style = mapLayers.styles().firstOrNull { it.id == requested } ?: return@invoke null
                style to mapLayers.layers(style.id)
            }
        return when (bundle) {
            is Outcome.Failure -> {
                bundle
            }

            is Outcome.Success -> {
                val (style, layers) = bundle.value ?: return Outcome.Failure(AppError.InvalidInput)
                val appliedStyle = host.setStyle(style)
                if (appliedStyle is Outcome.Failure) {
                    return appliedStyle
                }
                val appliedLayers = host.applyStyleDiff(StyleDiff(style.id, layers))
                if (appliedLayers is Outcome.Failure) {
                    return appliedLayers
                }
                val loaded =
                    withTimeoutOrNull(idleTimeoutMillis) {
                        host.events.first { event -> event is MapEvent.StyleLoaded }
                    } != null
                val idle =
                    withTimeoutOrNull(idleTimeoutMillis) {
                        host.events.first { event -> event is MapEvent.Idle }
                    } != null
                Outcome.Success(CommandResult("style=${style.id.value} loaded=$loaded idle=$idle"))
            }
        }
    }

    private fun parse(params: JsonElement): String? {
        val json = params as? JsonObject ?: return null
        val style = (json["style"] as? JsonPrimitive)?.contentOrNull ?: return null
        return style.takeIf { it.isNotBlank() }
    }

    private companion object {
        val STYLE_PLUGIN = PluginId("style.standard")
        const val DEFAULT_IDLE_TIMEOUT_MS = 10_000L
    }
}
