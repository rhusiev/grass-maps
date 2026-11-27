package nl.r1a.grassmaps.plugin.ui.api

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps every outline and shadow on the shared tokens (design language,
 * "Outlines and shadows"; the user's 2026-11-27 critique of six stroke colours).
 *
 * The test reads the main sources of every module that draws UI. Only
 * `ShellOutlines.kt` may call `border`, `BorderStroke` or `shadow`, and it
 * strokes only with [OutlineColors], the warning border, the accent or the
 * caller's selection. A call of a shared outline modifier must not pass a raw
 * colour either. A JVM test has no Compose runtime, so this pins the source;
 * the rendered result is checked on the emulator.
 */
class OutlineTokenGuardTest {
    private val root = File("..").canonicalFile
    private val sources: List<File> =
        UI_MODULES
            .flatMap { module -> moduleDirs(module) }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    @Test
    fun theGuardReadsEveryUiModule() {
        assertTrue("only ${sources.size} Kotlin files found under $root", sources.size >= MIN_SOURCE_FILES)
        assertTrue("ShellOutlines.kt was not found", sources.any { it.name == OUTLINES_FILE })
    }

    @Test
    fun onlyTheSharedModifiersDrawAStrokeOrAShadow() {
        val offenders =
            sources
                .filter { it.name != OUTLINES_FILE }
                .flatMap { file ->
                    codeLines(file).mapNotNull { (number, line) ->
                        if (RAW_STROKE.containsMatchIn(line)) "${relative(file)}:$number: ${line.trim()}" else null
                    }
                }
        assertTrue(
            "draw strokes and shadows with the modifiers in $OUTLINES_FILE, one per canon row:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun theSharedModifiersStrokeOnlyWithTokens() {
        val outlines = sources.single { it.name == OUTLINES_FILE }
        val strokes = calls(outlines.readText(), STROKE_CALL)
        assertTrue("no border call found in $OUTLINES_FILE", strokes.isNotEmpty())
        val offenders =
            strokes.mapNotNull { arguments ->
                val colour = topLevelArguments(arguments).getOrNull(1).orEmpty()
                if (TOKEN_COLOUR.matches(colour)) null else colour
            }
        assertTrue("a shared modifier strokes with a colour outside the tokens: $offenders", offenders.isEmpty())
    }

    @Test
    fun noCallerPassesARawColourToAnOutlineModifier() {
        val offenders =
            sources.flatMap { file ->
                calls(file.readText(), MODIFIER_CALL)
                    .filter { arguments -> RAW_COLOUR.containsMatchIn(arguments) }
                    .map { arguments -> "${relative(file)}: ($arguments)" }
            }
        assertTrue(
            "pass a ShellColors token, not a raw colour, to the outline modifiers:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    private fun moduleDirs(module: String): List<File> {
        if (!module.endsWith("/*")) {
            return listOf(File(root, "$module/src/main"))
        }
        val parent = File(root, module.removeSuffix("/*"))
        return parent
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .map { File(it, "src/main") }
    }

    private fun relative(file: File): String = file.relativeTo(root).path

    private fun codeLines(file: File): List<Pair<Int, String>> =
        file
            .readLines()
            .mapIndexed { index, line -> index + 1 to line }
            .filterNot { (_, line) -> line.trimStart().let { it.startsWith("import ") || it.startsWith("//") || it.startsWith("*") } }

    /** The argument text of every call [start] matches, with nested parentheses balanced. */
    private fun calls(
        text: String,
        start: Regex,
    ): List<String> =
        start
            .findAll(text)
            .map { match ->
                var depth = 1
                var index = match.range.last + 1
                while (index < text.length && depth > 0) {
                    when (text[index]) {
                        '(' -> depth += 1
                        ')' -> depth -= 1
                    }
                    index += 1
                }
                text.substring(match.range.last + 1, index - 1)
            }.toList()

    private fun topLevelArguments(arguments: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        val current = StringBuilder()
        for (char in arguments) {
            when {
                char == ',' && depth == 0 -> {
                    parts += current.toString().trim()
                    current.clear()
                }

                else -> {
                    if (char == '(') depth += 1
                    if (char == ')') depth -= 1
                    current.append(char)
                }
            }
        }
        if (current.isNotBlank()) parts += current.toString().trim()
        return parts
    }

    private companion object {
        const val OUTLINES_FILE = "ShellOutlines.kt"
        const val MIN_SOURCE_FILES = 200
        val UI_MODULES = listOf("plugin-ui-api", "ui-shell", "app", "map", "plugins/*")
        val RAW_STROKE = Regex("""(?<![\w])(border|shadow|BorderStroke|dashedRing)\(""")
        val STROKE_CALL = Regex("""(?<![\w])border\(""")
        val MODIFIER_CALL =
            Regex(
                """\.(floatingSurface|sheetSurface|selectedSegment|chipOutline|fieldOutline|choiceOutline|""" +
                    """switchOutline|alertOutline|dashedOutline)\(""",
            )
        val TOKEN_COLOUR =
            Regex(
                """colors\.outline\.\w+|colors\.warning\.border|selection|selection \?: colors\.outline\.\w+|""" +
                    """if \(on\) colors\.accent else colors\.outline\.\w+""",
            )
        val RAW_COLOUR = Regex("""Color\(|Color\.\w|0x[0-9A-Fa-f]{6,8}|\.copy\(""")
    }
}
