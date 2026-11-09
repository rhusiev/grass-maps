package nl.r1a.grassmap.plugins.osm.places

import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.OsmType
import nl.r1a.grassmap.plugin.api.provider.Place
import nl.r1a.grassmap.plugin.api.provider.PlaceDetails
import java.io.File

/**
 * The authored fixture places the committed `fixture-mini` search section is
 * built from (`tools/pipeline/fixture-places.tsv`).
 *
 * `RustPlaceProviderFixtureTest` asserts that the built section carries exactly
 * this cafe tag map, so this reader and the places test share one source of
 * truth for the fixture's tags.
 */
internal object FixturePlaces {
    data class Row(
        val osmType: String,
        val osmId: Long,
        val latitude: Double,
        val longitude: Double,
        val name: String,
        val kind: String,
        val context: String,
        val tags: Map<String, String>,
    ) {
        fun details(): PlaceDetails =
            PlaceDetails(
                place =
                    Place(
                        id = "fixture-mini-2026-11-03-1:0",
                        name = name,
                        kind = kind,
                        position = GeoPoint(latitude, longitude),
                        address = context.trim().ifEmpty { null },
                        regionBuild = "fixture-mini-2026-11-03-1",
                    ),
                osmType = osmTypeOf(osmType),
                osmId = osmId,
                tags = tags,
            )
    }

    val rows: List<Row> by lazy {
        fixtureFile()
            .readLines()
            .filterNot { it.isBlank() || it.startsWith("#") }
            .map(::parse)
    }

    fun byName(name: String): Row = rows.first { it.name == name }

    private fun parse(line: String): Row {
        val columns = line.split('\t')
        require(columns.size == COLUMN_COUNT) { "fixture row has ${columns.size} columns: $line" }
        return Row(
            osmType = columns[0],
            osmId = columns[1].toLong(),
            latitude = columns[2].toDouble(),
            longitude = columns[3].toDouble(),
            name = columns[4],
            kind = columns[5],
            context = columns[6],
            tags = parseTags(columns[7]),
        )
    }

    private fun parseTags(column: String): Map<String, String> =
        column
            .split('|')
            .filter { it.isNotBlank() }
            .associate { pair ->
                val separator = pair.indexOf('=')
                require(separator > 0) { "fixture tag has no '=': $pair" }
                pair.substring(0, separator) to pair.substring(separator + 1)
            }

    private fun osmTypeOf(value: String): OsmType =
        when (value) {
            "node" -> OsmType.NODE
            "way" -> OsmType.WAY
            "relation" -> OsmType.RELATION
            else -> error("unknown fixture OSM type '$value'")
        }

    private fun fixtureFile(): File {
        val path = "tools/pipeline/fixture-places.tsv"
        var directory: File? = File(System.getProperty("user.dir")).absoluteFile
        while (directory != null) {
            val candidate = File(directory, path)
            if (File(directory, "settings.gradle.kts").isFile && candidate.isFile) {
                return candidate
            }
            directory = directory.parentFile
        }
        error("Cannot find $path above ${System.getProperty("user.dir")}")
    }

    private const val COLUMN_COUNT = 8
}
