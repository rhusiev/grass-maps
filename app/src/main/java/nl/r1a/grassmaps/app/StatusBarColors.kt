package nl.r1a.grassmaps.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import nl.r1a.grassmaps.plugin.ui.api.NavigationScreenStatus
import nl.r1a.grassmaps.plugin.ui.api.ShellColors

/** The two window values one status-bar owner writes for the current screen. */
internal data class StatusBarSpec(
    val argb: Int,
    val lightIcons: Boolean,
)

/** The card colour a navigation status bar takes while the navigation screen is open. */
internal fun navigationStatusBarColor(status: NavigationScreenStatus): Color =
    when (status) {
        NavigationScreenStatus.Following,
        NavigationScreenStatus.Arrived,
        -> Color(0xFF1E7F53)

        NavigationScreenStatus.OffRoute -> Color(0xFF8A5A00)

        NavigationScreenStatus.Paused -> Color(0xFF5F6368)
    }

/**
 * The one decision the status bar needs: the navigation card colour while a
 * navigation screen is shown, otherwise the map token of the current theme.
 * Light icons follow the theme off navigation and are cleared on the coloured
 * bar, which is dark in every theme.
 */
internal fun statusBarSpec(
    status: NavigationScreenStatus?,
    colors: ShellColors,
): StatusBarSpec =
    if (status == null) {
        StatusBarSpec(argb = colors.map.toArgb(), lightIcons = !colors.dark)
    } else {
        StatusBarSpec(argb = navigationStatusBarColor(status).toArgb(), lightIcons = false)
    }
