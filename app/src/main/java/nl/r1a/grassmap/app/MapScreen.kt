package nl.r1a.grassmap.app

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.r1a.grassmap.core.services.ButtonLayoutStore
import nl.r1a.grassmap.core.services.DEFAULT_PROFILE
import nl.r1a.grassmap.core.services.MapControlCommands
import nl.r1a.grassmap.map.MapSurface
import nl.r1a.grassmap.map.MapSurfaceView
import nl.r1a.grassmap.map.PuckAgeFormatter
import nl.r1a.grassmap.map.prepareMapEngine
import nl.r1a.grassmap.map.rememberMapSurface
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.error.onFailure
import nl.r1a.grassmap.plugin.api.model.SlottedLayer
import nl.r1a.grassmap.plugin.api.model.StyleDiff
import nl.r1a.grassmap.plugin.api.model.StyleId
import nl.r1a.grassmap.plugin.api.model.StyleSpec
import nl.r1a.grassmap.plugin.api.provider.MapLayerContract
import nl.r1a.grassmap.plugin.api.provider.MapLayerProvider
import nl.r1a.grassmap.ui.shell.ControlBarLabels
import nl.r1a.grassmap.ui.shell.ControlButton
import nl.r1a.grassmap.ui.shell.MapControlBar
import java.io.File

