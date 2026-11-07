package buildlogic

import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Validates every plugin manifest before it reaches the generated registry.
 */
abstract class LintPluginManifestsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifests: ConfigurableFileCollection

    @TaskAction
    fun lint() {
        val errors = mutableListOf<String>()
        val parsed = mutableListOf<ParsedManifest>()
        for (file in manifests.files.sortedBy { it.path }) {
            if (!file.isFile) {
                errors += "Missing plugin manifest: ${file.path}"
                continue
            }
            try {
                parsed += ManifestReader.read(file)
            } catch (exception: GradleException) {
                errors += exception.message.orEmpty()
            }
        }

        val pluginIds = mutableMapOf<String, String>()
        val commandIds = mutableMapOf<String, String>()
        val streamIds = mutableMapOf<String, String>()
        val widgetIds = mutableMapOf<String, String>()
        val providers = mutableSetOf<String>()
        var commandCount = 0
        var streamCount = 0
        var widgetCount = 0

        for (manifest in parsed) {
            val raw = manifest.raw
            val where = manifest.file.path
            val pluginId = requiredString(raw, "id", where, errors)
            if (pluginId.isNotBlank()) {
                if (!PLUGIN_ID.matches(pluginId)) {
                    errors += "$where: plugin id '$pluginId' must be lowercase and dotted"
                }
                pluginIds.put(pluginId, where)?.let { previous ->
                    errors += "$where: duplicate plugin id '$pluginId', also declared in $previous"
                }
            }

            requiredString(raw, "name", where, errors)
            requiredString(raw, "description", where, errors)
            val version = requiredString(raw, "version", where, errors)
            if (version.isNotBlank() && !SEMVER.matches(version)) {
                errors += "$where: version '$version' is not semver"
            }
            requiredString(raw, "api", where, errors)
            val implementation = requiredString(raw, "implementation", where, errors)
            if (implementation.isNotBlank()) {
                checkImplementation(manifest.pluginDirectory, implementation, where, errors)
            }

            val contributes = raw.objectValue("contributes")
            val declaredCommands = contributes.objectList("commands")
            errors += reservedNamespaceErrors(where, declaredCommands)
            for (command in declaredCommands) {
                commandCount += 1
                val commandId = requiredString(command, "id", where, errors)
                if (commandId.isNotBlank()) {
                    if (!NAMESPACED.matches(commandId) || !commandId.startsWith("$pluginId.")) {
                        errors += "$where: command id '$commandId' must be namespaced under '$pluginId'"
                    }
                    commandIds.put(commandId, where)?.let { previous ->
                        errors += "$where: duplicate command id '$commandId', also declared in $previous"
                    }
                }
                requiredString(command, "params", where, errors)
                requiredString(command, "result", where, errors)
                requiredString(command, "doc", where, errors)
                val commandImplementation = requiredString(command, "implementation", where, errors)
                if (commandImplementation.isNotBlank()) {
                    checkImplementation(manifest.pluginDirectory, commandImplementation, where, errors)
                }
                val undoable = command["undoable"]
                if (undoable != null && undoable !is Boolean) {
                    errors += "$where: command '$commandId' undoable must be a boolean"
                }
                val allowlisted = command["allowlistedForControl"]
                if (allowlisted != null && allowlisted !is Boolean) {
                    errors += "$where: command '$commandId' allowlistedForControl must be a boolean"
                }
                val requiredSwitch = command["requiresSwitch"]
                if (requiredSwitch != null) {
                    val switchId = requiredSwitch as? String
                    if (switchId == null || !NAMESPACED.matches(switchId)) {
                        errors += "$where: command '$commandId' requiresSwitch must be a namespaced switch id"
                    }
                }
                val dispatcher = command["dispatcher"]
                if (dispatcher != null &&
                    (dispatcher !is String || dispatcher !in COMMAND_DISPATCHERS)
                ) {
                    errors += "$where: command '$commandId' has invalid dispatcher '$dispatcher'"
                }
            }

            for (provider in contributes.objectList("providers")) {
                val contract = requiredString(provider, "contract", where, errors)
                val contractVersion = requiredString(provider, "version", where, errors)
                if (contract.isNotBlank() && !NAMESPACED.matches(contract)) {
                    errors += "$where: provider contract '$contract' must be namespaced"
                }
                if (contractVersion.isNotBlank() && !SEMVER.matches(contractVersion)) {
                    errors += "$where: provider version '$contractVersion' is not semver"
                }
                if (contract.isNotBlank()) {
                    val key = "$pluginId:$contract"
                    if (!providers.add(key)) {
                        errors += "$where: duplicate provider contract '$contract' for '$pluginId'"
                    }
                }
            }

            for (stream in contributes.objectList("streams")) {
                streamCount += 1
                val streamId = requiredString(stream, "id", where, errors)
                if (streamId.isNotBlank()) {
                    if (!NAMESPACED.matches(streamId) || !streamId.startsWith("$pluginId.")) {
                        errors += "$where: stream id '$streamId' must be namespaced under '$pluginId'"
                    }
                    streamIds.put(streamId, where)?.let { previous ->
                        errors += "$where: duplicate stream id '$streamId', also declared in $previous"
                    }
                }
                requiredString(stream, "valueType", where, errors)
                requiredString(stream, "serializer", where, errors)
            }

            val streamsById = contributes.objectList("streams").associateBy { it.string("id").orEmpty() }
            for (widget in contributes.objectList("widgets")) {
                widgetCount += 1
                val widgetId = requiredString(widget, "id", where, errors)
                if (widgetId.isNotBlank()) {
                    if (!NAMESPACED.matches(widgetId) || !widgetId.startsWith("$pluginId.")) {
                        errors += "$where: widget id '$widgetId' must be namespaced under '$pluginId'"
                    }
                    widgetIds.put(widgetId, where)?.let { previous ->
                        errors += "$where: duplicate widget id '$widgetId', also declared in $previous"
                    }
                }
                requiredString(widget, "title", where, errors)
                val valueType = requiredString(widget, "valueType", where, errors)
                val streamId = requiredString(widget, "streamId", where, errors)
                if (streamId.isNotBlank()) {
                    val stream = streamsById[streamId]
                    if (stream == null) {
                        errors += "$where: widget '$widgetId' binds stream '$streamId' that this plugin does not declare"
                    } else if (valueType.isNotBlank() && stream.string("valueType") != valueType) {
                        errors += "$where: widget '$widgetId' value type '$valueType' does not match its stream"
                    }
                }
                runCatching { widgetIntervalMillis(widget["updateIntervalMillis"], widgetId, where) }
                    .onFailure { exception -> errors += exception.message.orEmpty() }
            }

            for (setting in contributes.objectList("settings")) {
                val key = requiredString(setting, "key", where, errors)
                if (key.isNotBlank() && !key.startsWith("$pluginId.")) {
                    errors += "$where: setting key '$key' must be namespaced under '$pluginId'"
                }
                requiredString(setting, "type", where, errors)
                if (!setting.containsKey("default")) {
                    errors += "$where: setting '${key.ifBlank { "?" }}' has no default"
                }
                val scope = setting["scope"]
                if (scope !is String || scope !in SETTING_SCOPES) {
                    errors += "$where: setting '${key.ifBlank { "?" }}' has invalid scope '${scope ?: ""}'"
                }
            }

            val needs = raw.objectValue("needs")
            val location = needs["location"]
            if (location != null && (location !is String || location !in LOCATION_NEEDS)) {
                errors += "$where: invalid location need '$location'"
            }
            for (entry in needs.stringListValue("network")) {
                if (entry.isBlank()) {
                    errors += "$where: network need must not be blank"
                }
            }
            for (entry in needs.stringListValue("regions")) {
                if (entry.isBlank()) {
                    errors += "$where: region need must not be blank"
                }
            }
            for (entry in raw.stringListValue("activation")) {
                if (entry.isBlank()) {
                    errors += "$where: activation event must not be blank"
                }
            }
        }

        if (errors.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Plugin manifest lint failed with ${errors.size} error(s):")
                    errors.forEach { appendLine("  $it") }
                },
            )
        }
        println(
            "Plugin manifests OK: ${parsed.size} plugin(s), $commandCount command(s), " +
                "$streamCount stream(s), $widgetCount widget(s), ${providers.size} provider(s)",
        )
    }

    private fun checkImplementation(
        pluginDirectory: File,
        qualifiedName: String,
        where: String,
        errors: MutableList<String>,
    ) {
        if (!qualifiedName.contains('.')) {
            errors += "$where: implementation '$qualifiedName' must be fully qualified"
            return
        }
        val simpleName = qualifiedName.substringAfterLast('.')
        val declaration = Regex("""\bclass\s+${Regex.escape(simpleName)}\b""")
        val sourceRoot = pluginDirectory.resolve("src/main")
        val found = sourceRoot.isDirectory &&
            sourceRoot.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .any { declaration.containsMatchIn(it.readText()) }
        if (!found) {
            errors += "$where: implementation '$qualifiedName' not found under ${sourceRoot.path}"
        }
    }

    private fun requiredString(
        map: Map<String, Any?>,
        key: String,
        where: String,
        errors: MutableList<String>,
    ): String {
        val value = map.string(key)
        if (value.isNullOrBlank()) {
            errors += "$where: missing '$key'"
            return ""
        }
        return value
    }

    private companion object {
        val PLUGIN_ID = Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)*$")
        val NAMESPACED = Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+$")
        val SEMVER = Regex("^\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?(\\+[0-9A-Za-z.-]+)?$")
        val SETTING_SCOPES = setOf("global", "profile")
        val LOCATION_NEEDS = setOf("none", "while_in_use", "during_navigation")
        val COMMAND_DISPATCHERS = setOf("main", "default", "io")
    }
}

/** The namespace the host reserves for commands it owns, such as `core.map.zoom_in`. */
internal const val RESERVED_COMMAND_NAMESPACE = "core"

/**
 * Rejects a plugin command in the reserved host namespace. `PluginHost` would
 * otherwise shadow the plugin command with the host command of the same id.
 */
internal fun reservedNamespaceErrors(
    where: String,
    commands: List<Map<String, Any?>>,
): List<String> =
    commands.mapNotNull { command ->
        val id = command.string("id") ?: return@mapNotNull null
        if (id == RESERVED_COMMAND_NAMESPACE || id.startsWith("$RESERVED_COMMAND_NAMESPACE.")) {
            "$where: command id '$id' uses the reserved '$RESERVED_COMMAND_NAMESPACE' namespace"
        } else {
            null
        }
    }
