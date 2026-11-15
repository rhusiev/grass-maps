package nl.r1a.grassmap.regions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.CatalogAddon
import nl.r1a.grassmap.plugin.api.model.CatalogPart
import nl.r1a.grassmap.plugin.api.model.CatalogPartKind
import nl.r1a.grassmap.plugin.api.model.CatalogRegion
import nl.r1a.grassmap.plugin.api.model.CatalogRegionKind
import nl.r1a.grassmap.plugin.api.model.RegionCatalog

/**
 * Reads and validates the server's catalogue (server-protocol.md section 2).
 *
 * The catalogue is untrusted input, so the reader is strict about the shape it
 * relies on: a missing bbox, a region without a name, a repeated region or part
 * kind and a part without a size are all [AppError.CorruptData]. An unknown
 * catalogue `format` is [AppError.Unsupported], because a newer server may mean
 * something else by the fields this build reads.
 *
 * A part kind this build does not name is kept as [CatalogPartKind.OTHER] with
 * its wire name, and an entry whose `kind` is not `region` or `world` is
 * [CatalogRegionKind.OTHER]. Both are additive changes a server may make; the
 * screen still draws the entry and its parts instead of hiding data it was sent.
 */
object RegionCatalogParser {
    const val SUPPORTED_FORMAT: Int = 1

    fun parse(bytes: ByteArray): Outcome<RegionCatalog> {
        val root = parseObject(bytes) ?: return corrupt()
        val format = root.int(FORMAT) ?: return corrupt()
        if (format != SUPPORTED_FORMAT) {
            return Outcome.Failure(AppError.Unsupported)
        }
        val regions = root[REGIONS] as? JsonArray ?: return corrupt()
        if (regions.isEmpty()) {
            return corrupt()
        }
        val parsed = regions.map { parseRegion(it) }
        if (parsed.any { it == null }) {
            return corrupt()
        }
        val entries = parsed.filterNotNull()
        if (entries.map { it.id }.toSet().size != entries.size) {
            return corrupt()
        }
        return Outcome.Success(
            RegionCatalog(
                format = format,
                generated = root.string(GENERATED),
                attribution = parseAttribution(root[ATTRIBUTION]) ?: return corrupt(),
                regions = entries,
            ),
        )
    }

    private fun parseRegion(element: JsonElement): CatalogRegion? {
        val json = element as? JsonObject ?: return null
        val id = json.string(ID) ?: return null
        if (!RegionIdentifiers.isSafeSegment(id)) {
            return null
        }
        val names = json[NAME] as? JsonObject ?: return null
        val nameEn = names.string(NAME_EN)
        val nameUk = names.string(NAME_UK)
        if (nameEn == null && nameUk == null) {
            return null
        }
        val kind = parseKind(json.string(KIND)) ?: return null
        val version = json.string(VERSION) ?: return null
        if (!RegionIdentifiers.isSafeSegment(version)) {
            return null
        }
        val bytes = json.long(SIZE_BYTES) ?: return null
        if (bytes <= 0) {
            return null
        }
        return CatalogRegion(
            id = id,
            nameEn = nameEn,
            nameUk = nameUk,
            kind = kind,
            parent = json.string(PARENT),
            version = version,
            bytes = bytes,
            bbox = parseBbox(json[BBOX]) ?: return null,
            outline = parseOutline(json.string(OUTLINE)) ?: return null,
            parts = parseParts(json[PARTS]) ?: return null,
            addons = parseAddons(json[ADDONS]) ?: return null,
            dataDate = dataDateOf(version),
        )
    }

    private fun parseKind(wire: String?): CatalogRegionKind? =
        when (wire) {
            KIND_REGION -> CatalogRegionKind.REGION
            KIND_WORLD -> CatalogRegionKind.WORLD
            null -> null
            else -> CatalogRegionKind.OTHER
        }

    private fun parseParts(element: JsonElement?): List<CatalogPart>? {
        val array = element as? JsonArray ?: return null
        if (array.isEmpty()) {
            return null
        }
        val parts = array.map { parsePart(it) }
        if (parts.any { it == null }) {
            return null
        }
        val present = parts.filterNotNull()
        if (present.map { it.wireKind }.toSet().size != present.size) {
            return null
        }
        return present
    }

