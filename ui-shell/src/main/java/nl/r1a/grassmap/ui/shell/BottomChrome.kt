package nl.r1a.grassmap.ui.shell

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt

/**
 * The bars and sheets that cover the bottom of the map, as one number: how
 * far the tallest one reaches up from the bottom edge of the window.
 *
 * Each bottom element reports itself with [reportsBottomChrome]. The map
 * credit line reads [insetPx] and sits above them all, so no sheet or bar
 * hides it.
 */
class BottomChrome {
    private val reachByKey = mutableStateMapOf<Any, Int>()

    /** The height in pixels from the window bottom to the top of the highest element. */
    val insetPx: Int get() = reachByKey.values.maxOrNull() ?: 0

    /** The reach of the element that reported under [key], or 0 when it has not reported. */
    fun reachOf(key: Any): Int = reachByKey[key] ?: 0

    internal fun report(
        key: Any,
        reachPx: Int,
    ) {
        reachByKey[key] = reachPx
    }

    internal fun forget(key: Any) {
        reachByKey.remove(key)
    }
}

/** The collector of the current screen. It is null where nothing reads it, as in a test. */
val LocalBottomChrome = staticCompositionLocalOf<BottomChrome?> { null }

/**
 * The key every bottom sheet over the map reports under, so chrome that must
 * stay clear of an open sheet can read its reach without naming one sheet.
 */
val BOTTOM_SHEET_KEY: Any = Any()

/**
 * The bottom clearance of an element that must stay above the navigation bar
 * and any open bottom sheet: the taller of the two reaches in pixels.
 */
internal fun bottomClearancePx(
    navigationBarPx: Int,
    sheetReachPx: Int,
): Int = maxOf(navigationBarPx, sheetReachPx)

/**
 * Lifts this bottom-aligned element above the navigation bar and above an open
 * bottom sheet. The sheet's reach already includes the navigation bar, so the
 * two are not added. An element that reports its own reach must not use this:
 * its padding would feed back into the measurement.
 */
@Composable
fun Modifier.aboveBottomSheet(chrome: BottomChrome? = LocalBottomChrome.current): Modifier {
    val density = LocalDensity.current
    val navigationBarPx = WindowInsets.navigationBars.getBottom(density)
    val clearancePx = bottomClearancePx(navigationBarPx, chrome?.reachOf(BOTTOM_SHEET_KEY) ?: 0)
    return padding(bottom = with(density) { clearancePx.toDp() })
}

/**
 * Reports this element's top edge to [chrome] while it is in the composition.
 * Put it after the modifiers that place the element, so the edge is the
 * element's own. A caller that needs to read this element's reach alone passes
 * its own [key] and asks [BottomChrome.reachOf].
 */
@Composable
fun Modifier.reportsBottomChrome(
    chrome: BottomChrome? = LocalBottomChrome.current,
    key: Any = remember { Any() },
): Modifier {
    if (chrome == null) return this
    DisposableEffect(chrome, key) { onDispose { chrome.forget(key) } }
    return onGloballyPositioned { coordinates ->
        val windowHeight = coordinates.findRootCoordinates().size.height
        chrome.report(key, windowHeight - coordinates.positionInRoot().y.roundToInt())
    }
}
