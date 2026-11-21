package nl.r1a.grassmaps.plugins.raster

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmaps.plugin.ui.api.LocalShellColors
import kotlin.math.roundToInt

internal val PANEL_BACKGROUND: Color
    @Composable @ReadOnlyComposable
    get() = LocalShellColors.current.surface

internal val PANEL_FOREGROUND: Color
    @Composable @ReadOnlyComposable
    get() = LocalShellColors.current.ink

internal val PANEL_MUTED: Color
    @Composable @ReadOnlyComposable
    get() = LocalShellColors.current.muted

internal val FIELD_BACKGROUND: Color
    @Composable @ReadOnlyComposable
    get() = LocalShellColors.current.soft

internal val FIELD_LINE: Color
    @Composable @ReadOnlyComposable
    get() = LocalShellColors.current.controlBorder
internal const val PANEL_CORNER_DP = 8
internal const val PANEL_HORIZONTAL_DP = 12
internal const val PANEL_VERTICAL_DP = 8
internal const val PANEL_GAP_DP = 6
internal const val PANEL_FONT_SP = 13
private const val SLIDER_HEIGHT_DP = 24
private val TRACK_STROKE: Dp = 2.dp
private val KNOB_RADIUS: Dp = 7.dp

/**
 * The minimal online-tile panel: the OSM preset toggle, what enabling sends,
 * the layer's placement and transparency, its visible credit, and a URL
 * template field for a custom source.
 *
 * T-0109 owns the full layers panel; until it lands this row is how the user
 * reaches online tiles, the user-added source, the underlay/overlay placement
 * and the transparency (T-0514). The panel is chrome: the app owns the privacy
 * switch and the layer settings, and the plugin supplies the row and the copy.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun RasterLayerPanel(
    enabled: Boolean,
    source: RasterSource,
    placement: RasterPlacement,
    percent: Int,
    onEnabledChange: (Boolean) -> Unit,
    onPlacementChange: (RasterPlacement) -> Unit,
    onTransparencyChange: (percent: Int, store: Boolean) -> Unit,
    onCustomSource: (String) -> Unit,
    modifier: Modifier = Modifier,
    onPresetChange: ((String) -> Unit)? = null,
    areaModel: RasterAreaSectionModel? = null,
    areaActions: RasterAreaSectionActions? = null,
) {
    var template by remember(source) {
        mutableStateOf(if (source.isOpenStreetMap()) "" else source.tileUrlTemplate)
    }
    val attribution =
        if (source.isOpenStreetMap()) {
            stringResource(R.string.map_raster_attribution)
        } else {
            source.attributionHtml
        }
    val shape = RoundedCornerShape(PANEL_CORNER_DP.dp)
    Column(
        modifier =
            modifier
                .clip(shape)
                .background(PANEL_BACKGROUND)
                .border(1.dp, LocalShellColors.current.ring, shape)
                .padding(horizontal = PANEL_HORIZONTAL_DP.dp, vertical = PANEL_VERTICAL_DP.dp),
        verticalArrangement = Arrangement.spacedBy(PANEL_GAP_DP.dp),
    ) {
        PanelText(stringResource(R.string.map_raster_panel_title), PANEL_MUTED)
        Row(
            modifier =
                Modifier
                    .clip(RoundedCornerShape(PANEL_CORNER_DP.dp))
                    .toggleable(
                        value = enabled,
                        role = Role.Switch,
                        onValueChange = onEnabledChange,
                    ).padding(vertical = PANEL_GAP_DP.dp),
            horizontalArrangement = Arrangement.spacedBy(PANEL_GAP_DP.dp),
        ) {
            PanelText(source.displayName, PANEL_FOREGROUND)
            PanelText(
                stringResource(if (enabled) R.string.map_raster_on else R.string.map_raster_off),
                PANEL_MUTED,
            )
        }
        PanelText(stringResource(R.string.map_raster_sends), PANEL_MUTED)
        if (enabled) {
            PlacementRow(
                placement = placement,
                onPlacementChange = onPlacementChange,
            )
            TransparencyControl(
                percent = percent,
                onTransparencyChange = onTransparencyChange,
            )
            if (attribution.isNotBlank()) {
                PanelText(attribution, PANEL_MUTED)
            }
            source.licence?.let { LicenceLines(it) }
            if (source.offlineDownload != OfflineTileDownload.ALLOWED) {
                PanelText(stringResource(R.string.map_raster_online_only), PANEL_FOREGROUND)
            }
            onPresetChange?.let { PresetRow(source.id, it) }
            PanelText(stringResource(R.string.map_raster_custom_hint), PANEL_MUTED)
            BasicTextField(
                value = template,
                onValueChange = { template = it },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(PANEL_CORNER_DP.dp))
                        .background(FIELD_BACKGROUND)
                        .border(1.dp, FIELD_LINE, RoundedCornerShape(PANEL_CORNER_DP.dp))
                        .padding(horizontal = PANEL_GAP_DP.dp, vertical = PANEL_GAP_DP.dp),
                textStyle = TextStyle(color = PANEL_FOREGROUND, fontSize = PANEL_FONT_SP.sp),
                singleLine = true,
            )
            Row(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(PANEL_CORNER_DP.dp))
                        .clickable { onCustomSource(template.trim()) }
                        .padding(vertical = PANEL_GAP_DP.dp),
                horizontalArrangement = Arrangement.spacedBy(PANEL_GAP_DP.dp),
            ) {
                PanelText(stringResource(R.string.map_raster_custom_use), PANEL_FOREGROUND)
            }
            PanelText(stringResource(R.string.map_raster_custom_terms), PANEL_MUTED)
        }
        if (areaModel != null && areaActions != null) {
            RasterAreaSection(areaModel, areaActions)
        }
    }
}

/** One choice per built-in source: the exclusive presets, nothing else. */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun PresetRow(
    selectedId: String,
    onPresetChange: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(PANEL_GAP_DP.dp)) {
        RasterPresets.all.forEach { preset ->
            PlacementChoice(
                label = preset.displayName,
                selected = preset.id == selectedId,
                onClick = { onPresetChange(preset.id) },
            )
        }
    }
}

