package nl.r1a.grassmap.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import nl.r1a.grassmap.plugin.ui.api.LocalShellColors
import nl.r1a.grassmap.plugin.ui.api.shellColors

/**
 * Provides the round 9 colour tokens of [dark] to every screen below it
 * (T-0612). The app wraps the map screen once; a plugin panel and a sheet read
 * [LocalShellColors] and never keep a palette of their own.
 */
@Composable
fun GrassMapsTheme(
    dark: Boolean,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalShellColors provides shellColors(dark), content = content)
}
