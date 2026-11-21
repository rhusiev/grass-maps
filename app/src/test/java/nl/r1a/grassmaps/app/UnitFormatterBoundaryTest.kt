package nl.r1a.grassmaps.app

import nl.r1a.grassmaps.plugin.api.unit.UnitStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The one display formatter (T-0624).
 *
 * [UnitText] lives in `plugin-api`, so every plugin and the app can reach it.
 * The first test pins the surfaces that used to format their own units to that
 * one path. The second stops a new private conversion from growing in a plugin:
 * a second formatter is how the guidance panel, the notification, the widget
 * grid and the point-info label disagreed in the first place. The allowlisted
 * files are not display formatters: the simulator's own speed model and the
 * Valhalla response's kilometres-to-metres are request units, not user units.
 */
class UnitFormatterBoundaryTest {
    @Test
    fun everyMetricSurfaceFormatsThroughTheBoundaryFormatter() {
        for (path in SURFACES) {
            val source = File(path).readText()
            assertTrue("$path does not call UnitText", "UnitText." in source)
        }
    }

    @Test
    fun noPluginKeepsAPrivateDisplayConversion() {
        val offenders =
            File(PLUGINS)
                .walkTopDown()
                .filter { file -> file.isFile && file.extension == "kt" && "/src/main/" in file.path }
                .filter { file -> file.name !in ALLOWED_CONVERSIONS }
                .filter { file -> PRIVATE_CONVERSIONS.any { it.containsMatchIn(file.readText()) } }
                .map { file -> file.path }
                .toList()

        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun everyBoundaryKeyIsInBothBundles() {
        val english = resources("src/main/res/values/strings.xml")
        val ukrainian = resources("src/main/res/values-uk/strings.xml")
        val keys =
            UnitStrings::class.java.declaredFields
                .filter { field -> field.type == String::class.java }
                .map { field -> field.get(null) as String }

        assertTrue("the key list is empty", keys.isNotEmpty())
        for (key in keys) {
            assertTrue("$key is missing in English", key in english)
            assertTrue("$key is missing in Ukrainian", key in ukrainian)
        }
    }

    private fun resources(path: String): Set<String> =
        Regex("""<(?:string|plurals) name="([^"]+)">""")
            .findAll(File(path).readText())
            .map { it.groupValues[1] }
            .toSet()

    private companion object {
        const val PLUGINS = "../plugins"

        val SURFACES =
            listOf(
                "src/main/java/nl/r1a/grassmap/app/NavigationNotification.kt",
                "src/main/java/nl/r1a/grassmap/app/NavigationScreenMapping.kt",
                "src/main/java/nl/r1a/grassmap/app/WidgetGridMapping.kt",
                "../plugins/guidance-ui/src/main/java/nl/r1a/grassmap/plugins/guidance/ui/GuidancePanel.kt",
                "../plugins/point-info/src/main/java/nl/r1a/grassmap/plugins/point/info/PointInfoLabel.kt",
            )

        val ALLOWED_CONVERSIONS =
            setOf(
                "NavigationSimulator.kt",
                "ValhallaResponse.kt",
                "ValhallaTrace.kt",
            )

        val PRIVATE_CONVERSIONS =
            listOf(
                Regex("""(?<!UnitText\.)\bMETRES_PER_KILOMETRE\b"""),
                Regex("""(?<!UnitText\.)\bMETRES_PER_FOOT\b"""),
                Regex("""(?<!UnitText\.)\bMETRES_PER_MILE\b"""),
                Regex("""(?<!UnitText\.)\bMETERS_PER_KILOMETER\b"""),
                Regex("""\bSECONDS_PER_HOUR\b"""),
                Regex("""\b0\.3048\b"""),
                Regex("""\b1_?609\.344\b"""),
            )
    }
}
