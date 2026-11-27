package nl.r1a.grassmaps.plugin.ui.api

import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The stroke of each outlined row of `design-language.md` section "Outlines
 * and shadows" (round 9 canon, 2026-11-27). One token per row; a screen never
 * picks a stroke colour of its own.
 *
 * The rule: a control that acts is filled and has no outline. An outline marks
 * a choice (chip, alternative), an input (field, switch) or a warning. The
 * warning stroke is [WarningColors.border]; a selected chip, alternative or
 * switch strokes in its selection colour (the accent, a profile colour or the
 * inverted chip fill).
 *
 * [chip], [field] and [switchOff] keep the measured 3:1 control boundary of
 * T-0636 (light `#857f77`, dark `#80868b`). The canon values (`#dcd8d1`,
 * LINE `#ebe8e3`, `#b9b4ac`; dark `#474d54`, `#3a3f45`) measure 1.08:1 to
 * 1.83:1 on the surface and the soft fill, and `ShellContrastTest` holds these
 * three rows to 3:1. The other rows take the canon value.
 */
data class OutlineColors(
    /** A chip's 1 px outline: canon `#dcd8d1` / `#474d54`, held at the 3:1 boundary. */
    val chip: Color,
    /** A text field's 1 px outline: canon LINE / `#3a3f45`, held at the 3:1 boundary. */
    val field: Color,
    /** An unselected choice card (route alternative, map style): LINE / `#3a3f45`. */
    val choice: Color,
    /** An off switch, radio or check mark: canon `#b9b4ac` / `#80868b`, light held at 3:1. */
    val switchOff: Color,
    /** The dashed outline of an add tile, add stop or free slot: `#cfccc6` / `#55595e`. */
    val dashed: Color,
    /**
     * The 1 px ring a floating surface adds in dark, where a shadow vanishes on
     * the dark map: `rgba(255,255,255,.07)`. Light draws no ring.
     */
    val floatRing: Color,
)

internal val LIGHT_OUTLINE =
    OutlineColors(
        chip = Color(0xFF857F77),
        field = Color(0xFF857F77),
        choice = Color(0xFFEBE8E3),
        switchOff = Color(0xFF857F77),
        dashed = Color(0xFFCFCCC6),
        floatRing = Color.Transparent,
    )

internal val DARK_OUTLINE =
    OutlineColors(
        chip = Color(0xFF80868B),
        field = Color(0xFF80868B),
        choice = Color(0xFF3A3F45),
        switchOff = Color(0xFF80868B),
        dashed = Color(0xFF55595E),
        floatRing = Color(0x12FFFFFF),
    )

/**
 * A floating map button, bar, card or glass panel: a shadow, plus the
 * [OutlineColors.floatRing] in dark. Put it before the surface's background,
 * so the shadow lies under the fill.
 */
fun Modifier.floatingSurface(
    colors: ShellColors,
    shape: Shape,
): Modifier {
    val raised = shadow(FLOAT_ELEVATION, shape, clip = false)
    return if (colors.dark) raised.border(HAIRLINE, colors.outline.floatRing, shape) else raised
}

/**
 * A bottom sheet or a small floating pill: a shadow only, with no ring in
 * either theme. Put it before the surface's background.
 */
fun Modifier.sheetSurface(shape: Shape): Modifier = shadow(SHEET_ELEVATION, shape, clip = false)

/** The selected segment of a segmented control: a small shadow, no border. */
fun Modifier.selectedSegment(shape: Shape): Modifier = shadow(SEGMENT_ELEVATION, shape, clip = false)

/**
 * A chip's 1 px outline. A selected chip strokes in [selection]: the accent
 * or a profile colour over its tint, or [ShellColors.selectedChip] when it
 * inverts to the ink fill.
 */
fun Modifier.chipOutline(
    colors: ShellColors,
    shape: Shape,
    selection: Color? = null,
): Modifier = border(HAIRLINE, selection ?: colors.outline.chip, shape)

/** A text field's 1 px outline. A search field has none: it is a soft fill. */
fun Modifier.fieldOutline(
    colors: ShellColors,
    shape: Shape,
): Modifier = border(HAIRLINE, colors.outline.field, shape)

/**
 * A choice card (route alternative, map style, colour swatch): 1 px when not
 * chosen, 2 px in [selection] when chosen.
 */
fun Modifier.choiceOutline(
    colors: ShellColors,
    shape: Shape,
    selection: Color? = null,
): Modifier =
    if (selection == null) {
        border(HAIRLINE, colors.outline.choice, shape)
    } else {
        border(SELECTED_STROKE, selection, shape)
    }

/** A switch track, radio or check mark: 2 px, the accent when [on]. */
fun Modifier.switchOutline(
    colors: ShellColors,
    on: Boolean,
    shape: Shape,
): Modifier = border(SELECTED_STROKE, if (on) colors.accent else colors.outline.switchOff, shape)

/** The warning (alert) card's 1.5 px amber outline. */
fun Modifier.alertOutline(
    colors: ShellColors,
    shape: Shape,
): Modifier = border(ALERT_STROKE, colors.warning.border, shape)

/**
 * The 1.5 px dashed outline of an add tile, an add stop or a free layout
 * slot. It draws inside the element's bounds, so the element keeps its
 * measured size; [corner] is clamped to a pill.
 */
fun Modifier.dashedOutline(
    colors: ShellColors,
    corner: Dp,
): Modifier =
    drawBehind {
        val strokePx = DASHED_STROKE.toPx()
        val half = strokePx / 2f
        drawRoundRect(
            color = colors.outline.dashed,
            topLeft = Offset(half, half),
            size = Size(size.width - strokePx, size.height - strokePx),
            cornerRadius = CornerRadius(corner.toPx().coerceAtMost(size.minDimension / 2f)),
            style =
                Stroke(
                    width = strokePx,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(strokePx * DASH_ON, strokePx * DASH_OFF)),
                ),
        )
    }

private val HAIRLINE = 1.dp
private val ALERT_STROKE = 1.5.dp
private val DASHED_STROKE = 1.5.dp
private val SELECTED_STROKE = 2.dp

/** Canon `.fab` and `.bar`: `0 2px 6px` to `0 3px 12px`, black at .18 to .2. */
private val FLOAT_ELEVATION = 6.dp

/** Canon `.sheet` and `.mini`: `0 1px 4px` to `0 -2px 12px`, black at .14 to .16. */
private val SHEET_ELEVATION = 4.dp

/** Canon selected segment: `0 1px 3px`, black at .12. */
private val SEGMENT_ELEVATION = 2.dp

private const val DASH_ON = 3f
private const val DASH_OFF = 2f
