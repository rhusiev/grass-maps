package nl.r1a.grassmap.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
