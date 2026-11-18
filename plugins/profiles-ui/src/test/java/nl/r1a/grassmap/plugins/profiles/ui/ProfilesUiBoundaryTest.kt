package nl.r1a.grassmap.plugins.profiles.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The profiles plugin reaches the profile files only through the host access
 * (T-0602). Its runtime dependencies are `:plugin-api`, `:plugin-ui-api` and
 * Compose.
 */
class ProfilesUiBoundaryTest {
    @Test
    fun theOnlyRuntimeProjectDependenciesAreThePluginApis() {
        val build = File(BUILD_FILE).readText()
        val runtime =
            Regex("""^\s*(?:api|implementation)\(project\("([^"]+)"\)\)""", RegexOption.MULTILINE)
                .findAll(build)
                .map { it.groupValues[1] }
                .toSet()

        assertEquals(setOf(":plugin-api", ":plugin-ui-api"), runtime)
    }

    @Test
    fun noSourceReachesAStoreAFileOrAPlatformApi() {
        val sources = File(SOURCE_ROOT).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

        assertTrue("no plugin sources found under $SOURCE_ROOT", sources.isNotEmpty())
        for (source in sources) {
            val text = source.readText()
            for (forbidden in FORBIDDEN) {
                assertTrue("${source.name} reaches outside the host access: $forbidden", forbidden !in text)
            }
        }
    }

    private companion object {
        const val BUILD_FILE = "build.gradle.kts"
        const val SOURCE_ROOT = "src/main/java/nl/r1a/grassmap/plugins/profiles/ui"

        val FORBIDDEN =
            listOf(
                "nl.r1a.grassmap.core",
                "ProfileCatalog",
                "ProfileValueStore",
                "java.io.",
                "java.nio.",
                "android.content.Context",
            )
    }
}
