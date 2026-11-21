package nl.r1a.grassmaps.plugins.favourites.layer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.r1a.grassmaps.plugin.ui.api.LocalShellColors
import nl.r1a.grassmaps.plugin.ui.api.ShellColors

private const val CORNER_DP = 8
private const val HORIZONTAL_DP = 12
private const val VERTICAL_DP = 8
private const val GAP_DP = 6
private const val FONT_SP = 13

/** The four shape choices of the control; null keeps the shape in each saved place. */
fun favouriteShapeChoices(): List<MarkerShape?> = listOf(null, MarkerShape.Circle, MarkerShape.Square, MarkerShape.Octagon)

/** A group whose name is not in [settings] contributes its features to the map. */
fun isGroupShown(
    settings: FavouriteLayerSettings,
    group: String,
): Boolean = group !in settings.hiddenGroups

/**
 * The saved-places card of the layers pane (T-0215).
 *
 * The Layers button and the style switch stay in `plugins/layers-panel`, and
 * the other layer cards are their own rows in the same column. This card is
 * one more row: it has no new chrome and no redesigned card, just four
 * controls over the layer state the host owns and persists.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun FavouritesLayerPanel(
    settings: FavouriteLayerSettings,
    groups: List<String>,
    pluginEnabled: Boolean,
    onIconsPerPointChange: (Boolean) -> Unit,
    onMarkerShapeChange: (MarkerShape?) -> Unit,
    onShowNamesChange: (Boolean) -> Unit,
    onGroupShownChange: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!pluginEnabled) return
    val colors = LocalShellColors.current
    val shape = RoundedCornerShape(CORNER_DP.dp)
    Column(
        modifier =
            modifier
                .clip(shape)
                .background(colors.surface)
                .border(1.dp, colors.ring, shape)
                .padding(horizontal = HORIZONTAL_DP.dp, vertical = VERTICAL_DP.dp),
        verticalArrangement = Arrangement.spacedBy(GAP_DP.dp),
    ) {
        PanelText(stringResource(R.string.favourites_layer_title), colors.muted)
        SwitchRow(
            label = stringResource(R.string.favourites_layer_icons),
            value = settings.iconsPerPoint,
            colors = colors,
            onChange = onIconsPerPointChange,
        )
        PanelText(stringResource(R.string.favourites_layer_shape_title), colors.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp)) {
            for (choice in favouriteShapeChoices()) {
                PanelText(
                    text = shapeLabel(choice),
                    color = if (choice == settings.markerShape) colors.accent else colors.muted,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(CORNER_DP.dp))
                            .selectable(selected = choice == settings.markerShape, role = Role.RadioButton) {
                                onMarkerShapeChange(choice)
                            }
                            .padding(vertical = GAP_DP.dp),
                )
            }
        }
        SwitchRow(
            label = stringResource(R.string.favourites_layer_names),
            value = settings.showNames,
            colors = colors,
            onChange = onShowNamesChange,
        )
        if (groups.isEmpty()) {
            PanelText(stringResource(R.string.favourites_layer_no_groups), colors.muted)
        } else {
            for (group in groups) {
                SwitchRow(
                    label =
                        stringResource(
                            if (isGroupShown(settings, group)) R.string.favourites_layer_group_hide else R.string.favourites_layer_group_show,
                            group,
                        ),
                    value = isGroupShown(settings, group),
                    colors = colors,
                    onChange = { shown -> onGroupShownChange(group, shown) },
                )
            }
        }
    }
}

@Composable
private fun shapeLabel(choice: MarkerShape?): String =
    when (choice) {
        null -> stringResource(R.string.favourites_layer_shape_stored)
        MarkerShape.Circle -> stringResource(R.string.favourites_layer_shape_circle)
        MarkerShape.Square -> stringResource(R.string.favourites_layer_shape_square)
        MarkerShape.Octagon -> stringResource(R.string.favourites_layer_shape_octagon)
    }

@Suppress("ktlint:standard:function-naming")
@Composable
private fun SwitchRow(
    label: String,
    value: Boolean,
    colors: ShellColors,
    onChange: (Boolean) -> Unit,
) {
    val state = stringResource(if (value) R.string.favourites_layer_on else R.string.favourites_layer_off)
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(CORNER_DP.dp))
                .toggleable(value = value, role = Role.Switch, onValueChange = onChange)
                .padding(vertical = GAP_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp),
    ) {
        PanelText(label, colors.ink)
        PanelText(state, colors.muted)
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun PanelText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    BasicText(text = text, style = TextStyle(color = color, fontSize = FONT_SP.sp), modifier = modifier)
}
