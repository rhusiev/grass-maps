package buildlogic

/**
 * A module that declares a `@Composable` function or a Compose `Modifier`
 * extension must apply the Compose compiler plugin. Without it the compiler
 * emits a plain JVM method with a signature callers compiled with the plugin
 * cannot find, and the app fails at runtime with `NoSuchMethodError`.
 *
 * The rule lives here so a deliberately bad module can be tested without a
 * second Gradle build. The task supplies the module build files and sources.
 */
object ComposePluginRules {
    const val PLUGIN_ID = "org.jetbrains.kotlin.plugin.compose"
    const val PLUGIN_ALIAS = "kotlin.compose"

    private val COMPOSABLE = Regex("@(?:androidx\\.compose\\.runtime\\.)?Composable\\b")
    private val MODIFIER_EXTENSION = Regex("\\bfun\\s+(?:androidx\\.compose\\.ui\\.)?Modifier\\s*\\.")
    private val PLUGIN_ALIAS_APPLICATION = Regex("alias\\(\\s*libs\\.plugins\\.$PLUGIN_ALIAS\\s*\\)")
    private val PLUGIN_ID_APPLICATION = Regex("id\\(\\s*\"${Regex.escape(PLUGIN_ID)}\"\\s*\\)")

    data class Declaration(
        val path: String,
        val line: Int,
        val kind: String,
    ) {
        override fun toString(): String = "$path:$line: $kind"
    }

    data class Module(
        val name: String,
        val buildFile: String,
        val appliesPlugin: Boolean,
        val declarations: List<Declaration>,
    )

    data class Violation(
        val module: String,
        val buildFile: String,
        val declaration: Declaration,
    ) {
        override fun toString(): String =
            "$module ($buildFile): $declaration needs $PLUGIN_ID"
    }

    /** Compose declarations in one source file. Line numbers start at one. */
    fun declarations(
        path: String,
        source: String,
    ): List<Declaration> =
        stripComments(source).lines().flatMapIndexed { index, code ->
            buildList {
                if (COMPOSABLE.containsMatchIn(code)) {
                    add(Declaration(path, index + 1, "Composable declaration"))
                }
                if (MODIFIER_EXTENSION.containsMatchIn(code)) {
                    add(Declaration(path, index + 1, "Modifier extension"))
                }
            }
        }

    /** True when a build file applies the plugin by alias or by id. */
    fun appliesPlugin(buildScript: String): Boolean {
        val code = stripComments(buildScript)
        return PLUGIN_ALIAS_APPLICATION.containsMatchIn(code) ||
            PLUGIN_ID_APPLICATION.containsMatchIn(code)
    }

    /** One violation per declaration in a module that does not apply the plugin. */
    fun violations(modules: List<Module>): List<Violation> =
        modules
            .filterNot { it.appliesPlugin }
            .flatMap { module ->
                module.declarations.map { declaration ->
                    Violation(module.name, module.buildFile, declaration)
                }
            }
            .sortedBy { it.toString() }

    /** Removes comments while keeping one output line per input line. */
    private fun stripComments(text: String): String =
        buildString {
            var inBlock = false
            text.lineSequence().forEach { line ->
                val code = StringBuilder()
                var index = 0
                while (index < line.length) {
                    if (inBlock) {
                        if (line.startsWith("*/", index)) {
                            inBlock = false
                            index += 2
                        } else {
                            code.append(' ')
                            index++
                        }
                        continue
                    }
                    if (line.startsWith("//", index)) break
                    if (line.startsWith("/*", index)) {
                        inBlock = true
                        code.append("  ")
                        index += 2
                        continue
                    }
                    code.append(line[index])
                    index++
                }
                appendLine(code)
            }
        }
}
