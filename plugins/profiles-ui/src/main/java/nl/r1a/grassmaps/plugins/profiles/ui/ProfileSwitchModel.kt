package nl.r1a.grassmaps.plugins.profiles.ui

import androidx.compose.ui.graphics.Color
import nl.r1a.grassmaps.plugin.api.model.BuiltInProfiles
import nl.r1a.grassmaps.plugin.api.model.ProfileId
import nl.r1a.grassmaps.plugin.ui.api.ProfileColors
import nl.r1a.grassmaps.plugin.ui.api.ShellColors
import nl.r1a.grassmaps.plugin.ui.api.contrastRatio
import nl.r1a.grassmaps.plugin.ui.api.inkOn

/** The four labels the bar reads from resources, so its rendering has no locale logic. */
internal data class ProfileSwitchLabels(
    val walk: String,
    val bike: String,
    val hike: String,
    val all: String,
)

/** One drawn pinned control. [role] is the string name of the Compose role the bar sets. */
internal data class ProfileSwitchItem(
    val id: ProfileId,
    val label: String,
    val role: String,
    val selected: Boolean,
    val fill: Color,
    val content: Color,
)

/** The round 9 tokens the bar uses, resolved once so both themes can be tested. */
internal data class ProfileSwitchPalette(
    val bar: Color,
    val border: Color,
    val ink: Color,
    val muted: Color,
    val quiet: Color,
    val onAccent: Color,
    val profiles: ProfileColors,
    val colors: ShellColors,
)

/** The semantic role of every control in the bar; the composable sets Role.Button. */
internal const val SWITCH_ROLE: String = "Button"

/** The three pinned profiles of the round 8 bar, in display order. */
internal val PINNED_SWITCH_PROFILES: List<ProfileId> = BuiltInProfiles.ALL

/**
 * The next profile in [order], wrapping at the end. An active id outside the
 * order starts at its first profile. An empty order leaves the current one.
 */
fun nextProfile(
    order: List<ProfileId>,
    current: ProfileId,
): ProfileId {
    if (order.isEmpty()) return current
    val index = order.indexOf(current)
    return order[(index + 1) % order.size]
}

internal fun profileSwitchPalette(colors: ShellColors): ProfileSwitchPalette =
    ProfileSwitchPalette(
        bar = colors.glass,
        border = colors.ring,
        ink = colors.ink,
        muted = colors.muted,
        quiet = colors.soft,
        onAccent = colors.onAccent,
        profiles = colors.profiles,
        colors = colors,
    )

internal fun profileSwitchItems(
    active: ProfileId,
    labels: ProfileSwitchLabels,
    palette: ProfileSwitchPalette,
): List<ProfileSwitchItem> {
    val names = mapOf(BuiltInProfiles.WALK to labels.walk, BuiltInProfiles.BIKE to labels.bike, BuiltInProfiles.HIKE to labels.hike)
    return PINNED_SWITCH_PROFILES.map { id ->
        val selected = id == active
        val color = palette.profiles.colorOf(id)
        ProfileSwitchItem(
            id = id,
            label = names.getValue(id),
            role = SWITCH_ROLE,
            selected = selected,
            fill = if (selected) color else palette.quiet,
            content = if (selected) inkOn(color, palette.colors) else quietIconOn(color, palette.quiet, palette.colors),
        )
    }
}

/** Keeps a quiet dot's glyph readable where a profile colour is too light on soft. */
internal fun quietIconOn(
    color: Color,
    quiet: Color,
    colors: ShellColors,
): Color = if (contrastRatio(color, quiet) >= UI_ICON_CONTRAST) color else colors.ink

internal const val UI_ICON_CONTRAST: Double = 3.0

private fun ProfileColors.colorOf(id: ProfileId): Color =
    when (id) {
        BuiltInProfiles.WALK -> walk
        BuiltInProfiles.BIKE -> bike
        BuiltInProfiles.HIKE -> hike
        else -> walk
    }
