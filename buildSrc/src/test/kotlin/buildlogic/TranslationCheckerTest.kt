package buildlogic

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationCheckerTest {
    @Test
    fun completeCatalogsPass() {
        val module = module(defaultStrings("app_name", "map_label"), ukrainianStrings("app_name", "map_label"))

        val report = TranslationChecker.check(module.files())

        assertTrue(report.errors.toString(), report.isSuccess)
        assertEquals(1, report.modulesChecked)
        assertEquals(2, report.keysChecked)
    }

    @Test
    fun missingUkrainianKeyFails() {
        val module = module(defaultStrings("app_name", "map_label"), ukrainianStrings("app_name"))

        val report = TranslationChecker.check(module.files())

        assertFalse(report.isSuccess)
        assertTrue(report.errors.any { it.contains("map_label") && it.contains("missing") })
    }

    @Test
    fun keyOnlyInUkrainianFails() {
        val module = module(defaultStrings("app_name"), ukrainianStrings("app_name", "map_label"))

        val report = TranslationChecker.check(module.files())

        assertFalse(report.isSuccess)
        assertTrue(report.errors.any { it.contains("map_label") && it.contains("only") })
    }

    @Test
    fun blankUkrainianValueFails() {
        val module = module(defaultStrings("app_name"), ukrainianStrings("app_name").replace("Grass Maps", "  "))

        val report = TranslationChecker.check(module.files())

        assertFalse(report.isSuccess)
        assertTrue(report.errors.any { it.contains("app_name") && it.contains("blank") })
    }

    @Test
    fun missingUkrainianFileFails() {
        val module = module(defaultStrings("app_name"), ukrainian = null)

        val report = TranslationChecker.check(module.files())

        assertFalse(report.isSuccess)
        assertTrue(report.errors.any { it.contains("values-uk/strings.xml") && it.contains("not found") })
    }

    @Test
    fun duplicateKeyFails() {
        val duplicated =
            """
            <resources>
                <string name="app_name">Grass Maps</string>
                <string name="app_name">Grass Maps again</string>
            </resources>
            """.trimIndent()
        val module = module(duplicated, ukrainianStrings("app_name"))

        val report = TranslationChecker.check(module.files())

        assertFalse(report.isSuccess)
        assertTrue(report.errors.any { it.contains("duplicate") && it.contains("app_name") })
    }

    @Test
    fun noResourcesFail() {
        val report = TranslationChecker.check(emptyList())

        assertFalse(report.isSuccess)
        assertEquals(0, report.modulesChecked)
    }

    private fun module(
        default: String?,
        ukrainian: String?,
    ): TestModule {
        val root = Files.createTempDirectory("translation-check").toFile()
        val directory = root.resolve("app")
        val defaultFile = directory.resolve("src/main/res/values/strings.xml")
        val ukrainianFile = directory.resolve("src/main/res/values-uk/strings.xml")
        default?.let { defaultFile.write(it) }
        ukrainian?.let { ukrainianFile.write(it) }
        return TestModule(directory, defaultFile, ukrainianFile)
    }

    private fun File.write(content: String) {
        parentFile.mkdirs()
        writeText(content)
    }

    private fun defaultStrings(vararg names: String): String =
        resources(names.map { "<string name=\"$it\">Grass Maps</string>" })

    private fun ukrainianStrings(vararg names: String): String =
        resources(names.map { "<string name=\"$it\">Grass Maps</string>" })

    private fun resources(entries: List<String>): String =
        "<resources>\n${entries.joinToString("\n")}\n</resources>"

    private class TestModule(
        val directory: File,
        val defaultFile: File,
        val ukrainianFile: File,
    ) {
        fun files(): List<File> = listOf(defaultFile, ukrainianFile).filter { it.isFile }

        override fun toString(): String = directory.path
    }
}
