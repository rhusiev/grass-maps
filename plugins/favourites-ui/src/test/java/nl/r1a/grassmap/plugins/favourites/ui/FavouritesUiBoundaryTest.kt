package nl.r1a.grassmap.plugins.favourites.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The lists plugin keeps the store, the GPX and the filesystem behind the host
 * access (T-0213). It may depend on `:plugin-api` only, and its sources name no
 * reader, file or platform API.
 */
class FavouritesUiBoundaryTest {
    @Test
    fun theOnlyProjectDependencyIsThePluginApi() {
        val build = File(BUILD_FILE).readText()
        val projectDependencies =
            Regex("""project\("([^"]+)"\)""").findAll(build).map { it.groupValues[1] }.toSet()

        assertEquals(setOf(":plugin-api"), projectDependencies)
    }

    @Test
    fun noSourceReadsAFileAParseOrThePlacesModule() {
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
        const val SOURCE_ROOT = "src/main/java/nl/r1a/grassmap/plugins/favourites/ui"

        val FORBIDDEN =
            listOf(
                "nl.r1a.grassmap.places",
                "FavouritesStore",
                "FavouritesGpx",
                "java.io.",
                "java.nio.",
                "android.content.Context",
            )
    }
}
