package nl.r1a.grassmaps.ui.shell

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-0612 final-review defect P1-2: the keyboard hides the typed query because
 * the IME inset never reaches `imePadding`. The activity must deliver the
 * inset, and the expanded card must take its own bottom clearance from that
 * inset wrapper, not from the bottom-bar modifier the app passes in.
 * Both are source-checked: a JVM test has no Compose window.
 */
class SearchImeInsetDeliveryTest {
    @Test
    fun theActivityHandsWindowInsetsToCompose() {
        val activity = File(ACTIVITY_FILE).readText()

        assertTrue(
            "the activity does not deliver window insets to the Compose content",
            activity.contains("WindowCompat.setDecorFitsSystemWindows(window, false)"),
        )
    }

    @Test
    fun theExpandedCardTakesItsBottomClearanceFromTheImeWrapper() {
        val host = File(HOST_FILE).readText()
        val expanded = host.substringAfter("LaunchedEffect(Unit) { focusRequester.requestFocus() }")

        assertTrue(
            "the expanded card lost the wrapper that turns the IME inset into padding",
            expanded.contains("Box(modifier = Modifier.fillMaxSize().imePadding())"),
        )
        assertTrue(
            "the card must be anchored inside the IME-padded wrapper",
            expanded.indexOf(".imePadding()") < expanded.indexOf(".align(SEARCH_EXPANDED_ANCHOR)"),
        )
        assertTrue(
            "the query must be the last pinned child of the bottom-anchored card",
            expanded.indexOf("BasicTextField(") > expanded.indexOf(".align(SEARCH_EXPANDED_ANCHOR)"),
        )
    }

    private companion object {
        const val ACTIVITY_FILE = "../app/src/main/java/nl/r1a/grassmaps/app/MainActivity.kt"
        const val HOST_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/SearchScreenHost.kt"
    }
}
