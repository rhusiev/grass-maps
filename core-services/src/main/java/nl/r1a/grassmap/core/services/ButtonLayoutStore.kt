package nl.r1a.grassmaps.core.services

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmaps.plugin.api.contract.CommandId
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Persists the map control layout of every profile in one JSON file (T-0606).
 *
 * The file is written to a temporary sibling, flushed and moved over the target
 * atomically, so a crash or a failed move leaves the old layout in place. A
 * missing, corrupt or unreadable file reads as [MapButtonLayout.DEFAULT]; it
 * never throws at the caller.
 *
 * A file written by the superseded order-only editor (a profile mapped to an
 * array of five command ids) is read as the first five slots of the six-slot
 * layout. A newer schema version reads as the default instead of being
 * overwritten. A null file keeps the layout in memory only.
 */
class ButtonLayoutStore(
    private val file: File?,
) {
    @Synchronized
    fun load(profileId: ProfileId): MapButtonLayout = readProfiles()[profileId.value] ?: MapButtonLayout.DEFAULT

    /**
     * Writes the layout for [profileId], keeping every other profile untouched.
     * Only the configured commands, each at most once, in [MapButtonLayout.SLOT_COUNT]
     * slots are accepted.
     */
    @Synchronized
    fun save(
        profileId: ProfileId,
        layout: MapButtonLayout,
    ): Outcome<Unit> {
        if (!isValid(layout)) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        if (hasNewerSchema()) {
            return failure("the file has a newer schema")
        }
        val profiles = readProfiles() + (profileId.value to layout)
        return writeProfiles(profiles)
    }

    private fun readProfiles(): Map<String, MapButtonLayout> =
        try {
            readProfilesOrNull() ?: emptyMap()
        } catch (exception: IOException) {
            emptyMap()
        } catch (exception: SerializationException) {
            emptyMap()
        } catch (exception: SecurityException) {
            emptyMap()
        }

    private fun readProfilesOrNull(): Map<String, MapButtonLayout>? {
        val target = file ?: return null
        if (!target.isFile) {
            return null
        }
        val root = Json.parseToJsonElement(target.readText()) as? JsonObject ?: return null
        if (isNewerSchema(root)) {
            return null
        }
        val profiles = root[PROFILES] as? JsonObject ?: return null
        return profiles
            .mapNotNull { (id, value) ->
                val layout = readLayout(value) ?: return@mapNotNull null
                if (!isValid(layout)) null else id to layout
            }.toMap()
    }

    private fun hasNewerSchema(): Boolean {
        val target = file ?: return false
        if (!target.isFile) {
            return false
        }
        return try {
            val root = Json.parseToJsonElement(target.readText()) as? JsonObject ?: return false
            isNewerSchema(root)
        } catch (exception: IOException) {
            false
        } catch (exception: SerializationException) {
            false
        } catch (exception: SecurityException) {
            false
        }
    }

    private fun isNewerSchema(root: JsonObject): Boolean =
        (root[VERSION] as? JsonPrimitive)?.content?.toIntOrNull()?.let { it > SCHEMA_VERSION } == true

    private fun readLayout(value: JsonElement): MapButtonLayout? =
        when (value) {
            is JsonArray -> {
                val order =
                    readCommands(value)?.takeIf { it.size == MapControlCommands.DEFAULT_ORDER.size }
                        ?: return null
                MapButtonLayout(order + List(MapButtonLayout.SLOT_COUNT - order.size) { null })
            }

            is JsonObject -> {
                val slots = (value[SLOTS] as? JsonArray)?.let(::readSlots) ?: return null
                MapButtonLayout(
                    slots = slots,
                    quickAccess = readQuickAccess(value[QUICK_ACCESS]),
                    arc = (value[ARC] as? JsonPrimitive)?.booleanOrNull ?: false,
                )
            }

            else -> {
                null
            }
        }

    private fun readSlots(array: JsonArray): List<CommandId?>? {
        val slots =
            array.map { element ->
                when (element) {
                    JsonNull -> null
                    is JsonPrimitive -> element.contentOrNullString()?.let(::CommandId) ?: return null
                    else -> return null
                }
            }
        return slots.takeIf { it.size == MapButtonLayout.SLOT_COUNT }
    }

    private fun readCommands(array: JsonArray): List<CommandId>? =
        array
            .map { element ->
                (element as? JsonPrimitive)?.contentOrNullString()?.let(::CommandId) ?: return null
            }

    private fun readQuickAccess(value: JsonElement?): QuickAccessButton? {
        val objectValue = value as? JsonObject ?: return null
        val command = (objectValue[COMMAND] as? JsonPrimitive)?.contentOrNullString() ?: return null
        val parameters = objectValue[PARAMETERS] as? JsonObject ?: JsonObject(emptyMap())
        return QuickAccessButton(CommandId(command), parameters)
    }

    private fun isValid(layout: MapButtonLayout): Boolean {
        if (layout.slots.size != MapButtonLayout.SLOT_COUNT) {
            return false
        }
        val commands = layout.commands
        return commands.size == commands.toSet().size &&
            commands.all { it in MapControlCommands.CONFIGURABLE } &&
            (layout.quickAccess == null || layout.quickAccess.command in MapControlCommands.CONFIGURABLE)
    }

    private fun writeProfiles(profiles: Map<String, MapButtonLayout>): Outcome<Unit> {
        val target = file ?: return Outcome.Success(Unit)
        val parent = target.parentFile ?: return failure("no parent directory")
        return try {
            if (!parent.isDirectory && !parent.mkdirs()) {
                return failure("cannot create $parent")
            }
            val document =
                buildJsonObject {
                    put(VERSION, SCHEMA_VERSION)
                    put(
                        PROFILES,
                        buildJsonObject {
                            profiles.forEach { (id, layout) ->
                                put(id, layoutJson(layout))
                            }
                        },
                    )
                }
            val temporary = File(parent, target.name + TEMPORARY_SUFFIX)
            FileOutputStream(temporary).use { stream ->
                stream.write(document.toString().toByteArray())
                stream.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            Outcome.Success(Unit)
        } catch (exception: IOException) {
            failure(exception.message)
        } catch (exception: SecurityException) {
            failure(exception.message)
        }
    }

    private fun layoutJson(layout: MapButtonLayout): JsonObject =
        buildJsonObject {
            put(
                SLOTS,
                buildJsonArray {
                    layout.slots.forEach { command -> add(command?.let { JsonPrimitive(it.value) } ?: JsonNull) }
                },
            )
            layout.quickAccess?.let { quickAccess ->
                put(
                    QUICK_ACCESS,
                    buildJsonObject {
                        put(COMMAND, quickAccess.command.value)
                        put(PARAMETERS, quickAccess.parameters)
                    },
                )
            }
            put(ARC, layout.arc)
        }

    private fun JsonPrimitive.contentOrNullString(): String? = if (isString) content else null

    private fun failure(detail: String?): Outcome.Failure = Outcome.Failure(AppError.Internal(ERROR_ID, detail))

    private companion object {
        const val SCHEMA_VERSION = 2
        const val VERSION = "version"
        const val PROFILES = "profiles"
        const val SLOTS = "slots"
        const val QUICK_ACCESS = "quickAccess"
        const val COMMAND = "command"
        const val PARAMETERS = "params"
        const val ARC = "arc"
        const val TEMPORARY_SUFFIX = ".tmp"
        const val ERROR_ID = "core.services.button_layout"
    }
}
