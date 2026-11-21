package nl.r1a.grassmaps.core.services.backup

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The bundle container: the manifest, its version gate and its file checks. */
class BundleZipTest {
    @Test
    fun aWrittenBundleReadsBackWithItsManifestAndFiles() {
        val gpx = "<gpx version=\"1.1\"><trk><name>one</name></trk></gpx>".toByteArray()
        val bundle =
            BackupBundle(
                formatVersion = 1,
                appVersion = "0.1.0",
                createdAtMillis = 1_794_000_000_000L,
                kinds =
                    listOf(
                        BundleKind(
                            "tracks",
                            listOf(
                                BundleEntry(
                                    id = "abc",
                                    value = objectOf("name" to "one"),
                                    files = listOf(BundleFile("tracks/abc.gpx", gpx)),
                                ),
                            ),
                        ),
                        BundleKind("profiles", listOf(BundleEntry("walk", objectOf("route_profile" to "walk")))),
                    ),
            )

        val bytes = written(bundle)
        val read = read(bytes)

        assertEquals(1, read.formatVersion)
        assertEquals("0.1.0", read.appVersion)
        assertEquals(1_794_000_000_000L, read.createdAtMillis)
        assertEquals(listOf("tracks", "profiles"), read.kinds.map { kind -> kind.id })
        assertEquals(
            listOf("abc"),
            read.kinds
                .first()
                .entries
                .map { entry -> entry.id },
        )
        assertEquals(
            gpx.decodeToString(),
            read.kinds
                .first()
                .entries
                .first()
                .files
                .single()
                .bytes
                .decodeToString(),
        )
        assertTrue(read.warnings.isEmpty())
    }

    @Test
    fun aNewerManifestVersionIsATypedRefusal() {
        val bundle =
            BackupBundle(
                formatVersion = 2,
                appVersion = "0.2.0",
                createdAtMillis = 1L,
                kinds = emptyList(),
            )

        assertEquals(
            Outcome.Failure(AppError.NewerSchema(found = 2, supported = 1)),
            BundleZipReader.read(written(bundle)),
        )
    }

    @Test
    fun aBundleWithNoManifestIsCorrupt() {
        assertEquals(Outcome.Failure(AppError.CorruptData), BundleZipReader.read(rawZip(emptyMap())))
    }

    @Test
    fun aKindThisBuildDoesNotKnowIsSkippedWithAWarning() {
        val bundle =
            BackupBundle(
                formatVersion = 1,
                appVersion = "0.1.0",
                createdAtMillis = 1L,
                kinds =
                    listOf(
                        BundleKind("favourites", listOf(BundleEntry("one", objectOf("name" to "Cafe")))),
                        BundleKind("future.kind", listOf(BundleEntry("future", objectOf("value" to "x")))),
                    ),
            )

        val read = read(written(bundle))

        assertEquals(listOf("favourites"), read.kinds.map { kind -> kind.id })
        assertEquals(listOf(BackupWarning.UnknownKind("future.kind")), read.warnings)
    }

    @Test
    fun aDeclaredFileWithAWrongHashIsCorrupt() {
        val index =
            """{"kind":"favourites","entries":[{"id":"one","value":{"name":"Cafe"}}]}"""
                .toByteArray()
        val manifest =
            manifestOf(
                version = 1,
                appVersion = "0.1.0",
                kinds =
                    listOf(
                        kindOf(
                            id = "favourites",
                            count = 1,
                            files = listOf(fileOf("favourites/index.json", index, sha256 = "0".repeat(64))),
                        ),
                    ),
            )

        assertEquals(
            Outcome.Failure(AppError.CorruptData),
            BundleZipReader.read(rawZip(mapOf("manifest.json" to manifest, "favourites/index.json" to index))),
        )
    }

    @Test
    fun aPathThatLeavesItsFolderIsCorrupt() {
        val index =
            """{"kind":"favourites","entries":[{"id":"one","value":{"name":"Cafe"}}]}"""
                .toByteArray()
        val manifest =
            manifestOf(
                version = 1,
                appVersion = "0.1.0",
                kinds =
                    listOf(
                        kindOf(
                            id = "favourites",
                            count = 1,
                            files = listOf(fileOf("favourites/../escape", index)),
                        ),
                    ),
            )

        assertEquals(
            Outcome.Failure(AppError.CorruptData),
            BundleZipReader.read(rawZip(mapOf("manifest.json" to manifest, "favourites/../escape" to index))),
        )
    }

    @Test
    fun aForeignFormatIsUnsupported() {
        val manifest =
            JsonObject(
                mapOf(
                    "format" to JsonPrimitive("other-app"),
                    "formatVersion" to JsonPrimitive(1),
                    "appVersion" to JsonPrimitive("1"),
                    "createdAtMillis" to JsonPrimitive(1),
                    "kinds" to kotlinx.serialization.json.JsonArray(emptyList()),
                ),
            )

        assertEquals(
            Outcome.Failure(AppError.Unsupported),
            BundleZipReader.read(rawZip(mapOf("manifest.json" to manifest.toString().toByteArray()))),
        )
    }

    private fun written(bundle: BackupBundle): ByteArray {
        val output = ByteArrayOutputStream()
        when (val written = BundleZipWriter.write(bundle, output)) {
            is Outcome.Success -> Unit
            is Outcome.Failure -> throw AssertionError("write failed: ${written.error}")
        }
        return output.toByteArray()
    }

    private fun read(bytes: ByteArray): BackupBundle =
        when (val read = BundleZipReader.read(bytes)) {
            is Outcome.Success -> read.value
            is Outcome.Failure -> throw AssertionError("read failed: ${read.error}")
        }

    private fun objectOf(vararg pairs: Pair<String, String>): JsonObject =
        buildJsonObject {
            pairs.forEach { (key, value) -> put(key, value) }
        }

    private fun manifestOf(
        version: Int,
        appVersion: String,
        kinds: List<JsonObject>,
    ): ByteArray =
        buildJsonObject {
            put("format", BACKUP_FORMAT_ID)
            put("formatVersion", version)
            put("appVersion", appVersion)
            put("createdAtMillis", 1L)
            put("kinds", kotlinx.serialization.json.JsonArray(kinds))
        }.toString().toByteArray()

    private fun kindOf(
        id: String,
        count: Int,
        files: List<JsonObject>,
    ): JsonObject =
        buildJsonObject {
            put("kind", id)
            put("folder", id)
            put("count", count)
            put("files", kotlinx.serialization.json.JsonArray(files))
        }

    private fun fileOf(
        path: String,
        bytes: ByteArray,
        sha256: String = sha256Hex(bytes),
    ): JsonObject =
        buildJsonObject {
            put("path", path)
            put("bytes", bytes.size.toLong())
            put("sha256", sha256)
        }

    private fun rawZip(files: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            files.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
