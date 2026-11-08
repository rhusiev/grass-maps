package nl.r1a.grassmap.regions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome

/**
 * Reads and validates the package manifest. The reader is deliberately strict:
 * an unknown format is [AppError.Unsupported], and anything that does not match
 * the requested region/version or the schema is [AppError.CorruptData]. A
 * package is untrusted input, so a value that looks plausible but is not
 * declared is not accepted.
 */
object RegionManifestParser {
    fun parse(
        bytes: ByteArray,
        expectedRegion: String,
        expectedVersion: String,
    ): Outcome<RegionManifest> {
        val root = parseObject(bytes) ?: return Outcome.Failure(AppError.CorruptData)
        val region = root.string("region") ?: return corrupt()
        val version = root.string("version") ?: return corrupt()
        if (!RegionIdentifiers.isSafeSegment(region) || !RegionIdentifiers.isSafeSegment(version)) {
            return corrupt()
        }
        if (region != expectedRegion || version != expectedVersion) {
            return corrupt()
        }
        val packageFormat = root.int("packageFormat") ?: return corrupt()
        if (packageFormat != RegionIdentifiers.SUPPORTED_PACKAGE_FORMAT) {
            return Outcome.Failure(AppError.Unsupported)
        }
        val osmTimestamp = root.string("osmTimestamp") ?: return corrupt()
        val pipeline =
            if (root.containsKey("pipeline")) {
                parsePipeline(root.getValue("pipeline")) ?: return corrupt()
            } else {
                null
            }
        val components = parseComponents(root["components"]) ?: return corrupt()
        return Outcome.Success(
            RegionManifest(
                region = region,
                version = version,
                packageFormat = packageFormat,
                osmTimestamp = osmTimestamp,
                pipeline = pipeline,
                components = components,
            ),
        )
    }

    private fun parseObject(bytes: ByteArray): JsonObject? =
        try {
            Json.parseToJsonElement(String(bytes, Charsets.UTF_8)) as? JsonObject
        } catch (exception: IllegalArgumentException) {
            null
        }

    private fun parseComponents(element: JsonElement?): List<RegionComponent>? {
        val array = element as? JsonArray ?: return null
        if (array.isEmpty()) {
            return null
        }
        val components = array.map { parseComponent(it) }
        if (components.any { it == null }) {
            return null
        }
        val present = components.filterNotNull()
        if (present.map { it.kind }.toSet().size != present.size) {
            return null
        }
        if (present.map { it.file }.toSet().size != present.size) {
            return null
        }
        // The reader refuses a search section without its feature component, so
        // a package that omits it must not install and then fail search and
        // details for a region the app reports as installed.
        val features = present.filter { it.kind == FEATURES_KIND }
        if (features.size != 1) {
            return null
        }
        val required = features.single()
        if (required.format != FEATURES_FORMAT || required.formatVersion != FEATURES_FORMAT_VERSION) {
            return null
        }
        return present
    }

    private fun parseComponent(element: JsonElement): RegionComponent? {
        val json = element as? JsonObject ?: return null
        val kind = json.string("kind") ?: return null
        val file = json.string("file") ?: return null
        if (!RegionIdentifiers.isSafeFileName(file)) {
            return null
        }
        val format = json.string("format") ?: return null
        val formatVersion = json.int("formatVersion") ?: return null
        if (formatVersion < 1) {
            return null
        }
        val version = json.string("version") ?: return null
        val bytes = json.long("bytes") ?: return null
        if (bytes <= 0) {
            return null
        }
        val sha256 = json.string("sha256") ?: return null
        if (!RegionIdentifiers.isSha256(sha256)) {
            return null
        }
        return RegionComponent(
            kind = kind,
            file = file,
            format = format,
            formatVersion = formatVersion,
            version = version,
            bytes = bytes,
            sha256 = sha256,
            standIn = json.boolean("standIn") ?: false,
            zoomLevels = parseZoomLevels(json["zoomLevels"]) ?: return null,
        )
    }

    private fun parseZoomLevels(element: JsonElement?): List<Int>? {
        if (element == null) {
            return emptyList()
        }
        val array = element as? JsonArray ?: return null
        if (array.size != RegionIdentifiers.ZOOM_LEVEL_COUNT) {
            return null
        }
        val levels = array.map { (it as? JsonPrimitive)?.intOrNull ?: return null }
        if (levels[0] > levels[1]) {
            return null
        }
        return levels
    }

    private fun parsePipeline(element: JsonElement): PackagePipeline? {
        val json = element as? JsonObject ?: return null
        val bbox = parseBbox(json["bbox"]) ?: return null
        val sources =
            listOfNotNull(
                parseSource("mapSource", json["mapSource"]),
                parseSource("contoursSource", json["contoursSource"]),
            )
        return PackagePipeline(bbox = bbox, sources = sources)
    }

    private fun parseBbox(element: JsonElement?): List<Double>? {
        if (element == null) {
            return emptyList()
        }
        val array = element as? JsonArray ?: return null
        if (array.size != RegionIdentifiers.BBOX_COORDINATES) {
            return null
        }
        return array.map { (it as? JsonPrimitive)?.doubleOrNull ?: return null }
    }

    private fun parseSource(
        role: String,
        element: JsonElement?,
    ): SourceArchive? {
        if (element == null) {
            return null
        }
        val json = element as? JsonObject ?: return null
        val archive = json.string("archive") ?: return null
        val sha256 = json.string("sha256") ?: return null
        if (!RegionIdentifiers.isSha256(sha256)) {
            return null
        }
        return SourceArchive(role = role, archive = archive, sha256 = sha256)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun corrupt(): Outcome<RegionManifest> = Outcome.Failure(AppError.CorruptData)

    private const val FEATURES_KIND = "features"
    private const val FEATURES_FORMAT = "place-features"
    private const val FEATURES_FORMAT_VERSION = 1
}
