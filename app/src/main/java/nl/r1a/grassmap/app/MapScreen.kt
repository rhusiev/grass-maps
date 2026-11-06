package nl.r1a.grassmap.app

import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.r1a.grassmap.map.MapSurface
import nl.r1a.grassmap.map.MapSurfaceView
import nl.r1a.grassmap.map.prepareMapEngine
import nl.r1a.grassmap.map.rememberMapSurface
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.error.onFailure
import nl.r1a.grassmap.plugin.api.host.MapEvent
import nl.r1a.grassmap.plugin.api.model.SlottedLayer
import nl.r1a.grassmap.plugin.api.model.StyleDiff
import nl.r1a.grassmap.plugin.api.model.StyleId
import nl.r1a.grassmap.plugin.api.model.StyleSpec
import nl.r1a.grassmap.plugin.api.provider.MapLayerContract
import nl.r1a.grassmap.plugin.api.provider.MapLayerProvider

private val STYLE_PLUGIN = PluginId("style.standard")
private val STANDARD = StyleId("standard")
private val TOURING = StyleId("touring")
private const val TAG = "T0020"
private const val EXTRA_STYLE_SWITCHES = "t0020_style_switches"
private const val SWITCH_SETTLE_MS = 3_000L

internal data class MapBundle(
    val style: StyleSpec,
    val layers: List<SlottedLayer>,
    val touring: StyleSpec,
    val touringLayers: List<SlottedLayer>,
    val sources: MapSources,
)

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun MapScreen(
    container: AppContainer,
    styleSwitches: Int,
) {
    val context = LocalContext.current
    val bundle by
        produceState<MapBundle?>(null) {
            value =
                withContext(Dispatchers.IO) {
                    prepareMapEngine(context.applicationContext)
                    loadBundle(container, context.applicationContext)
                }
            if (value == null) {
                Log.w(TAG, "no map style provider; check the style.standard plugin manifest")
            }
        }
    val loaded = bundle
    if (loaded == null) {
        Box(Modifier.fillMaxSize().background(Color(0xFF202020)))
        return
    }
    val surface = rememberMapSurface(loaded.style, loaded.sources.camera) { loaded.sources.uri(it) }
    MapSurfaceView(surface = surface, modifier = Modifier.fillMaxSize())
    MapMeasurement(surface, loaded, styleSwitches)
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun MapMeasurement(
    surface: MapSurface,
    bundle: MapBundle,
    styleSwitches: Int,
) {
    LaunchedEffect(surface.host, bundle) {
        var sawFullFrame = false
        var loggedFirstFrame = false
        var switchesDone = false
        launch {
            surface.host.events.collect { event ->
                when (event) {
                    MapEvent.StyleLoaded -> {
                        Log.i(TAG, "style-loaded total=${surface.host.styleLoadCount}")
                    }

                    is MapEvent.FrameRendered -> {
                        if (event.full && !loggedFirstFrame) {
                            loggedFirstFrame = true
                            sawFullFrame = true
                            val elapsed = SystemClock.uptimeMillis() - Process.getStartUptimeMillis()
                            Log.i(TAG, "first-full-frame-ms=$elapsed")
                        }
                    }

                    MapEvent.Idle -> {
                        if (sawFullFrame && !switchesDone && styleSwitches > 0) {
                            switchesDone = true
                            Log.i(TAG, "switch-start")
                            repeat(styleSwitches) { index ->
                                val touring = index % 2 == 0
                                surface.host
                                    .applyStyleDiff(
                                        if (touring) {
                                            StyleDiff(TOURING, bundle.touringLayers)
                                        } else {
                                            StyleDiff(STANDARD, bundle.layers)
                                        },
                                    ).onFailure { error -> Log.w(TAG, "style diff rejected: $error") }
                            }
                            delay(SWITCH_SETTLE_MS)
                            Log.i(
                                TAG,
                                "style-switches=$styleSwitches total-style-loads=${surface.host.styleLoadCount}",
                            )
                        }
                    }

                    else -> {
                        Unit
                    }
                }
            }
        }
        surface.host.setStyle(bundle.style).onFailure { error ->
            Log.w(TAG, "style rejected: $error")
        }
        surface.host.applyStyleDiff(StyleDiff(bundle.style.id, bundle.layers)).onFailure { error ->
            Log.w(TAG, "style layers rejected: $error")
        }
        Log.i(
            TAG,
            "map-ready region=${bundle.sources.regionName} contours=${bundle.sources.hasContours}",
        )
    }
}

private suspend fun loadBundle(
    container: AppContainer,
    context: Context,
): MapBundle? {
    val sources = MapSources.resolve(context)
    val result =
        container.providerRegistry.invoke(STYLE_PLUGIN, MapLayerContract.REFERENCE) { provider ->
            val mapLayers = provider as? MapLayerProvider ?: return@invoke null
            val styles = mapLayers.styles()
            val standard = styles.firstOrNull { it.id == STANDARD } ?: return@invoke null
            val touring = styles.firstOrNull { it.id == TOURING } ?: standard
            MapBundle(
                style = standard,
                layers = mapLayers.layers(standard.id),
                touring = touring,
                touringLayers = mapLayers.layers(touring.id),
                sources = sources,
            )
        }
    return (result as? Outcome.Success)?.value
}

/** Reads the style-switch count from the launch intent in debug builds only. */
internal fun styleSwitchCount(
    intent: Intent,
    debuggable: Boolean,
): Int = if (debuggable) intent.getIntExtra(EXTRA_STYLE_SWITCHES, 0) else 0