/** The underlay/overlay choice: one pair of exclusive options, nothing else. */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun PlacementRow(
    placement: RasterPlacement,
    onPlacementChange: (RasterPlacement) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(PANEL_GAP_DP.dp)) {
        PlacementChoice(
            label = stringResource(R.string.map_raster_underlay),
            selected = placement == RasterPlacement.Underlay,
            onClick = { onPlacementChange(RasterPlacement.Underlay) },
        )
        PlacementChoice(
            label = stringResource(R.string.map_raster_overlay),
            selected = placement == RasterPlacement.Overlay,
            onClick = { onPlacementChange(RasterPlacement.Overlay) },
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun PlacementChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(PANEL_CORNER_DP.dp))
                .background(if (selected) FIELD_BACKGROUND else Color.Transparent)
                .selectable(
                    selected = selected,
                    role = Role.RadioButton,
                    onClick = onClick,
                ).padding(horizontal = PANEL_GAP_DP.dp, vertical = PANEL_VERTICAL_DP.dp),
    ) {
        PanelText(label, if (selected) PANEL_FOREGROUND else PANEL_MUTED)
    }
}

/**
 * The transparency control: the named percentage and a track the user taps or
 * drags. It is a slider, not a stepper, and it is the only control this task
 * adds; the full layers panel takes it over with its final placement (T-0109).
 *
 * Every step of a drag updates the map, and only the value the finger leaves is
 * stored, so a drag does not write the settings file once per frame.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun TransparencyControl(
    percent: Int,
    onTransparencyChange: (percent: Int, store: Boolean) -> Unit,
) {
    val label = stringResource(R.string.map_raster_transparency)
    val colors = LocalShellColors.current
    val latest by rememberUpdatedState(onTransparencyChange)
    Column(verticalArrangement = Arrangement.spacedBy(PANEL_GAP_DP.dp)) {
        PanelText("$label $percent %", PANEL_FOREGROUND)
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(SLIDER_HEIGHT_DP.dp)
                    .semantics {
                        contentDescription = label
                        progressBarRangeInfo = ProgressBarRangeInfo(percent.toFloat(), PERCENT_RANGE)
                    }.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val down = awaitFirstDown()
                                var value = percentAt(down.position.x, size.width)
                                latest(value, false)
                                down.consume()
                                drag(down.id) { change ->
                                    change.consume()
                                    value = percentAt(change.position.x, size.width)
                                    latest(value, false)
                                }
                                latest(value, true)
                            }
                        }
                    },
        ) {
            val centerY = size.height / 2f
            val filled = size.width * percent / RasterTransparency.MAX_PERCENT
            drawLine(colors.muted, Offset(0f, centerY), Offset(size.width, centerY), strokeWidth = TRACK_STROKE.toPx())
            drawLine(colors.ink, Offset(0f, centerY), Offset(filled, centerY), strokeWidth = TRACK_STROKE.toPx())
            drawCircle(colors.ink, radius = KNOB_RADIUS.toPx(), center = Offset(filled, centerY))
        }
    }
}

/** The percent a track position names, clamped to the control's range. */
private fun percentAt(
    x: Float,
    width: Int,
): Int {
    if (width <= 0) {
        return RasterTransparency.MIN_PERCENT
    }
    val fraction = (x / width).coerceIn(0f, 1f)
    return (fraction * RasterTransparency.MAX_PERCENT).roundToInt()
}

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun PanelText(
    text: String,
    color: Color,
) {
    BasicText(
        text = text,
        style = TextStyle(color = color, fontSize = PANEL_FONT_SP.sp),
    )
}

private val PERCENT_RANGE =
    RasterTransparency.MIN_PERCENT.toFloat()..RasterTransparency.MAX_PERCENT.toFloat()
