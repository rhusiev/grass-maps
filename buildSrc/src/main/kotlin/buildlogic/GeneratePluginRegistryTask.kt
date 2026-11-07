package buildlogic

import groovy.json.JsonSlurper
import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

internal data class ParsedManifest(
    val file: File,
    val pluginDirectory: File,
    val raw: Map<String, Any?>,
)

internal object ManifestReader {
    private val slurper = JsonSlurper()

    fun read(file: File): ParsedManifest {
        if (!file.isFile) {
            throw GradleException("Missing plugin manifest: ${file.path}")
        }
        val parsed = try {
            slurper.parse(file)
        } catch (exception: Exception) {
            throw GradleException("Cannot parse plugin manifest ${file.path}: ${exception.message}")
        }
        if (parsed !is Map<*, *>) {
            throw GradleException("Plugin manifest must be a JSON object: ${file.path}")
        }
        val raw = parsed.entries.associate { it.key.toString() to it.value }
        return ParsedManifest(
            file = file,
            pluginDirectory = file.parentFile.parentFile.parentFile,
            raw = raw,
        )
    }
}

internal fun Map<String, Any?>.string(key: String): String? = this[key] as? String

internal fun Map<String, Any?>.objectValue(key: String): Map<String, Any?> {
    val value = this[key] ?: return emptyMap()
    if (value !is Map<*, *>) {
        return emptyMap()
    }
    return value.entries.associate { it.key.toString() to it.value }
}

internal fun Map<String, Any?>.objectList(key: String): List<Map<String, Any?>> {
    val value = this[key] ?: return emptyList()
    if (value !is List<*>) {
        return emptyList()
    }
    return value.mapNotNull { item ->
        if (item is Map<*, *>) {
            item.entries.associate { it.key.toString() to it.value }
        } else {
            null
        }
    }
}

internal fun Map<String, Any?>.stringListValue(key: String): List<String> {
    val value = this[key] ?: return emptyList()
    if (value !is List<*>) {
        return emptyList()
    }
    return value.mapNotNull { it as? String }
}

