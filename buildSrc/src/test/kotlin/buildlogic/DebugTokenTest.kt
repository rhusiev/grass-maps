package buildlogic

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DebugTokenTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun aMissingFileGivesAnEmptyToken() {
        assertEquals("", DebugToken.read(File(folder.root, "absent.token")))
    }

    @Test
    fun aBlankFileGivesAnEmptyToken() {
        val file = folder.newFile("blank.token").apply { writeText("  \n\n") }
        assertEquals("", DebugToken.read(file))
    }

    @Test
    fun theFirstNonBlankLineIsTrimmed() {
        val file = folder.newFile("token").apply { writeText("\n  fixture-token-value \nsecond\n") }
        assertEquals("fixture-token-value", DebugToken.read(file))
    }

    @Test
    fun aDirectoryGivesAnEmptyToken() {
        assertEquals("", DebugToken.read(folder.newFolder("dir")))
    }

    @Test
    fun theDefaultFileSitsOutsideTheRepository() {
        assertEquals(
            File("/home/x/.local/share/grassmaps/debug-builds.token"),
            DebugToken.defaultFile("/home/x"),
        )
    }

    @Test
    fun aLiteralEscapesQuotesAndBackslashes() {
        assertEquals("\"a\\\"b\\\\c\"", DebugToken.javaLiteral("a\"b\\c"))
    }
}
