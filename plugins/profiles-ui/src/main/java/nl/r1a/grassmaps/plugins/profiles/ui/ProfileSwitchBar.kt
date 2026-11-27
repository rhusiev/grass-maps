package nl.r1a.grassmaps.plugins.profiles.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import nl.r1a.grassmaps.plugin.api.model.BuiltInProfiles
import nl.r1a.grassmaps.plugin.api.model.ProfileId
import nl.r1a.grassmaps.plugin.ui.api.LocalShellColors

/**
 * The round 8 profile bar: three pinned profile dots and a fourth button for
 * all profiles, in one pill (T-0604).
 *
 * A tap on a pinned dot selects that profile; a long press
 * opens the full profiles screen, as the fourth button does. The active dot is
 * filled with its profile colour, so the map shows the active profile without
 * opening a screen. The bar only reports labels and calls back; the host owns
 * the controller that publishes the five stored categories.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun ProfileSwitchBar(
    active: ProfileId,
    onSelect: (ProfileId) -> Unit,
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
            activeDescription = stringResource(R.string.profiles_switch_active),
            switchDescription = stringResource(R.string.profiles_switch_to),
        )
    val items = profileSwitchItems(active, labels, palette)
    Row(
        modifier =
            modifier
                .height(BAR_HEIGHT_DP.dp)
                .background(palette.bar, RoundedCornerShape(BAR_CORNER_DP.dp))
                .padding(horizontal = BAR_INSET_DP.dp, vertical = DOT_INSET_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DOT_GAP_DP.dp),
    ) {
        items.forEach { item ->
            Box(
                modifier =
                    Modifier
                        .sizeIn(minWidth = TOUCH_TARGET_DP.dp, minHeight = TOUCH_TARGET_DP.dp)
                        .combinedClickable(
                            role = Role.Button,
                            onClick = { onSelect(item.id) },
                            onLongClick = onOpenAll,
                        ).semantics {
                            contentDescription = item.description
                            selected = item.selected
                        },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier.size(DOT_SIZE_DP.dp).background(item.fill, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    ProfilePictogram(id = item.id, content = item.content)
                }
            }
        }
        Box(
            modifier =
                Modifier
                    .size(DIVIDER_WIDTH_DP.dp, DIVIDER_HEIGHT_DP.dp)
                    .background(palette.divider)
                    .clearAndSetSemantics {},
        )
        val allSelected = items.none { it.selected }
        Box(
            modifier =
                Modifier
                    .sizeIn(minWidth = TOUCH_TARGET_DP.dp, minHeight = TOUCH_TARGET_DP.dp)
                    .clickable(role = Role.Button, onClick = onOpenAll)
                    .semantics { contentDescription = labels.all },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(DOT_SIZE_DP.dp)
                        .background(if (allSelected) palette.ink else palette.quiet, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                ProfilePictogram(
                    id = null,
                    content = if (allSelected) palette.onAccent else palette.ink,
                )
            }
        }
    }
}

/**
 * The round 9 vector pictogram of a pinned control. [id] is null for the fourth
 * button, which opens all profiles. The dot's semantics carry the accessibility
 * label, so the drawing itself has none. All four pictograms are sourced from
 * round9/k-browse.html's bottom bar; no letter fallback is needed.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun ProfilePictogram(
    id: ProfileId?,
    content: Color,
) {
    val painter =
        when (id) {
            BuiltInProfiles.WALK -> painterResource(R.drawable.ic_profile_walk)
            BuiltInProfiles.BIKE -> painterResource(R.drawable.ic_profile_bike)
            BuiltInProfiles.HIKE -> painterResource(R.drawable.ic_profile_hike)
            else -> painterResource(R.drawable.ic_profiles_grid)
        }
    Image(
        painter = painter,
        contentDescription = null,
        colorFilter = ColorFilter.tint(content),
        modifier = Modifier.size(PICTOGRAM_DP.dp),
    )
}

// The canon bottom bar is 58 dp high and fully round (radius 29): round 5's
// BAR_H = 58 and border-radius BAR_H / 2 in r3.py. The drawn faces are 42 dp.
// Each face sits in a 48 dp clickable box, so the touch target keeps the shell
// minimum without growing the bar away from the canon. The host bounds this
// cluster to the width left by Search and the chevron.
private const val BAR_HEIGHT_DP = 58
private const val BAR_CORNER_DP = 29
private const val BAR_INSET_DP = 2
private const val DOT_INSET_DP = 8
private const val TOUCH_TARGET_DP = 48
private const val DOT_SIZE_DP = 42
private const val DOT_GAP_DP = 1
private const val DIVIDER_WIDTH_DP = 1
private const val DIVIDER_HEIGHT_DP = 28
private const val PICTOGRAM_DP = 21