internal fun String.literal(): String = buildString {
    append('"')
    for (character in this@literal) {
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '$' -> append("\\$")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
    append('"')
}

internal fun emitJson(value: Any?): String = when (value) {
    null -> "JsonNull"
    is String -> "JsonPrimitive(${value.literal()})"
    is Boolean -> "JsonPrimitive($value)"
    is Number -> "JsonPrimitive($value)"
    is Map<*, *> -> {
        val entries = value.entries.joinToString(", ") { entry ->
            "${entry.key.toString().literal()} to ${emitJson(entry.value)}"
        }
        "JsonObject(mapOf($entries))"
    }

    is List<*> -> "JsonArray(listOf(${value.joinToString(", ") { emitJson(it) }}))"
    else -> "JsonPrimitive(${value.toString().literal()})"
}

internal fun locationNeed(value: Any?, where: String): String = when (value) {
    null, "none" -> "LocationNeed.NONE"
    "while_in_use" -> "LocationNeed.WHILE_IN_USE"
    "during_navigation" -> "LocationNeed.DURING_NAVIGATION"
    else -> throw GradleException("$where: invalid location need '$value'")
}

internal fun settingScope(value: Any?, where: String): String = when (value) {
    "global" -> "SettingScope.GLOBAL"
    "profile" -> "SettingScope.PROFILE"
    else -> throw GradleException("$where: invalid setting scope '${value ?: ""}'")
}

internal fun commandDispatcher(value: Any?, where: String): String = when (value) {
    null, "default" -> "CommandDispatcher.DEFAULT"
    "main" -> "CommandDispatcher.MAIN"
    "io" -> "CommandDispatcher.IO"
    else -> throw GradleException("$where: invalid command dispatcher '$value'")
}

internal fun switchId(value: Any?, commandId: String, where: String): String = when (value) {
    null -> "null"
    is String -> "SwitchId(${value.literal()})"
    else -> throw GradleException(
        "$where: command '$commandId' requiresSwitch must be a string, got '$value'",
    )
}

internal fun booleanFlag(
    value: Any?,
    key: String,
    commandId: String,
    where: String,
): Boolean = when (value) {
    null -> false
    is Boolean -> value
    else -> throw GradleException(
        "$where: command '$commandId' $key must be a boolean, got '$value'",
    )
}

internal fun providerCapabilities(value: Any?): String =
    if (value is Map<*, *>) emitJson(value) else "JsonObject(emptyMap())"

internal fun emitStringList(values: List<String>): String =
    "listOf(${values.joinToString(", ") { it.literal() }})"

/** The shortest period a widget may declare, mirrored from WidgetCadence. */
internal const val WIDGET_MIN_INTERVAL_MILLIS: Long = 100L

internal fun widgetIntervalMillis(
    value: Any?,
    widgetId: String,
    where: String,
): Long {
    val millis =
        when (value) {
            is Number -> value.toLong()
            else -> throw GradleException("$where: widget '$widgetId' updateIntervalMillis must be a number")
        }
    if (millis < WIDGET_MIN_INTERVAL_MILLIS) {
        throw GradleException(
            "$where: widget '$widgetId' updateIntervalMillis must be at least " +
                "$WIDGET_MIN_INTERVAL_MILLIS ms",
        )
    }
    return millis
}

/**
 * Reads every plugin manifest and emits the static registries used by app.
 * No scanning and no reflection happen at runtime.
 */
abstract class GeneratePluginRegistryTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifests: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val parsed = manifests.files.sortedBy { it.path }.map { ManifestReader.read(it) }
        val output = outputDirectory.get().asFile
        output.mkdirs()
        output.resolve("GeneratedPlugins.kt").writeText(generatePlugins(parsed))
        output.resolve("GeneratedStreams.kt").writeText(generateStreams(parsed))
    }

    private fun generatePlugins(manifests: List<ParsedManifest>): String = buildString {
        appendLine("package nl.r1a.grassmap.app.generated")
        appendLine()
        appendLine("import kotlin.reflect.KClass")
        appendLine("import kotlinx.serialization.json.JsonArray")
        appendLine("import kotlinx.serialization.json.JsonNull")
        appendLine("import kotlinx.serialization.json.JsonObject")
        appendLine("import kotlinx.serialization.json.JsonPrimitive")
        appendLine("import nl.r1a.grassmap.plugin.api.command.Command")
        appendLine("import nl.r1a.grassmap.plugin.api.command.CommandDispatcher")
        appendLine("import nl.r1a.grassmap.plugin.api.contract.CommandId")
        appendLine("import nl.r1a.grassmap.plugin.api.contract.PluginId")
        appendLine("import nl.r1a.grassmap.plugin.api.contract.StreamId")
        appendLine("import nl.r1a.grassmap.plugin.api.contract.SwitchId")
        appendLine("import nl.r1a.grassmap.plugin.api.contract.WidgetId")
        appendLine("import nl.r1a.grassmap.plugin.api.manifest.CommandDeclaration")
        appendLine("import nl.r1a.grassmap.plugin.api.manifest.GeneratedCommand")
        appendLine("import nl.r1a.grassmap.plugin.api.manifest.GeneratedProvider")
        appendLine("import nl.r1a.grassmap.plugin.api.manifest.LocationNeed")
        appendLine("import nl.r1a.grassmap.plugin.api.manifest.PluginContributions")
        appendLine("import nl.r1a.grassmap.plugin.api.manifest.PluginFactory")
        appendLine("import nl.r1a.grassmap.plugin.api.manifest.PluginManifest")
        appendLine("import nl.r1a.grassmap.plugin.api.manifest.PluginNeeds")
        appendLine("import nl.r1a.grassmap.plugin.api.manifest.ProviderDeclaration")
        appendLine("import nl.r1a.grassmap.plugin.api.manifest.SettingDeclaration")
        appendLine("import nl.r1a.grassmap.plugin.api.manifest.SettingScope")
        appendLine("import nl.r1a.grassmap.plugin.api.manifest.StreamDeclaration")
        appendLine("import nl.r1a.grassmap.plugin.api.manifest.WidgetDeclaration")
        appendLine("import nl.r1a.grassmap.plugin.api.provider.ProviderContract")
        appendLine()
        appendLine("object GeneratedPlugins {")
        appendLine("    val plugins: List<PluginFactory> = listOf(")
        for (manifest in manifests) {
            appendLine("        PluginFactory(")
            appendLine("            manifest = ${emitManifest(manifest.raw, manifest.file.path, "            ")},")
            appendLine("            create = { ${manifest.raw.string("implementation")}() },")
            appendLine("        ),")
        }
        appendLine("    )")
        appendLine()
        appendLine("    val commands: List<GeneratedCommand> = listOf(")
        for (manifest in manifests) {
            val pluginId = manifest.raw.string("id").orEmpty()
            val commands = manifest.raw.objectValue("contributes").objectList("commands")
            val where = manifest.file.path
            for (command in commands) {
                val commandId = command.string("id").orEmpty()
                val undoable = booleanFlag(command["undoable"], "undoable", commandId, where)
                val requiresSwitch = switchId(command["requiresSwitch"], commandId, where)
                val allowlisted = booleanFlag(
                    command["allowlistedForControl"],
                    "allowlistedForControl",
                    commandId,
                    where,
                )
                appendLine("        GeneratedCommand(")
                appendLine("            id = CommandId(${commandId.literal()}),")
                appendLine("            pluginId = PluginId(${pluginId.literal()}),")
                appendLine("            undoable = $undoable,")
                appendLine("            requiresSwitch = $requiresSwitch,")
                appendLine("            allowlistedForControl = $allowlisted,")
                appendLine(
                    "            dispatcher = " +
                        "${commandDispatcher(command["dispatcher"], where)},",
                )
                appendLine("        ),")
            }
        }
        appendLine("    )")
        appendLine()
        appendLine("    val providers: List<GeneratedProvider> = listOf(")
        for (manifest in manifests) {
            val pluginId = manifest.raw.string("id").orEmpty()
            val providers = manifest.raw.objectValue("contributes").objectList("providers")
            for (provider in providers) {
                val contract = provider.string("contract").orEmpty()
                val version = provider.string("version").orEmpty()
                appendLine(
                    "        GeneratedProvider(pluginId = PluginId(${pluginId.literal()}), " +
                        "contract = ProviderContract(${contract.literal()}, ${version.literal()})),",
                )
            }
        }
        appendLine("    )")
        appendLine()
        appendLine("    @Suppress(\"unused\")")
        appendLine("    val declaredCommandImplementations: List<KClass<out Command<*, *>>> = listOf(")
        for (manifest in manifests) {
            val commands = manifest.raw.objectValue("contributes").objectList("commands")
            for (command in commands) {
                val implementation = command.string("implementation").orEmpty()
                appendLine("        $implementation::class,")
            }
        }
        appendLine("    )")
        appendLine("}")
    }

    private fun generateStreams(manifests: List<ParsedManifest>): String = buildString {
        appendLine("package nl.r1a.grassmap.app.generated")
        appendLine()
        appendLine("import nl.r1a.grassmap.plugin.api.contract.PluginId")
        appendLine("import nl.r1a.grassmap.plugin.api.contract.StreamId")
        appendLine("import nl.r1a.grassmap.plugin.api.manifest.GeneratedStream")
        appendLine()
        appendLine("object GeneratedStreams {")
        appendLine("    val streams: List<GeneratedStream> = listOf(")
        for (manifest in manifests) {
            val pluginId = manifest.raw.string("id").orEmpty()
            val streams = manifest.raw.objectValue("contributes").objectList("streams")
            for (stream in streams) {
                val streamId = stream.string("id").orEmpty()
                val valueType = stream.string("valueType").orEmpty()
                val serializer = stream.string("serializer").orEmpty()
                appendLine("        GeneratedStream(")
                appendLine("            id = StreamId(${streamId.literal()}),")
                appendLine("            pluginId = PluginId(${pluginId.literal()}),")
                appendLine("            valueType = ${valueType.literal()},")
                appendLine("            serializer = ${serializer.literal()},")
                appendLine("        ),")
            }
        }
        appendLine("    )")
        appendLine("}")
    }

    private fun emitManifest(raw: Map<String, Any?>, where: String, indent: String): String = buildString {
        val contributes = raw.objectValue("contributes")
        val needs = raw.objectValue("needs")
        appendLine("PluginManifest(")
        appendLine("$indent    id = PluginId(${raw.string("id").orEmpty().literal()}),")
        appendLine("$indent    name = ${raw.string("name").orEmpty().literal()},")
        appendLine("$indent    description = ${raw.string("description").orEmpty().literal()},")
        appendLine("$indent    version = ${raw.string("version").orEmpty().literal()},")
        appendLine("$indent    api = ${raw.string("api").orEmpty().literal()},")
        appendLine("$indent    implementation = ${raw.string("implementation").orEmpty().literal()},")
        appendLine("$indent    contributes = PluginContributions(")
        appendLine("$indent        commands = listOf(")
        for (command in contributes.objectList("commands")) {
            val commandId = command.string("id").orEmpty()
            val undoable = booleanFlag(command["undoable"], "undoable", commandId, where)
            val requiresSwitch = switchId(command["requiresSwitch"], commandId, where)
            val allowlisted = booleanFlag(
                command["allowlistedForControl"],
                "allowlistedForControl",
                commandId,
                where,
            )
            appendLine("$indent            CommandDeclaration(")
            appendLine("$indent                id = CommandId(${commandId.literal()}),")
            appendLine("$indent                params = ${command.string("params").orEmpty().literal()},")
            appendLine("$indent                result = ${command.string("result").orEmpty().literal()},")
            appendLine("$indent                doc = ${command.string("doc").orEmpty().literal()},")
            appendLine("$indent                undoable = $undoable,")
            appendLine("$indent                requiresSwitch = $requiresSwitch,")
            appendLine("$indent                allowlistedForControl = $allowlisted,")
            appendLine(
                "$indent                dispatcher = " +
                    "${commandDispatcher(command["dispatcher"], where)},",
            )
            appendLine(
                "$indent                implementation = " +
                    "${command.string("implementation").orEmpty().literal()},",
            )
            appendLine("$indent            ),")
        }
        appendLine("$indent        ),")
        appendLine("$indent        providers = listOf(")
        for (provider in contributes.objectList("providers")) {
            appendLine("$indent            ProviderDeclaration(")
            appendLine("$indent                contract = ${provider.string("contract").orEmpty().literal()},")
            appendLine("$indent                version = ${provider.string("version").orEmpty().literal()},")
            appendLine("$indent                capabilities = ${providerCapabilities(provider["capabilities"])},")
            appendLine("$indent            ),")
        }
        appendLine("$indent        ),")
        appendLine("$indent        streams = listOf(")
        for (stream in contributes.objectList("streams")) {
            appendLine("$indent            StreamDeclaration(")
            appendLine("$indent                id = StreamId(${stream.string("id").orEmpty().literal()}),")
            appendLine("$indent                valueType = ${stream.string("valueType").orEmpty().literal()},")
            appendLine("$indent                serializer = ${stream.string("serializer").orEmpty().literal()},")
            appendLine("$indent            ),")
        }
        appendLine("$indent        ),")
        appendLine("$indent        widgets = listOf(")
        for (widget in contributes.objectList("widgets")) {
            val widgetId = widget.string("id").orEmpty()
            appendLine("$indent            WidgetDeclaration(")
            appendLine("$indent                id = WidgetId(${widgetId.literal()}),")
            appendLine("$indent                title = ${widget.string("title").orEmpty().literal()},")
            appendLine("$indent                valueType = ${widget.string("valueType").orEmpty().literal()},")
            appendLine("$indent                streamId = StreamId(${widget.string("streamId").orEmpty().literal()}),")
            appendLine(
                "$indent                updateIntervalMillis = " +
                    "${widgetIntervalMillis(widget["updateIntervalMillis"], widgetId, where)},",
            )
            appendLine("$indent            ),")
        }
        appendLine("$indent        ),")
        appendLine("$indent        settings = listOf(")
        for (setting in contributes.objectList("settings")) {
            appendLine("$indent            SettingDeclaration(")
            appendLine("$indent                key = ${setting.string("key").orEmpty().literal()},")
            appendLine("$indent                type = ${setting.string("type").orEmpty().literal()},")
            appendLine("$indent                default = ${emitJson(setting["default"])},")
            appendLine("$indent                scope = ${settingScope(setting["scope"], where)},")
            appendLine("$indent            ),")
        }
        appendLine("$indent        ),")
        appendLine("$indent    ),")
        appendLine("$indent    needs = PluginNeeds(")
        appendLine("$indent        network = ${emitStringList(needs.stringListValue("network"))},")
        appendLine("$indent        location = ${locationNeed(needs["location"], where)},")
        appendLine("$indent        regions = ${emitStringList(needs.stringListValue("regions"))},")
        appendLine("$indent    ),")
        appendLine("$indent    activation = ${emitStringList(raw.stringListValue("activation"))},")
        append("$indent)")
    }
}
