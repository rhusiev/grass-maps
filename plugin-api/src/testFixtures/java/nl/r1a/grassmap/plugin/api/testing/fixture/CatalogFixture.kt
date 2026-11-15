package nl.r1a.grassmap.plugin.api.testing.fixture

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/**
 * The catalogue the T-0501 pipeline wrote, committed as the download fixture.
 *
 * `catalog.json` is copied from the real all-oblast build output unchanged, so
 * the tests read the shape the server really serves, not a hand-written
 * approximation. The variants below add only entries the protocol declares and
 * the T-0501 build does not produce yet: the `world` overview and an
 * `elevation-30m` add-on (T-0502 and T-0509).
 */
object CatalogFixture {
    const val REGION_COUNT: Int = 25
    const val VERSION: String = "2026-11-03"
    const val WORLD_ID: String = "world"
    const val LVIV: String = "ua-46"
    const val VINNYTSIA: String = "ua-05"

    fun path(): Path = TestRegions.repositoryRoot().resolve("test-data/catalog/catalog.json")

    fun bytes(): ByteArray = Files.readAllBytes(path())

    fun text(): String = String(bytes(), Charsets.UTF_8)

    fun json(): JsonObject = Json.parseToJsonElement(text()).jsonObject

    /** The committed catalogue with one `world` entry added, in the declared shape. */
    fun withWorld(): ByteArray =
        withCatalog { root ->
            val regions = root.getValue(REGIONS) as JsonArray
            JsonObject(root + (REGIONS to JsonArray(listOf(worldEntry()) + regions)))
        }

    /**
     * The committed catalogue with the 30 m elevation add-on on [region],
     * exactly as server-protocol.md section 2 declares it.
     */
    fun withElevationAddon(region: String = LVIV): ByteArray = withRegion(region) { entry -> JsonObject(entry + ("addons" to addons())) }

    /** The world entry and the 30 m add-on together, the way a full catalogue reads. */
    fun withWorldAndElevationAddon(region: String = LVIV): ByteArray =
        withCatalog { root ->
            val regions =
                (root.getValue(REGIONS) as JsonArray).map { element ->
                    val entry = element.jsonObject
                    if (entry.getValue("id").jsonPrimitive.content == region) {
                        JsonObject(entry + ("addons" to addons()))
                    } else {
                        entry
                    }
                }
            JsonObject(root + (REGIONS to JsonArray(listOf(worldEntry()) + regions)))
        }

    private fun worldEntry(): JsonObject =
        buildJsonObject {
            put("id", WORLD_ID)
            put(
                "name",
                buildJsonObject {
                    put("en", "World overview")
                    put("uk", "Огляд світу")
                },
            )
            put("kind", "world")
            put(
                "bbox",
                buildJsonArray { listOf(-180.0, -85.0, 180.0, 85.0).forEach { add(JsonPrimitive(it)) } },
            )
            put("outline", "outlines/world.geojson")
            put("version", VERSION)
            put("size_bytes", 74_000_000L)
            put(
                "parts",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("kind", "map")
                            put("version", VERSION)
                            put("size_bytes", 74_000_000L)
                        },
                    )
                },
            )
        }

    private fun addons(): JsonArray =
        buildJsonArray {
            add(
                buildJsonObject {
                    put("id", "elevation-30m")
                    put("version", "2026-01")
                    put("size_bytes", 13_000_000L)
                },
            )
        }

    /** The committed catalogue with the document itself changed. */
    fun withCatalog(transform: (JsonObject) -> JsonObject): ByteArray = transform(json()).toString().toByteArray()

    /** The committed catalogue with one region entry changed. */
    fun withRegion(
        region: String,
        transform: (JsonObject) -> JsonObject,
    ): ByteArray =
        withCatalog { root ->
            val regions =
                (root.getValue(REGIONS) as JsonArray).map { element ->
                    val entry = element.jsonObject
                    if (entry.getValue("id").jsonPrimitive.content == region) transform(entry) else entry
                }
            JsonObject(root + (REGIONS to JsonArray(regions)))
        }

    private const val REGIONS = "regions"
}
