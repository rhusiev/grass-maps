package nl.r1a.grassmap.core.services

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.ProfileId
import nl.r1a.grassmap.plugin.api.service.ProfileValueStore
import nl.r1a.grassmap.plugin.api.service.ProfileValues
import java.io.File
import java.io.IOException

/**
 * The file of one pre-profile store and the function that reads it. [read]
 * returns null for a file it cannot make sense of.
 */
class LegacyProfileFile(
    val file: File,
    val read: (File) -> ProfileValues?,
)

/** The points of a migration at which a process can die. A test hook, never a behaviour switch. */
enum class MigrationStep {
    /** The new file is on disk and not yet checked against the old one. */
    Written,

    /** The new file read back equal to the old one and the old one is still there. */
    Verified,
}

/**
 * One per-profile store in one JSON file (architecture sections 11 and 20):
 * `{"schema_version":1,"profiles":{"<profile id>":{"<key>":"<value>"}}}`.
 *
 * The store declares one schema version. A file with a higher version is
 * refused with [AppError.NewerSchema] on read and on write, so an older build
 * never overwrites data it cannot understand. A missing, unreadable or
 * unversioned file reads as empty. A write is atomic.
 *
 * A store that had a file before this format passes it as [legacy], the one
 * previous version it reads forward. [read] migrates it: it writes the new
 * file, reads it back, compares it with what the old file held and only then
 * removes the old file. Until the comparison passes the old file stays, and it
 * stays for good when it cannot be read or the new file already differs from
 * it. A process that dies between two of those steps leaves the old file in
 * place, so the next [read] migrates again.
 */
class ProfileValueFile(
    private val file: File,
    private val schemaVersion: Int,
    private val errorId: String,
    private val legacy: LegacyProfileFile? = null,
    private val checkpoint: (MigrationStep) -> Unit = {},
) : ProfileValueStore {
    @Synchronized
    override fun read(): Outcome<ProfileValues> {
        val current =
            when (val stored = readStored()) {
                is Outcome.Failure -> return stored
                is Outcome.Success -> stored.value
            }
        val old = legacy
        return when {
            old == null -> Outcome.Success(current ?: emptyMap())
            current == null -> Outcome.Success(migrate(old))
            else -> Outcome.Success(current).also { retireIfEqual(old, current) }
        }
    }

    @Synchronized
    override fun write(values: ProfileValues): Outcome<Unit> {
        val stored = readStored()
        if (stored is Outcome.Failure) {
            return stored
        }
        return AtomicFiles.write(file, errorId) { stream -> stream.write(encode(values).toByteArray()) }
    }

    private fun migrate(old: LegacyProfileFile): ProfileValues {
        val expected = old.read(old.file) ?: return emptyMap()
        val written = AtomicFiles.write(file, errorId) { it.write(encode(expected).toByteArray()) }
        if (written is Outcome.Success) {
            checkpoint(MigrationStep.Written)
            retireIfEqual(old, expected)
        }
        return expected
    }

    private fun retireIfEqual(
        old: LegacyProfileFile,
        current: ProfileValues,
    ) {
        val expected = old.read(old.file) ?: return
        val readBack = (readStored() as? Outcome.Success)?.value
        if (expected != current || readBack != current) {
            return
        }
        checkpoint(MigrationStep.Verified)
        old.file.delete()
    }

    private fun readStored(): Outcome<ProfileValues?> =
        try {
            if (!file.isFile) {
                Outcome.Success(null)
            } else {
                decode(file.readText())
            }
        } catch (exception: IOException) {
            Outcome.Success(null)
        } catch (exception: SecurityException) {
            Outcome.Success(null)
        }

    private fun decode(text: String): Outcome<ProfileValues?> {
        val root =
            try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (exception: SerializationException) {
                null
            } ?: return Outcome.Success(null)
        val version = (root[SCHEMA_VERSION_KEY] as? JsonPrimitive)?.intOrNull ?: return Outcome.Success(null)
        if (version > schemaVersion) {
            return Outcome.Failure(AppError.NewerSchema(found = version, supported = schemaVersion))
        }
        val profiles = root[PROFILES_KEY] as? JsonObject ?: return Outcome.Success(null)
        return Outcome.Success(profiles.mapNotNull(::decodeProfile).toMap())
    }

    private fun decodeProfile(entry: Map.Entry<String, JsonElement>): Pair<ProfileId, Map<String, String>>? {
        val values = entry.value as? JsonObject ?: return null
        val strings = values.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { key to it } }
        return ProfileId(entry.key) to strings.toMap()
    }

    private fun encode(values: ProfileValues): String =
        buildJsonObject {
            put(SCHEMA_VERSION_KEY, schemaVersion)
            put(
                PROFILES_KEY,
                buildJsonObject {
                    for ((profile, entries) in values.entries.sortedBy { it.key.value }) {
                        put(profile.value, buildJsonObject { entries.forEach { (key, value) -> put(key, value) } })
                    }
                },
            )
        }.toString()

    private companion object {
        const val SCHEMA_VERSION_KEY = "schema_version"
        const val PROFILES_KEY = "profiles"
    }
}
