package nl.r1a.grassmap.core.services

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Persists the button order of one profile in a JSON file.
 *
 * The file is written to a temporary sibling, flushed and moved over the target
 * atomically, so a crash or a failed move leaves the old order in place. A
 * missing, corrupt or unreadable file reads as
 * [MapControlCommands.DEFAULT_ORDER]; it never throws at the caller.
 */
class ButtonLayoutStore(
    private val file: File,
) {
    @Synchronized
    fun load(profileId: ProfileId): List<CommandId> {
        val stored = readProfiles()[profileId.value] ?: return MapControlCommands.DEFAULT_ORDER
        return validated(stored) ?: MapControlCommands.DEFAULT_ORDER
    }

    /**
     * Writes the order for [profileId], keeping every other profile untouched.
     * Only a permutation of [MapControlCommands.DEFAULT_ORDER] is accepted.
     */
    @Synchronized
    fun save(
        profileId: ProfileId,
        layout: List<CommandId>,
    ): Outcome<Unit> {
        if (!isPermutation(layout)) {
            return Outcome.Failure(AppError.InvalidInput)
        }
        val profiles = readProfiles() + (profileId.value to layout.map { it.value })
        return writeProfiles(profiles)
    }

    private fun readProfiles(): Map<String, List<String>> =
        try {
            readProfilesOrNull() ?: emptyMap()
        } catch (exception: IOException) {
            emptyMap()
        } catch (exception: SerializationException) {
            emptyMap()
        } catch (exception: SecurityException) {
            emptyMap()
        }

    private fun readProfilesOrNull(): Map<String, List<String>>? {
        if (!file.isFile) {
            return null
        }
        val root = Json.parseToJsonElement(file.readText()) as? JsonObject ?: return null
        val profiles = root[PROFILES] as? JsonObject ?: return null
        return profiles
            .mapNotNull { (id, value) ->
                val array = value as? JsonArray ?: return@mapNotNull null
                val layout = array.mapNotNull { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }
                if (layout.size != array.size) null else id to layout
            }.toMap()
    }

    private fun writeProfiles(profiles: Map<String, List<String>>): Outcome<Unit> {
        val parent = file.parentFile ?: return failure("no parent directory")
        return try {
            if (!parent.isDirectory && !parent.mkdirs()) {
                return failure("cannot create $parent")
            }
            val document =
                buildJsonObject {
                    put(
                        PROFILES,
                        buildJsonObject {
                            profiles.forEach { (id, layout) ->
                                put(
                                    id,
                                    buildJsonArray {
                                        layout.forEach { add(JsonPrimitive(it)) }
                                    },
                                )
                            }
                        },
                    )
                }
            val temporary = File(parent, file.name + TEMPORARY_SUFFIX)
            FileOutputStream(temporary).use { stream ->
                stream.write(document.toString().toByteArray())
                stream.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                file.toPath(),
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

    private fun validated(ids: List<String>): List<CommandId>? {
        val layout = ids.map(::CommandId)
        return layout.takeIf(::isPermutation)
    }

    private fun isPermutation(layout: List<CommandId>): Boolean =
        layout.size == MapControlCommands.DEFAULT_ORDER.size &&
            layout.toSet() == MapControlCommands.DEFAULT_ORDER.toSet()

    private fun failure(detail: String?): Outcome.Failure = Outcome.Failure(AppError.Internal(ERROR_ID, detail))

    private companion object {
        const val PROFILES = "profiles"
        const val TEMPORARY_SUFFIX = ".tmp"
        const val ERROR_ID = "core.services.button_layout"
    }
}
