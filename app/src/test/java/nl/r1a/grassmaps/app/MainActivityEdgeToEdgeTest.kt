package nl.r1a.grassmaps.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-0612 final-review defect P1-2: the expanded search card asks for
 * `imePadding`, but the activity keeps the decor fitted to system windows, so
 * no IME inset reaches the card and the query sits under the keyboard.
 * A JVM test cannot render the window, so it pins the activity's inset call.
 */
class MainActivityEdgeToEdgeTest {
    @Test
    fun theActivityDeliversWindowInsetsBeforeComposingTheContent() {
        val source = File(ACTIVITY_FILE).readText()
        val insetCall = "WindowCompat.setDecorFitsSystemWindows(window, false)"

        assertTrue(
            "the activity never opts out of fitting the decor to system windows",
            source.contains(insetCall),
        )
        assertTrue(
            "the inset call must run before setContent, or the first composition misses it",
            source.indexOf(insetCall) < source.indexOf("setContent {"),
        )
    }

    private companion object {
        const val ACTIVITY_FILE = "src/main/java/nl/r1a/grassmaps/app/MainActivity.kt"
    }
}