    private fun parsePart(element: JsonElement): CatalogPart? {
        val json = element as? JsonObject ?: return null
        val wireKind = json.string(KIND) ?: return null
        val version = json.string(VERSION) ?: return null
        if (!RegionIdentifiers.isSafeSegment(version)) {
            return null
        }
        val bytes = json.long(SIZE_BYTES) ?: return null
        if (bytes <= 0) {
            return null
        }
        return CatalogPart(
            kind = CatalogPartKind.fromWire(wireKind),
            wireKind = wireKind,
            version = version,
            bytes = bytes,
        )
    }

    private fun parseAddons(element: JsonElement?): List<CatalogAddon>? {
        if (element == null) {
            return emptyList()
        }
        val array = element as? JsonArray ?: return null
        val addons = array.map { parseAddon(it) }
        if (addons.any { it == null }) {
            return null
        }
        val present = addons.filterNotNull()
        if (present.map { it.id }.toSet().size != present.size) {
            return null
        }
        return present
    }

    private fun parseAddon(element: JsonElement): CatalogAddon? {
        val json = element as? JsonObject ?: return null
        val id = json.string(ID) ?: return null
        val version = json.string(VERSION) ?: return null
        if (!RegionIdentifiers.isSafeSegment(version)) {
            return null
        }
        val bytes = json.long(SIZE_BYTES) ?: return null
        if (bytes <= 0) {
            return null
        }
        return CatalogAddon(id = id, version = version, bytes = bytes)
    }

    /** West, south, east, north; an unordered or out-of-range box is not a box. */
    private fun parseBbox(element: JsonElement?): List<Double>? {
        val array = element as? JsonArray ?: return null
        if (array.size != BBOX_COORDINATES) {
            return null
        }
        val values = array.map { (it as? JsonPrimitive)?.doubleOrNull ?: return null }
        if (values.any { !it.isFinite() }) {
            return null
        }
        val west = values[WEST]
        val south = values[SOUTH]
        val east = values[EAST]
        val north = values[NORTH]
        if (west < -MAX_LONGITUDE || east > MAX_LONGITUDE || south < -MAX_LATITUDE || north > MAX_LATITUDE) {
            return null
        }
        if (west > east || south > north) {
            return null
        }
        return values
    }

    /** A relative path the app can join to the server base without escaping it. */
    private fun parseOutline(value: String?): String? {
        val path = value ?: return null
        if (path.isBlank() || path.startsWith("/") || path.contains('\\')) {
            return null
        }
        val segments = path.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
            return null
        }
        return path
    }

    private fun parseAttribution(element: JsonElement?): List<String>? {
        if (element == null) {
            return emptyList()
        }
        val array = element as? JsonArray ?: return null
        val values = array.map { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content }
        if (values.any { it == null }) {
            return null
        }
        return values.filterNotNull()
    }

    /**
     * The data date the row shows: a version names the monthly build, so its
     * leading ISO date is the date of the data inside. A version that does not
     * start with one has no date to show.
     */
    internal fun dataDateOf(version: String): String? {
        if (version.length < ISO_DATE_LENGTH) {
            return null
        }
        val date = version.substring(0, ISO_DATE_LENGTH)
        return date.takeIf { ISO_DATE.matches(it) }
    }

    private fun parseObject(bytes: ByteArray): JsonObject? =
        try {
            Json.parseToJsonElement(String(bytes, Charsets.UTF_8)) as? JsonObject
        } catch (exception: IllegalArgumentException) {
            null
        }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun corrupt(): Outcome<RegionCatalog> = Outcome.Failure(AppError.CorruptData)

    private const val FORMAT = "format"
    private const val GENERATED = "generated"
    private const val ATTRIBUTION = "attribution"
    private const val REGIONS = "regions"
    private const val ID = "id"
    private const val NAME = "name"
    private const val NAME_EN = "en"
    private const val NAME_UK = "uk"
    private const val KIND = "kind"
    private const val PARENT = "parent"
    private const val VERSION = "version"
    private const val SIZE_BYTES = "size_bytes"
    private const val BBOX = "bbox"
    private const val OUTLINE = "outline"
    private const val PARTS = "parts"
    private const val ADDONS = "addons"

    private const val KIND_REGION = "region"
    private const val KIND_WORLD = "world"

    private const val BBOX_COORDINATES = 4
    private const val WEST = 0
    private const val SOUTH = 1
    private const val EAST = 2
    private const val NORTH = 3
    private const val MAX_LATITUDE = 90.0
    private const val MAX_LONGITUDE = 180.0

    private const val ISO_DATE_LENGTH = 10
    private val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")
}
