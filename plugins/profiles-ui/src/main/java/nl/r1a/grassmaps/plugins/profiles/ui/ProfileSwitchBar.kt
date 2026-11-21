package nl.r1a.grassmaps.plugins.profiles.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmaps.plugin.api.model.BuiltInProfiles
import nl.r1a.grassmaps.plugin.api.model.ProfileId
import nl.r1a.grassmaps.plugin.ui.api.LocalShellColors

/**
 * The round 8 profile bar: three pinned profile dots and a fourth button for
 * all profiles, in one pill (T-0604).
 *
 * A tap on any pinned dot moves to the next profile in [order]; a long press
 * opens the full profiles screen, as the fourth button does. The active dot is
 * filled with its profile colour, so the map shows the active profile without
 * opening a screen. The bar only reports labels and calls back; the host owns
 * the controller that publishes the five stored categories.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun ProfileSwitchBar(
    active: ProfileId,
    order: List<ProfileId>,
    onNext: () -> Unit,
    onOpenAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalShellColors.current
    val palette = profileSwitchPalette(colors)
    val labels =
        ProfileSwitchLabels(
            walk = stringResource(R.string.profiles_walk),
            bike = stringResource(R.string.profiles_bike),
            hike = stringResource(R.string.profiles_hike),
            all = stringResource(R.string.profiles_switch_all),
        )
    val items = profileSwitchItems(active, labels, palette)
    val activeLabel = items.firstOrNull { it.selected }?.label ?: labels.all
    val next = nextProfile(order, active)
    val nextLabel = items.firstOrNull { it.id == next }?.label ?: labels.all
    val description = stringResource(R.string.profiles_switch_description, activeLabel, nextLabel)
    Row(
        modifier =
            modifier
                .height(BAR_HEIGHT_DP.dp)
                .background(palette.bar, RoundedCornerShape(BAR_CORNER_DP.dp))
                .border(1.dp, palette.border, RoundedCornerShape(BAR_CORNER_DP.dp))
                .padding(horizontal = BAR_INSET_DP.dp, vertical = DOT_INSET_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DOT_GAP_DP.dp),
    ) {
        items.forEach { item ->
            Box(
                modifier =
                    Modifier
                        .size(DOT_SIZE_DP.dp)
                        .background(item.fill, CircleShape)
                        .combinedClickable(role = Role.Button, onClick = onNext, onLongClick = onOpenAll)
                        .semantics {
                            contentDescription = "${item.label}. $description"
                            selected = item.selected
                        },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = switchGlyph(item.id),
                    style = TextStyle(color = item.content, fontSize = DOT_FONT_SP.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
        Box(
            modifier =
                Modifier
                    .size(DIVIDER_WIDTH_DP.dp, DIVIDER_HEIGHT_DP.dp)
                    .background(palette.border)
                    .clearAndSetSemantics {},
        )
        val allSelected = items.none { it.selected }
        Box(
            modifier =
                Modifier
                    .size(DOT_SIZE_DP.dp)
                    .background(if (allSelected) palette.ink else palette.quiet, CircleShape)
                    .clickable(role = Role.Button, onClick = onOpenAll)
                    .semantics { contentDescription = labels.all },
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = GRID_GLYPH,
                style =
                    TextStyle(
                        color = if (allSelected) palette.onAccent else palette.ink,
                        fontSize = GRID_FONT_SP.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

/** A stable initial for each dot, so a locale change never redraws the bar's shape. */
private fun switchGlyph(id: ProfileId): String =
    when (id) {
        BuiltInProfiles.WALK -> "W"
        BuiltInProfiles.BIKE -> "B"
        BuiltInProfiles.HIKE -> "H"
        else -> "•"
    }

private const val BAR_HEIGHT_DP = 52
private const val BAR_CORNER_DP = 26
private const val BAR_INSET_DP = 8
private const val DOT_INSET_DP = 6
private const val DOT_SIZE_DP = 36
private const val DOT_GAP_DP = 4
private const val DOT_FONT_SP = 16
private const val DIVIDER_WIDTH_DP = 1
private const val DIVIDER_HEIGHT_DP = 28
private const val GRID_FONT_SP = 18
private const val GRID_GLYPH = "▦"
