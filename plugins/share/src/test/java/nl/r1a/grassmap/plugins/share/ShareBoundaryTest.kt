package nl.r1a.grassmap.plugins.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The share plugin keeps the clipboard, the chooser and the intents behind the
 * host seam (T-0210). It may depend on `:plugin-api` only, and its sources name
 * no Android platform type.
 */
class ShareBoundaryTest {
    @Test
    fun theOnlyProjectDependencyIsThePluginApi() {
        val build = File(BUILD_FILE).readText()
        val projectDependencies =
            Regex("""project\("([^"]+)"\)""").findAll(build).map { it.groupValues[1] }.toSet()

        assertEquals(setOf(":plugin-api"), projectDependencies)
    }

    @Test
    fun noSourceNamesAFileOrAnAndroidPlatformType() {
        val sources = File(SOURCE_ROOT).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

        assertTrue("no plugin sources found under $SOURCE_ROOT", sources.isNotEmpty())
        for (source in sources) {
            val text = source.readText()
            for (forbidden in FORBIDDEN) {
                assertTrue("${source.name} reaches outside the host seam: $forbidden", forbidden !in text)
            }
        }
    }

    private companion object {
        const val BUILD_FILE = "build.gradle.kts"
        const val SOURCE_ROOT = "src/main/java/nl/r1a/grassmap/plugins/share"

        val FORBIDDEN =
            listOf(
                "import android.",
                "import java.io.",
                "import java.nio.",
                "import nl.r1a.grassmap.app",
            )
    }
}
