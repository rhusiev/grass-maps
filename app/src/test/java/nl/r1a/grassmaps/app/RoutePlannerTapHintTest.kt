package nl.r1a.grassmaps.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The picking hint names a map tap (T-0612), so a normal tap must set the
 * point. A long press stays as an alternative way to pick the same point.
 */
class RoutePlannerTapHintTest {
    @Test
    fun aMapTapAndALongPressBothSetThePointWhilePicking() {
        val map = mapScreen()
        val onTap =
            map
                .substringAfter("onTap = tap@{ point ->")
                .substringBefore("\n            },")
        val onPointSelected =
            map
                .substringAfter("onPointSelected = { point ->")
                .substringBefore("\n            },")

        assertTrue(
            "a tap checks the picking role before the track and relation hit tests",
            Regex("""val pickingRole = plan\.pickingRole""").containsMatchIn(onTap),
        )
        assertTrue("a tap sends the tapped point to the planner", onTap.contains("setPlanPoint(container, pickingRole, point"))
        assertTrue("a tap returns after picking", onTap.contains("return@tap"))
        assertTrue(
            "a long press still sets the point as an alternative",
            onPointSelected.contains("setPlanPoint(container, role, point"),
        )
    }

    @Test
    fun thePickingHintNamesATapInBothLanguages() {
        val hints =
            listOf(
                "src/main/res/values/strings.xml" to "Tap",
                "src/main/res/values-uk/strings.xml" to "Торкніться",
            )
        for ((path, word) in hints) {
            val text = File(path).readText()
            for (key in listOf("route_planner_pick_start", "route_planner_pick_destination")) {
                val value =
                    Regex("""<string name="$key">([^<]*)</string>""")
                        .find(text)
                        ?.groupValues
                        ?.get(1)
                        .orEmpty()
                assertTrue("$key in $path names a tap", value.startsWith(word))
                assertFalse("$key in $path must not require a long press", value.contains("long press", ignoreCase = true))
            }
        }
    }

    private fun mapScreen(): String = File("src/main/java/nl/r1a/grassmaps/app/MapScreen.kt").readText()
}
