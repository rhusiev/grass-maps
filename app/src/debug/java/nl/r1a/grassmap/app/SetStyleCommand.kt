package nl.r1a.grassmap.app

import android.util.Log
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import nl.r1a.grassmap.plugin.api.host.MapHost
import nl.r1a.grassmap.plugin.api.model.SlottedLayer
import nl.r1a.grassmap.plugin.api.model.StyleDiff
import nl.r1a.grassmap.plugin.api.model.StyleId
import nl.r1a.grassmap.plugin.api.model.StyleSpec
import nl.r1a.grassmap.plugin.api.provider.MapLayerContract
import nl.r1a.grassmap.plugin.api.provider.MapLayerProvider

/** The debug-only command that selects one style variant for the goldens. */
internal val SET_STYLE_COMMAND: CommandId = CommandId("core.map.set_style")

/**
 * Loads a style by id from the Standard or Touring style plugin and applies its layers in
 * place. It exists only in the debug source set: the release app has no style
 * command, and theme-driven selection is T-0111.
 */
internal class SetStyleCommand(
    private val session: MapSessionHolder,
    private val providers: () -> ProviderRegistry,
    private val contours: () -> ContoursAccess,
    private val contoursEnabled: () -> Boolean,
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
    private var lastBaseJson: String? = null

    override suspend fun execute(params: JsonElement): Outcome<CommandResult<String>> {
        val styleId = parse(params) ?: return Outcome.Failure(AppError.InvalidInput)
        val host = session.hostOrNull() ?: return Outcome.Failure(AppError.NotReady)
        val requested = StyleId(styleId)
        val bundle =
            STYLE_PLUGINS.firstNotNullOfOrNull { plugin -> findStyle(plugin, requested) }
                ?: Outcome.Success(null)
        return when (bundle) {
            is Outcome.Failure -> {
                bundle
            }

            is Outcome.Success -> {
                val (style, layers) = bundle.value ?: return Outcome.Failure(AppError.InvalidInput)
                applyAndAwait(host, style, layers)
            }
        }
    }

    /**
     * Subscribes before the apply calls, because the event flow has no replay and
     * an event that fires first would be lost. A style that shares the previous
     * base document emits no `StyleLoaded`, so only `Idle` is awaited then.
     */
    private suspend fun applyAndAwait(
        host: MapHost,
        style: StyleSpec,
        layers: List<SlottedLayer>,
    ): Outcome<CommandResult<String>> =
        coroutineScope {
            val reloads = lastBaseJson != null && lastBaseJson != style.baseStyleJson
            val loaded = async(start = CoroutineStart.UNDISPATCHED) { awaitEvent(host) { it is MapEvent.StyleLoaded } }
            val idle = async(start = CoroutineStart.UNDISPATCHED) { awaitEvent(host) { it is MapEvent.Idle } }
            val failure = applyStyle(host, style, layers)
            if (failure != null) {
                loaded.cancel()
                idle.cancel()
                return@coroutineScope failure
            }
            lastBaseJson = style.baseStyleJson
            val isLoaded = if (reloads) loaded.await() else true.also { loaded.cancel() }
            Outcome.Success(CommandResult("style=${style.id.value} loaded=$isLoaded idle=${idle.await()}"))
        }

    private suspend fun awaitEvent(
        host: MapHost,
        match: (MapEvent) -> Boolean,
    ): Boolean = withTimeoutOrNull(idleTimeoutMillis) { host.events.first(match) } != null

    private suspend fun applyStyle(
        host: MapHost,
        style: StyleSpec,
        layers: List<SlottedLayer>,
    ): Outcome.Failure? {
        val appliedStyle = host.setStyle(style)
        if (appliedStyle is Outcome.Failure) {
            return appliedStyle
        }
        val appliedLayers = host.applyStyleDiff(StyleDiff(style.id, layers))
        if (appliedLayers is Outcome.Failure) {
            return appliedLayers
        }
        when (val overlay = contours().apply(host, style.id, contoursEnabled())) {
            is Outcome.Success -> Unit
            is Outcome.Failure -> Log.w(TAG, "contour layers not applied: ${overlay.error}")
        }
        return null
    }

    private suspend fun findStyle(
        plugin: PluginId,
        requested: StyleId,
    ): Outcome<Pair<StyleSpec, List<SlottedLayer>>?>? {
        val found =
            providers().invoke(plugin, MapLayerContract.REFERENCE) { provider ->
                val mapLayers = provider as? MapLayerProvider ?: return@invoke null
                val style = mapLayers.styles().firstOrNull { it.id == requested } ?: return@invoke null
                style to mapLayers.layers(style.id)
            }
        return found.takeUnless { it is Outcome.Success && it.value == null }
    }

    private fun parse(params: JsonElement): String? {
        val json = params as? JsonObject ?: return null
        val style = (json["style"] as? JsonPrimitive)?.contentOrNull ?: return null
        return style.takeIf { it.isNotBlank() }
    }

    private companion object {
        val STYLE_PLUGINS = listOf(PluginId("style.standard"), PluginId("style.touring"))
        const val TAG = "GrassMapsStyle"
        const val DEFAULT_IDLE_TIMEOUT_MS = 10_000L
    }
}
