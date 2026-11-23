package nl.r1a.grassmaps.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import nl.r1a.grassmaps.plugin.ui.api.LocalShellColors

private const val BUTTON_DP = 48
private const val GLYPH_DP = 22
private const val STROKE_DP = 2f
private const val KNOB_DP = 3f
private val LINE_FRACTIONS = listOf(0.25f, 0.5f, 0.75f)
private val KNOB_FRACTIONS = listOf(0.32f, 0.68f, 0.5f)

/**
 * The map's user-visible path to the Settings index (T-0612). A drawn tune
 * glyph keeps this an asset-free control: no icon file needs attributing, and
 * the label is the only thing a screen reader reads.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun SettingsEntryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalShellColors.current
    Box(
        modifier =
            modifier
                .size(BUTTON_DP.dp)
                .clip(CircleShape)
                .background(colors.soft)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(GLYPH_DP.dp)) {
            val width = size.width
            val height = size.height
            val stroke = STROKE_DP.dp.toPx()
            val knobRadius = KNOB_DP.dp.toPx()
            LINE_FRACTIONS.forEach { fraction ->
                val y = height * fraction
                drawLine(colors.ink, Offset(0f, y), Offset(width, y), stroke, StrokeCap.Round)
            }
            KNOB_FRACTIONS.zip(LINE_FRACTIONS).forEach { (xFraction, yFraction) ->
                drawCircle(colors.accent, knobRadius, Offset(width * xFraction, height * yFraction))
            }
        }
    }
}
