package nl.r1a.grassmaps.plugins.manager

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.r1a.grassmaps.plugin.api.contract.PluginId
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every plugin the manager lists carries real text. A manifest names a string
 * key and the app resolves it by name, so a key that no plugin module declares
 * reaches the row as the key itself.
 */
class PluginManifestTextTest {
    @Test
    fun everyListedPluginDeclaresItsNameAndDescription() {
        for (id in PluginGroups.switchableIds) {
            val module = moduleOf(id)
            val manifest = manifestOf(module)
            for (key in listOf(NAME_KEY, DESCRIPTION_KEY)) {
                val text = manifest.getValue(key).jsonPrimitive.content
                assertTrue("$id: $text is not declared in $module", declares(module, text))
            }
        }
    }

    private fun moduleOf(id: PluginId): File =
        (File(PLUGINS_ROOT).listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .firstOrNull { module ->
                val manifest = File(module, MANIFEST)
                manifest.isFile && manifestOf(module).getValue("id").jsonPrimitive.content == id.value
            } ?: throw AssertionError("no plugin module declares ${id.value}")

    private fun manifestOf(module: File): JsonObject = Json.parseToJsonElement(File(module, MANIFEST).readText()).jsonObject

    private fun declares(
        module: File,
        key: String,
    ): Boolean =
        listOf(ENGLISH, UKRAINIAN).all { values ->
            File(module, "src/main/res/$values/strings.xml").let { it.isFile && ("name=\"$key\"" in it.readText()) }
        }

    private companion object {
        const val PLUGINS_ROOT = "../"
        const val MANIFEST = "src/main/plugin.json"
        const val NAME_KEY = "name"
        const val DESCRIPTION_KEY = "description"
        const val ENGLISH = "values"
        const val UKRAINIAN = "values-uk"
    }
}
