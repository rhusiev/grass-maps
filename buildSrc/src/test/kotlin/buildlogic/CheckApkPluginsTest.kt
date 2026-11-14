package buildlogic

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CheckApkPluginsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun developmentPluginIdsComeFromThePluginManifests() {
        val first = manifest("nav-simulator", "nav.simulator", "nl.r1a.NavSimulatorPlugin")
        val second = manifest("dev-tools", "dev.tools", "nl.r1a.DevToolsPlugin")

        val plugins = developmentPlugins(listOf(first, second))

        assertEquals(listOf("nav.simulator", "dev.tools"), plugins.map { it.id })
        assertEquals(
            listOf("nl.r1a.NavSimulatorPlugin", "nl.r1a.DevToolsPlugin"),
            plugins.map { it.implementation },
        )
    }

    @Test
    fun aManifestWithoutAnImplementationClassIsRejected() {
        val file = temporary.newFile("plugin.json")
        file.writeText("""{"id":"nav.simulator"}""")

        val error =
            runCatching { developmentPlugins(listOf(file)) }
                .exceptionOrNull()

        assertTrue(error is org.gradle.api.GradleException)
        assertTrue(error?.message.orEmpty().contains("no implementation class"))
    }

    @Test
    fun aPackagedImplementationClassIsNamedByItsPluginId() {
        val apk = temporary.newFile("app-release.apk")
        writeApk(apk, mapOf("classes.dex" to "before nl.r1a.NavSimulatorPlugin after"))
        val plugins =
            listOf(
                DevelopmentPlugin("nav.simulator", "nl.r1a.NavSimulatorPlugin"),
                DevelopmentPlugin("example", "nl.r1a.ExamplePlugin"),
            )

        val findings = packagedDevelopmentPlugins(apk, plugins)

        assertEquals(1, findings.size)
        assertTrue(findings.single().contains("'nav.simulator'"))
        assertTrue(findings.single().contains("classes.dex"))
    }

    @Test
    fun anApkWithoutTheImplementationClassesHasNoFindings() {
        val apk = temporary.newFile("app-release.apk")
        writeApk(apk, mapOf("classes.dex" to "no development plugin here"))
        val plugins = listOf(DevelopmentPlugin("nav.simulator", "nl.r1a.NavSimulatorPlugin"))

        assertTrue(packagedDevelopmentPlugins(apk, plugins).isEmpty())
    }

    private fun manifest(
        directory: String,
        id: String,
        implementation: String,
    ): File {
        val root = temporary.newFolder(directory)
        val file = File(root, "src/main/plugin.json")
        file.parentFile.mkdirs()
        file.writeText(
            """
            {
              "id": "$id",
              "implementation": "$implementation"
            }
            """.trimIndent(),
        )
        return file
    }

    private fun writeApk(
        file: File,
        entries: Map<String, String>,
    ) {
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}
