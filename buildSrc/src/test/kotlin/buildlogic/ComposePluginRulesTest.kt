package buildlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposePluginRulesTest {
    private fun declaration(source: String) =
        ComposePluginRules.declarations("ShellIcons.kt", source).single()

    private fun module(
        name: String,
        appliesPlugin: Boolean,
        declaration: ComposePluginRules.Declaration,
    ) = ComposePluginRules.Module(name, "$name/build.gradle.kts", appliesPlugin, listOf(declaration))

    @Test
    fun composableFunctionIsDetected() {
        val found = declaration("@Composable\nfun ShellIcon() {}")

        assertEquals("ShellIcons.kt:1: Composable declaration", found.toString())
    }

    @Test
    fun qualifiedComposableAnnotationIsDetected() {
        val found = declaration("@androidx.compose.runtime.Composable\nfun ShellIcon() {}")

        assertEquals(1, found.line)
    }

    @Test
    fun modifierExtensionIsDetected() {
        val found = declaration("fun Modifier.dashedRing() = this")

        assertEquals("ShellIcons.kt:1: Modifier extension", found.toString())
    }

    @Test
    fun qualifiedModifierExtensionIsDetected() {
        val found = declaration("fun androidx.compose.ui.Modifier.glass() = this")

        assertEquals("Modifier extension", found.kind)
    }

    @Test
    fun commentsAreNotDeclarations() {
        val source =
            """
            // @Composable
            /* fun Modifier.ignored() = this */
            fun plain() = Unit
            """.trimIndent()

        assertTrue(ComposePluginRules.declarations("Plain.kt", source).isEmpty())
    }

    @Test
    fun declarationLineNumbersArePreserved() {
        val found = ComposePluginRules.declarations("X.kt", "fun plain() = Unit\n\n@Composable\nfun icon() {}")

        assertEquals(3, found.single().line)
    }

    @Test
    fun pluginByAliasOrIdIsAccepted() {
        assertTrue(
            ComposePluginRules.appliesPlugin("plugins { alias(libs.plugins.kotlin.compose) }"),
        )
        assertTrue(
            ComposePluginRules.appliesPlugin("plugins { id(\"org.jetbrains.kotlin.plugin.compose\") }"),
        )
    }

    @Test
    fun libraryPluginAloneIsNotTheComposePlugin() {
        assertFalse(ComposePluginRules.appliesPlugin("plugins { alias(libs.plugins.android.library) }"))
    }

    @Test
    fun aDependencyNamingThePluginDoesNotApplyIt() {
        assertFalse(
            ComposePluginRules.appliesPlugin(
                "dependencies { implementation(\"org.jetbrains.kotlin.plugin.compose:compiler\") }",
            ),
        )
    }

    @Test
    fun commentedPluginDoesNotCount() {
        assertFalse(ComposePluginRules.appliesPlugin("// alias(libs.plugins.kotlin.compose)"))
        assertFalse(ComposePluginRules.appliesPlugin("/* id(\"org.jetbrains.kotlin.plugin.compose\") */"))
    }

    @Test
    fun declarationWithoutPluginFails() {
        val found =
            ComposePluginRules.violations(
                listOf(module("plugin-ui-api", false, declaration("@Composable\nfun ShellIcon() {}"))),
            )

        assertEquals(1, found.size)
        assertTrue(found.single().toString(), found.single().toString().contains("plugin-ui-api"))
        assertTrue(found.single().toString().contains("ShellIcons.kt:1"))
    }

    @Test
    fun declarationWithPluginPasses() {
        val found =
            ComposePluginRules.violations(
                listOf(module("ui-shell", true, declaration("fun Modifier.dashedRing() = this"))),
            )

        assertTrue(found.isEmpty())
    }

    @Test
    fun moduleWithoutDeclarationsPasses() {
        val module = ComposePluginRules.Module("core", "core/build.gradle.kts", false, emptyList())

        assertTrue(ComposePluginRules.violations(listOf(module)).isEmpty())
    }
}
