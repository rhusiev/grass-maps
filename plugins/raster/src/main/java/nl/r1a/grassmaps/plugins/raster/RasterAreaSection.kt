package nl.r1a.grassmaps.plugins.raster

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import nl.r1a.grassmaps.plugin.api.model.BoundingBox
import nl.r1a.grassmaps.plugin.ui.api.LayerRowSpec

private const val DEFAULT_FROM_ZOOM = 8
private const val DEFAULT_TO_ZOOM = 12

/** What the offline section needs from the app. */
data class RasterAreaSectionModel(
    val items: List<RasterAreaItem>,
    val areasShown: Boolean,
    val state: RasterAreaDownloadState,
    val onlineEnabled: Boolean,
    val source: RasterSource,
    val viewBounds: BoundingBox?,
    val refusalOf: (RasterAreaPlan) -> RasterAreaPlan.Refusal?,
    val freeBytes: Long?,
)

/** The callbacks of the offline section. */
data class RasterAreaSectionActions(
    val onAreasShownChange: (Boolean) -> Unit,
    val onDownload: (RasterAreaPlan, String) -> Unit,
    val onCancel: () -> Unit,
    val onDelete: (String) -> Unit,
    val onAcknowledge: () -> Unit,
)

/**
 * The offline raster areas inside the layers panel (T-0513). Read-only
 * information comes first: the installed areas with their licence, then the
 * estimate and licence of the area about to be downloaded. The controls follow
 * and the Download button is the last row.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun RasterAreaSection(
    model: RasterAreaSectionModel,
    actions: RasterAreaSectionActions,
) {
    var fromZoom by remember(model.source) { mutableIntStateOf(DEFAULT_FROM_ZOOM.coerceIn(model.source.minZoom, model.source.maxZoom)) }
    var toZoom by remember(model.source) { mutableIntStateOf(DEFAULT_TO_ZOOM.coerceIn(fromZoom, model.source.maxZoom)) }
    val plan =
        remember(model.source, model.viewBounds, fromZoom, toZoom) {
            model.viewBounds?.let { RasterAreaPlan.of(model.source, it, fromZoom, toZoom) }
        }
    Column(verticalArrangement = Arrangement.spacedBy(PANEL_GAP_DP.dp)) {
        PanelText(stringResource(R.string.map_raster_areas_title), PANEL_MUTED)
        InstalledAreas(model, actions)
        if (plan != null) {
            PlanInfo(plan, model)
        }
        val running = model.state is RasterAreaDownloadState.Running
        if (!running) {
            ZoomStepper(R.string.map_raster_area_from, fromZoom, model.source.minZoom..toZoom) { fromZoom = it }
            ZoomStepper(R.string.map_raster_area_to, toZoom, fromZoom..model.source.maxZoom) { toZoom = it }
        }
        DownloadRow(plan, model, actions)
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun InstalledAreas(
    model: RasterAreaSectionModel,
    actions: RasterAreaSectionActions,
) {
    val context = LocalContext.current
    if (model.items.isEmpty()) {
        PanelText(stringResource(R.string.map_raster_areas_none), PANEL_MUTED)
        return
    }
    model.items.forEach { item ->
        Column(verticalArrangement = Arrangement.spacedBy(PANEL_GAP_DP.dp / 2)) {
            PanelText(item.name, PANEL_FOREGROUND)
            PanelText(
                stringResource(
                    R.string.map_raster_area_row,
                    item.tileCount,
                    Formatter.formatShortFileSize(context, item.bytes),
                    item.minZoom,
                    item.maxZoom,
                ),
                PANEL_MUTED,
            )
            LicenceLines(item.licence)
            ActionText(stringResource(R.string.map_raster_area_delete)) { actions.onDelete(item.id) }
        }
    }
    SwitchRow(stringResource(R.string.map_raster_areas_show), model.areasShown, actions.onAreasShownChange)
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun PlanInfo(
    plan: RasterAreaPlan,
    model: RasterAreaSectionModel,
) {
    val context = LocalContext.current
    if (plan.source.offlineDownload != OfflineTileDownload.ALLOWED) {
        model.refusalOf(plan)?.let { PanelText(refusalText(it), PANEL_FOREGROUND) }
        return
    }
    PanelText(
        stringResource(
            R.string.map_raster_area_estimate,
            plan.tileCount,
            Formatter.formatShortFileSize(context, plan.estimatedBytes),
        ),
        PANEL_FOREGROUND,
    )
    model.freeBytes?.let { free ->
        PanelText(stringResource(R.string.map_raster_area_free, Formatter.formatShortFileSize(context, free)), PANEL_MUTED)
    }
    plan.source.licence?.let { LicenceLines(it) }
    model.refusalOf(plan)?.let { PanelText(refusalText(it), PANEL_FOREGROUND) }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun refusalText(refusal: RasterAreaPlan.Refusal): String =
    when (refusal) {
        RasterAreaPlan.Refusal.SourceForbidsOffline -> {
            stringResource(R.string.map_raster_area_refused_source)
        }

        RasterAreaPlan.Refusal.NoLicence -> {
            stringResource(R.string.map_raster_area_refused_licence)
        }

        RasterAreaPlan.Refusal.TooManyTiles -> {
            stringResource(R.string.map_raster_area_refused_tiles, RasterAreaPlan.MAX_TILES)
        }

        RasterAreaPlan.Refusal.TooManyBytes -> {
            stringResource(R.string.map_raster_area_refused_bytes, RasterAreaPlan.MAX_BYTES / BYTES_PER_MIB)
        }

        RasterAreaPlan.Refusal.NotEnoughSpace -> {
            stringResource(R.string.map_raster_area_refused_space)
        }
    }

/** The licence name, its link text and the non-commercial notice, shown online and offline. */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun LicenceLines(licence: RasterLicence) {
    PanelText(stringResource(R.string.map_raster_licence, licence.name), PANEL_MUTED)
    if (licence.url.isNotBlank()) {
        PanelText(licence.url, PANEL_MUTED)
    }
    if (licence.isNonCommercial) {
        PanelText(stringResource(R.string.map_raster_licence_nc), PANEL_FOREGROUND)
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun ZoomStepper(
    label: Int,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(PANEL_GAP_DP.dp)) {
        PanelText(stringResource(label, value), PANEL_FOREGROUND)
        val less = stringResource(R.string.map_raster_area_less)
        val more = stringResource(R.string.map_raster_area_more)
        StepButton("-", less, enabled = value > range.first) { onChange(value - 1) }
        StepButton("+", more, enabled = value < range.last) { onChange(value + 1) }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun StepButton(
    symbol: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(PANEL_CORNER_DP.dp))
                .background(FIELD_BACKGROUND)
                .semantics { contentDescription = description }
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .heightIn(min = LayerRowSpec.ROW_MIN_HEIGHT_DP.dp)
                .wrapContentSize(Alignment.Center)
                .padding(horizontal = PANEL_VERTICAL_DP.dp),
    ) {
        PanelText(symbol, if (enabled) PANEL_FOREGROUND else PANEL_MUTED)
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun SwitchRow(
    label: String,
    value: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .toggleable(value = value, role = Role.Switch, onValueChange = onChange)
                .heightIn(min = LayerRowSpec.ROW_MIN_HEIGHT_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(PANEL_GAP_DP.dp),
    ) {
        PanelText(label, PANEL_FOREGROUND)
        PanelText(stringResource(if (value) R.string.map_raster_on else R.string.map_raster_off), PANEL_MUTED)
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun ActionText(
    text: String,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(PANEL_CORNER_DP.dp))
                .clickable(role = Role.Button, onClick = onClick)
                .heightIn(min = LayerRowSpec.ROW_MIN_HEIGHT_DP.dp)
                .wrapContentSize(Alignment.Center),
    ) {
        PanelText(text, PANEL_FOREGROUND)
    }
}

/** The last row: progress and Cancel while a download runs, else the Download button. */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun DownloadRow(
    plan: RasterAreaPlan?,
    model: RasterAreaSectionModel,
    actions: RasterAreaSectionActions,
) {
    when (val state = model.state) {
        is RasterAreaDownloadState.Running -> {
            PanelText(stringResource(R.string.map_raster_area_progress, state.progress.done, state.progress.total), PANEL_FOREGROUND)
            ActionText(stringResource(R.string.map_raster_area_cancel), actions.onCancel)
        }

        is RasterAreaDownloadState.Failed -> {
            PanelText(failureText(state.reason), PANEL_FOREGROUND)
            ActionText(stringResource(R.string.map_raster_area_dismiss), actions.onAcknowledge)
        }

        RasterAreaDownloadState.Done -> {
            PanelText(stringResource(R.string.map_raster_area_done), PANEL_FOREGROUND)
            ActionText(stringResource(R.string.map_raster_area_dismiss), actions.onAcknowledge)
        }

        RasterAreaDownloadState.Idle -> {
            if (!model.onlineEnabled) {
                PanelText(stringResource(R.string.map_raster_area_needs_online), PANEL_MUTED)
            }
            if (plan != null && model.onlineEnabled && model.refusalOf(plan) == null) {
                ActionText(stringResource(R.string.map_raster_area_download), { actions.onDownload(plan, areaName(plan)) })
            }
        }
    }
}

private fun areaName(plan: RasterAreaPlan): String = plan.source.displayName

@Suppress("ktlint:standard:function-naming")
@Composable
private fun failureText(reason: RasterAreaFailure): String =
    stringResource(
        when (reason) {
            RasterAreaFailure.SwitchOff -> R.string.map_raster_area_needs_online
            RasterAreaFailure.Network -> R.string.map_raster_area_failed_network
            RasterAreaFailure.Refused -> R.string.map_raster_area_failed_refused
            RasterAreaFailure.NotEnoughSpace -> R.string.map_raster_area_refused_space
            RasterAreaFailure.TooLarge -> R.string.map_raster_area_failed_too_large
            RasterAreaFailure.Cancelled -> R.string.map_raster_area_cancelled
            RasterAreaFailure.Other -> R.string.map_raster_area_failed_other
        },
    )

private const val BYTES_PER_MIB = 1024L * 1024L
