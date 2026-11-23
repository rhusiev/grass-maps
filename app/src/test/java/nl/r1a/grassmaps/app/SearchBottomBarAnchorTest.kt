package nl.r1a.grassmaps.app

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-0612: the collapsed search field belongs in the bottom bar, left of the
 * profile and control buttons, with a 48 dp tap target. [SearchOverlay] is a
 * private Composable and the JVM tests have no Compose runtime, so its
 * Modifier cannot be rendered here. Pin the named anchor, the minimum-height
 * constant and their use in the collapsed card modifier from source instead.
 * That is weaker than a rendered assertion.
 */
class SearchBottomBarAnchorTest {
    @Test
    fun collapsedFieldIsBottomBarAnchoredWith48DpTarget() {
        val source = File(HOST_FILE).readText()

        assertTrue(
            "the bottom-bar anchor constant is missing",
            source.contains("private val SEARCH_COLLAPSED_ANCHOR: Alignment = Alignment.BottomStart"),
        )
        assertTrue(
            "the 48 dp tap-target constant is missing",
            source.contains("private const val SEARCH_COLLAPSED_MIN_HEIGHT_DP = 48"),
        )
        val cardModifier =
            source
                .substringAfter("cardModifier =")
                .substringBefore("onlineSearchEnabled =")
        assertNotNull("the search card modifier is missing", cardModifier.takeIf { it.isNotBlank() })
        assertTrue(
            "the collapsed field does not use the bottom-bar anchor",
            cardModifier.contains("collapsedAnchor = SEARCH_COLLAPSED_ANCHOR"),
        )
        assertTrue(
            "the collapsed field lost its navigation-bar clearance",
            cardModifier.contains("navigationBarsPadding()"),
        )
        assertTrue(
            "the collapsed field has no 48 dp tap target",
            cardModifier.contains("heightIn(min = SEARCH_COLLAPSED_MIN_HEIGHT_DP.dp)"),
        )
    }

    private companion object {
        const val HOST_FILE = "src/main/java/nl/r1a/grassmaps/app/MapScreen.kt"
    }
}
