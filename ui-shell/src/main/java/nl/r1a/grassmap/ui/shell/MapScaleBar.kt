package nl.r1a.grassmap.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The map scale bar (T-0107).
 *
 * It draws only what the caller computed: [widthDp] is the bar length for
 * [label]'s ground distance, so the shell never re-derives a ground scale. The
 * span from the first end tick to the last is exactly [widthDp], as a scale bar
 * is read between its ticks.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun MapScaleBar(
    label: String,
    widthDp: Float,
    description: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .background(Color(0x99000000), RoundedCornerShape(BAR_CORNER_DP.dp))
                .padding(horizontal = BAR_PADDING_HORIZONTAL_DP.dp, vertical = BAR_PADDING_VERTICAL_DP.dp)
                .semantics { contentDescription = description },
        horizontalAlignment = Alignment.Start,
    ) {
        BasicText(
            text = label,
            style =
                TextStyle(
                    color = Color.White,
                    fontSize = FONT_SP.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
        )
        Row(
            modifier =
                Modifier
                    .padding(top = LABEL_GAP_DP.dp)
                    .width(widthDp.coerceAtLeast(MIN_BAR_WIDTH_DP).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tick()
            Box(Modifier.weight(1f).height(LINE_HEIGHT_DP.dp).background(Color.White))
            Tick()
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun Tick() {
    Box(Modifier.width(TICK_WIDTH_DP.dp).height(TICK_HEIGHT_DP.dp).background(Color.White))
}

private const val BAR_CORNER_DP = 8
private const val BAR_PADDING_HORIZONTAL_DP = 8
private const val BAR_PADDING_VERTICAL_DP = 6
private const val FONT_SP = 12
private const val LABEL_GAP_DP = 4
private const val MIN_BAR_WIDTH_DP = 2f
private const val TICK_WIDTH_DP = 1
private const val TICK_HEIGHT_DP = 6
private const val LINE_HEIGHT_DP = 1
