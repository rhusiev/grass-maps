package nl.r1a.grassmaps.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Only a debug build may carry the embedded region token. The main source set
 * never names the field, and the production application hook is empty.
 */
class DebugTokenWiringTest {
    @Test
    fun theTokenFieldExistsOnlyInADebugBuildConfig() {
        val hasField = buildConfigFieldNames().contains(TOKEN_FIELD)
        assertEquals(BuildConfig.DEBUG, hasField)
    }

    @Test
    fun theMainSourceSetNeverNamesTheTokenField() {
        val offenders =
            File(MAIN_SOURCES)
                .walkTopDown()
                .filter { it.isFile && it.extension in SOURCE_EXTENSIONS }
                .filter { TOKEN_FIELD in it.readText() }
                .toList()
        assertTrue("main sources name $TOKEN_FIELD: $offenders", offenders.isEmpty())
    }

    @Test
    fun theProductionApplicationEmbedsNoToken() {
        assertEquals("", GrassMapsApplication().defaultRegionToken())
    }

    @Test
    fun theTokenFileIsNeverUnderTheRepository() {
        assertFalse(File(MAIN_SOURCES).walkTopDown().any { it.name.endsWith(".token") })
    }

    private fun buildConfigFieldNames(): List<String> = BuildConfig::class.java.declaredFields.map { it.name }

    private companion object {
        const val TOKEN_FIELD = "GRASSMAPS_DEBUG_TOKEN"
        const val MAIN_SOURCES = "src/main"
        val SOURCE_EXTENSIONS = setOf("kt", "java", "xml", "kts")
    }
}