private val STYLE_PLUGIN = PluginId("style.standard")
private val STANDARD = StyleId("standard")
private val TOURING = StyleId("touring")
private const val TAG = "GrassMapsMap"
private const val LAYOUT_DIRECTORY = "controls"
private const val LAYOUT_FILE = "button-layout.json"
private const val BAR_PADDING_DP = 16

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
    harness: MapHarness,
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
    val ageLabel =
        remember(context) {
            PuckAgeFormatter { ageMillis -> formatPuckAge(context, ageMillis) }
        }
    val surface =
        rememberMapSurface(
            initialStyle = loaded.style,
            initialCamera = loaded.sources.camera,
            sourceResolver = { loaded.sources.uri(it) },
            ageLabel = ageLabel,
        )
    BindMapSession(container.mapSession, surface)
    MapContent(container, surface, loaded, harness)
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun BindMapSession(
    session: MapSessionHolder,
    surface: MapSurface,
) {
    DisposableEffect(session, surface.host) {
        session.bind(surface.host)
        onDispose { session.unbind(surface.host) }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun MapContent(
    container: AppContainer,
    surface: MapSurface,
    bundle: MapBundle,
    harness: MapHarness,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(container.locationController, surface.host) {
        container.locationController.syncToHost()
    }
    val store by
        produceState<ButtonLayoutStore?>(null) {
            value =
                withContext(Dispatchers.IO) {
                    ButtonLayoutStore(
                        File(context.applicationContext.filesDir, "$LAYOUT_DIRECTORY/$LAYOUT_FILE"),
                    )
                }
        }
    var layout by remember { mutableStateOf(MapControlCommands.DEFAULT_ORDER) }
    var isEditing by remember { mutableStateOf(false) }
    val currentLayout = rememberUpdatedState(layout)
    val writer =
        remember(store) {
            store?.let { store ->
                LayoutWriter(
                    currentLayout = { currentLayout.value },
                    save = { layout -> withContext(Dispatchers.IO) { store.save(DEFAULT_PROFILE, layout) } },
                )
            }
        }
    LaunchedEffect(store) {
        val active = store ?: return@LaunchedEffect
        layout = withContext(Dispatchers.IO) { active.load(DEFAULT_PROFILE) }
    }
    Box(Modifier.fillMaxSize()) {
        MapSurfaceView(surface = surface, modifier = Modifier.fillMaxSize())
        MapControlBar(
            layout = controlButtons(layout, context),
            labels = controlBarLabels(context),
            isEditing = isEditing,
            onInvoke = { id ->
                scope.launch {
                    container.commandBus.invoke(id).onFailure { error ->
                        Log.w(TAG, "control $id failed: $error")
                        if (error == AppError.PermissionDenied) {
                            Toast.makeText(context, R.string.location_permission_denied, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            onMove = onMove@{ id, delta ->
                val activeWriter = writer ?: return@onMove
                val moved = moveControl(layout, id, delta)
                if (moved == layout) {
                    return@onMove
                }
                layout = moved
                scope.launch {
                    activeWriter.saveLatest().onFailure { error ->
                        Log.w(TAG, "layout not saved: $error")
                    }
                }
            },
            onEditingChange = { isEditing = it },
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(BAR_PADDING_DP.dp),
        )
    }
    LaunchedEffect(surface.host, bundle) {
        launch(start = CoroutineStart.UNDISPATCHED) { harness.run(surface, bundle) }
        surface.host.setStyle(bundle.style).onFailure { error ->
            Log.w(TAG, "style rejected: $error")
        }
        surface.host.applyStyleDiff(StyleDiff(bundle.style.id, bundle.layers)).onFailure { error ->
            Log.w(TAG, "style layers rejected: $error")
        }
        when (val installed = installDataSourceLayer(container.dataSources, surface.host)) {
            is Outcome.Success -> Log.i(TAG, "data source layer ready owner=${container.dataSources.pluginId}")
            is Outcome.Failure -> Log.w(TAG, "data source layer not applied: ${installed.error}")
        }
        Log.i(
            TAG,
            "map-ready region=${bundle.sources.regionName} contours=${bundle.sources.hasContours}",
        )
    }
}

/**
 * The age shown next to the grey ring. Seconds under a minute, minutes after
 * that; "just now" covers a fix that is only a few seconds old.
 */
internal fun formatPuckAge(
    context: Context,
    ageMillis: Long,
): String {
    val seconds = ageMillis / MILLIS_PER_SECOND
    return when {
        seconds < AGE_NOW_SECONDS -> context.getString(R.string.location_age_now)
        seconds < SECONDS_PER_MINUTE -> context.getString(R.string.location_age_seconds, seconds)
        else -> context.getString(R.string.location_age_minutes, seconds / SECONDS_PER_MINUTE)
    }
}

private const val MILLIS_PER_SECOND = 1_000L
private const val AGE_NOW_SECONDS = 10L
private const val SECONDS_PER_MINUTE = 60L

/** Moves one control by [delta] positions; an out-of-range move keeps the order. */
internal fun moveControl(
    layout: List<CommandId>,
    id: CommandId,
    delta: Int,
): List<CommandId> {
    val from = layout.indexOf(id)
    if (from < 0) {
        return layout
    }
    val to = from + delta
    if (to !in layout.indices) {
        return layout
    }
    return layout.toMutableList().apply {
        add(to, removeAt(from))
    }
}

private fun controlButtons(
    layout: List<CommandId>,
    context: Context,
): List<ControlButton> {
    val buttons = layout.mapNotNull { id -> controlButton(id, context) }
    return buttons.ifEmpty { MapControlCommands.DEFAULT_ORDER.mapNotNull { controlButton(it, context) } }
}

private fun controlButton(
    id: CommandId,
    context: Context,
): ControlButton? =
    when (id) {
        MapControlCommands.ZOOM_IN -> {
            ControlButton(
                id = id,
                label = context.getString(R.string.control_zoom_in_label),
                contentDescription = context.getString(R.string.control_zoom_in_description),
            )
        }

        MapControlCommands.ZOOM_OUT -> {
            ControlButton(
                id = id,
                label = context.getString(R.string.control_zoom_out_label),
                contentDescription = context.getString(R.string.control_zoom_out_description),
            )
        }

        MapControlCommands.NORTH_UP -> {
            ControlButton(
                id = id,
                label = context.getString(R.string.control_north_up_label),
                contentDescription = context.getString(R.string.control_north_up_description),
            )
        }

        MapControlCommands.FOLLOW -> {
            ControlButton(
                id = id,
                label = context.getString(R.string.control_follow_label),
                contentDescription = context.getString(R.string.control_follow_description),
            )
        }

        else -> {
            null
        }
    }

private fun controlBarLabels(context: Context): ControlBarLabels =
    ControlBarLabels(
        edit = context.getString(R.string.control_edit_label),
        editDone = context.getString(R.string.control_edit_done_label),
        editDescription = context.getString(R.string.control_edit_description),
        moveLeft = context.getString(R.string.control_move_left_label),
        moveLeftDescription = context.getString(R.string.control_move_left_description),
        moveRight = context.getString(R.string.control_move_right_label),
        moveRightDescription = context.getString(R.string.control_move_right_description),
    )

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
