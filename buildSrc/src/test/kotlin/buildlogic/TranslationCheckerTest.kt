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
    fun completePluralCatalogsPass() {
        val plural = plural("track_count", "one" to "One track", "other" to "Many tracks")
        val module = module(resources(plural), resources(plural))

        val report = TranslationChecker.check(module.files())

        assertTrue(report.errors.toString(), report.isSuccess)
        assertEquals(1, report.keysChecked)
    }

    @Test
    fun missingUkrainianKeyFails() {
        val module = module(defaultStrings("app_name", "map_label"), ukrainianStrings("app_name"))

        val report = TranslationChecker.check(module.files())

        assertFalse(report.isSuccess)
        assertTrue(report.errors.any { it.contains("map_label") && it.contains("missing") })
    }

    @Test
    fun missingUkrainianPluralFails() {
        val plural = plural("track_count", "one" to "One track", "other" to "Many tracks")
        val module = module(resources(plural), ukrainianStrings("app_name"))

        val report = TranslationChecker.check(module.files())

        assertFalse(report.isSuccess)
        assertTrue(report.errors.any { it.contains("track_count") && it.contains("missing") })
    }

    @Test
    fun pluralOnlyInUkrainianFails() {
        val plural = plural("track_count", "one" to "One track", "other" to "Many tracks")
        val module = module(defaultStrings("app_name"), resources(plural))

        val report = TranslationChecker.check(module.files())

        assertFalse(report.isSuccess)
        assertTrue(report.errors.any { it.contains("track_count") && it.contains("only") })
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
    fun blankPluralItemFails() {
        val plural = plural("track_count", "one" to "One track", "other" to " ")
        val module = module(resources(plural), resources(plural))

        val report = TranslationChecker.check(module.files())

        assertFalse(report.isSuccess)
        assertTrue(report.errors.any { it.contains("track_count") && it.contains("blank") })
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
    fun britishOverrideMustDifferFromTheDefault() {
        val module =
            module(
                default = defaultStrings("track_card_colour"),
                ukrainian = ukrainianStrings("track_card_colour"),
                british = defaultStrings("track_card_colour"),
            )

        val report = TranslationChecker.check(module.files())

        assertFalse(report.isSuccess)
        assertTrue(report.errors.any { it.contains("track_card_colour") && it.contains("identical") })
    }

    @Test
    fun britishOverrideMustExistInTheDefault() {
        val module =
            module(
                default = defaultStrings("track_card_colour"),
                ukrainian = ukrainianStrings("track_card_colour"),
                british = defaultStrings("track_card_other"),
            )

        val report = TranslationChecker.check(module.files())

        assertFalse(report.isSuccess)
        assertTrue(report.errors.any { it.contains("track_card_other") && it.contains("does not exist") })
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
        british: String? = null,
    ): TestModule {
        val root = Files.createTempDirectory("translation-check").toFile()
        val directory = root.resolve("app")
        val defaultFile = directory.resolve("src/main/res/values/strings.xml")
        val ukrainianFile = directory.resolve("src/main/res/values-uk/strings.xml")
        val britishFile = directory.resolve("src/main/res/values-en-rGB/strings.xml")
        default?.let { defaultFile.write(it) }
        ukrainian?.let { ukrainianFile.write(it) }
        british?.let { britishFile.write(it) }
        return TestModule(directory, defaultFile, ukrainianFile, britishFile)
    }

    private fun File.write(content: String) {
        parentFile.mkdirs()
        writeText(content)
    }

    private fun defaultStrings(vararg names: String): String =
        resources(names.map { "<string name=\"$it\">Grass Maps</string>" })

    private fun ukrainianStrings(vararg names: String): String =
        resources(names.map { "<string name=\"$it\">Grass Maps</string>" })

    private fun plural(
        name: String,
        vararg quantities: Pair<String, String>,
    ): String {
        val items = quantities.joinToString("") { (quantity, value) -> "<item quantity=\"$quantity\">$value</item>" }
        return "<plurals name=\"$name\">$items</plurals>"
    }

    private fun resources(vararg entries: String): String =
        resources(entries.toList())

    private fun resources(entries: List<String>): String =
        "<resources>\n${entries.joinToString("\n")}\n</resources>"

    private class TestModule(
        val directory: File,
        val defaultFile: File,
        val ukrainianFile: File,
        val britishFile: File,
    ) {
        fun files(): List<File> = listOf(defaultFile, ukrainianFile, britishFile).filter { it.isFile }

        override fun toString(): String = directory.path
    }
}
