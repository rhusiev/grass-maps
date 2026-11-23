package nl.r1a.grassmaps.ui.shell

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The collapsed navigation bar's expand arrow (blind review section 5) must
 * have a 48 dp tap target. `RoundButton` is a private Composable and the JVM
 * tests have no Compose runtime, so its Modifier cannot be inspected here.
 * Pin the named constant and its use at the Expand call in the source instead.
 */
class NavigationExpandTargetTest {
    @Test
    fun theExpandArrowSetsIts48DpTapTargetFromTheNamedConstant() {
        val source = File(HOST_FILE).readText()

        assertTrue(
            "the named 48 dp expand constant is missing",
            source.contains("private const val EXPAND_BUTTON_DP = 48"),
        )
        val expandButton =
            source
                .substringAfter("NavigationScreenStrings.EXPAND)")
                .substringBefore("\n        }")
        assertNotNull("the Expand arrow is not drawn", expandButton.takeIf { it.isNotBlank() })
        assertTrue(
            "the Expand arrow does not use the 48 dp constant",
            expandButton.contains("size = EXPAND_BUTTON_DP"),
        )
    }

    private companion object {
        const val HOST_FILE = "src/main/java/nl/r1a/grassmaps/ui/shell/NavigationScreenHost.kt"
    }
}
