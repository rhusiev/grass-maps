package nl.r1a.grassmap.plugins.valhalla

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The pack the host mounted (T-0301): what is missing, and where the paths point. */
class ValhallaPackTest {
    @Test
    fun aMissingDirectoryIsAbsent() {
        val pack = File(directory(), "valhalla")

        val status = ValhallaPack.inspect(pack.absolutePath)

        assertEquals(ValhallaPackProblem.Absent, (status as ValhallaPackStatus.NotReady).problem)
        assertTrue(status.detail.contains(pack.absolutePath))
    }

    @Test
    fun anEmptyDirectoryIsAbsent() {
        val pack = directory()

        val status = ValhallaPack.inspect(pack.absolutePath)

        assertEquals(ValhallaPackProblem.Absent, (status as ValhallaPackStatus.NotReady).problem)
        assertTrue(status.detail.contains("empty"))
    }

    @Test
    fun aPackWithoutAConfigIsIncomplete() {
        val pack = directory()
        write(pack, ValhallaPack.ADMINS_FILE)
        write(pack, ValhallaPack.TIMEZONES_FILE)
        write(pack, ValhallaPack.TILES_ARCHIVE)

        val status = ValhallaPack.inspect(pack.absolutePath)

        assertEquals(ValhallaPackProblem.Incomplete, (status as ValhallaPackStatus.NotReady).problem)
        assertTrue(status.detail.contains(ValhallaPack.CONFIG_FILE))
    }

    @Test
    fun aPackWithoutTilesIsIncomplete() {
        val pack = directory()
        writeConfig(pack)
        write(pack, ValhallaPack.ADMINS_FILE)
        write(pack, ValhallaPack.TIMEZONES_FILE)

        val status = ValhallaPack.inspect(pack.absolutePath)

        assertEquals(ValhallaPackProblem.Incomplete, (status as ValhallaPackStatus.NotReady).problem)
        assertTrue(status.detail.contains(ValhallaPack.TILES_ARCHIVE))
    }

    @Test
    fun anUnreadableConfigIsUnusable() {
        val pack = directory()
        File(pack, ValhallaPack.CONFIG_FILE).writeText("{ not json")
        write(pack, ValhallaPack.ADMINS_FILE)
        write(pack, ValhallaPack.TIMEZONES_FILE)
        write(pack, ValhallaPack.TILES_ARCHIVE)

        val status = ValhallaPack.inspect(pack.absolutePath)

        assertEquals(ValhallaPackProblem.Unusable, (status as ValhallaPackStatus.NotReady).problem)
    }

    @Test
    fun aTarExtractMakesThePackReady() {
        val pack = directory()
        writeConfig(pack)
        write(pack, ValhallaPack.ADMINS_FILE)
        write(pack, ValhallaPack.TIMEZONES_FILE)
        write(pack, ValhallaPack.TILES_ARCHIVE)

        val status = ValhallaPack.inspect(pack.absolutePath) as ValhallaPackStatus.Ready

        assertEquals(File(pack, ValhallaPack.TILES_ARCHIVE), (status.tiles as ValhallaTiles.Extract).archive)
    }

    @Test
    fun aTileDirectoryMakesThePackReady() {
        val pack = directory()
        writeConfig(pack)
        write(pack, ValhallaPack.ADMINS_FILE)
        write(pack, ValhallaPack.TIMEZONES_FILE)
        write(File(pack, "${ValhallaPack.TILES_DIRECTORY}/2/000/041"), "739.gph")

        val status = ValhallaPack.inspect(pack.absolutePath) as ValhallaPackStatus.Ready

        assertEquals(File(pack, ValhallaPack.TILES_DIRECTORY), (status.tiles as ValhallaTiles.Directory).directory)
    }

    @Test
    fun theLocalConfigReplacesEveryForeignAbsolutePath() {
        val pack = directory()
        writeConfig(pack)
        write(pack, ValhallaPack.ADMINS_FILE)
        write(pack, ValhallaPack.TIMEZONES_FILE)
        write(pack, ValhallaPack.TILES_ARCHIVE)
        val configDirectory = directory()

        val written =
            ValhallaPackConfig.write(
                ValhallaPack.inspect(pack.absolutePath) as ValhallaPackStatus.Ready,
                configDirectory,
            )
        val mjolnir =
            Json
                .parseToJsonElement(written.readText())
                .jsonObject
                .getValue("mjolnir")
                .jsonObject

        assertEquals(File(pack, ValhallaPack.TILES_ARCHIVE).absolutePath, mjolnir.path("tile_extract"))
        assertEquals("", mjolnir.path("tile_dir"))
        assertEquals(File(pack, ValhallaPack.ADMINS_FILE).absolutePath, mjolnir.path("admin"))
        assertEquals(File(pack, ValhallaPack.TIMEZONES_FILE).absolutePath, mjolnir.path("timezone"))
        assertEquals("", mjolnir.path("landmarks"))
        assertEquals("", mjolnir.path("traffic_extract"))
        assertEquals("", mjolnir.path("transit_dir"))
        assertEquals("", mjolnir.path("transit_feeds_dir"))
        assertEquals(
            "",
            Json
                .parseToJsonElement(written.readText())
                .jsonObject
                .getValue("additional_data")
                .jsonObject
                .path("elevation"),
        )
    }

    @Test
    fun aTileDirectoryConfigClearsTheForeignTarPath() {
        val pack = directory()
        writeConfig(pack)
        write(pack, ValhallaPack.ADMINS_FILE)
        write(pack, ValhallaPack.TIMEZONES_FILE)
        write(File(pack, "${ValhallaPack.TILES_DIRECTORY}/2/000/041"), "739.gph")

        val written =
            ValhallaPackConfig.write(
                ValhallaPack.inspect(pack.absolutePath) as ValhallaPackStatus.Ready,
                directory(),
            )
        val mjolnir =
            Json
                .parseToJsonElement(written.readText())
                .jsonObject
                .getValue("mjolnir")
                .jsonObject

        assertEquals("", mjolnir.path("tile_extract"))
        assertEquals(File(pack, ValhallaPack.TILES_DIRECTORY).absolutePath, mjolnir.path("tile_dir"))
    }

    @Test
    fun optionalEntriesThePackShipsAreKeptAndTheRestOfTheConfigSurvives() {
        val pack = directory()
        writeConfig(pack)
        write(pack, ValhallaPack.ADMINS_FILE)
        write(pack, ValhallaPack.TIMEZONES_FILE)
        write(pack, ValhallaPack.TILES_ARCHIVE)
        write(pack, "landmarks.sqlite")
        write(pack, "traffic.tar")
        write(File(pack, "transit"), "tiles.tar")
        write(File(pack, "elevation"), "elevation.tif")

        val written =
            ValhallaPackConfig.write(
                ValhallaPack.inspect(pack.absolutePath) as ValhallaPackStatus.Ready,
                directory(),
            )
        val root = Json.parseToJsonElement(written.readText()).jsonObject
        val mjolnir = root.getValue("mjolnir").jsonObject

        assertEquals(File(pack, "landmarks.sqlite").absolutePath, mjolnir.path("landmarks"))
        assertEquals(File(pack, "traffic.tar").absolutePath, mjolnir.path("traffic_extract"))
        assertEquals(File(pack, "transit").absolutePath, mjolnir.path("transit_dir"))
        assertEquals(File(pack, "elevation").absolutePath, root.getValue("additional_data").jsonObject.path("elevation"))
        assertEquals(
            "trace_route",
            root
                .getValue("loki")
                .jsonObject
                .getValue("actions")
                .jsonArray
                .single()
                .jsonPrimitive
                .content,
        )
    }

    private fun JsonObject.path(key: String): String = getValue(key).jsonPrimitive.content

    private fun writeConfig(directory: File) {
        File(directory, ValhallaPack.CONFIG_FILE).writeText(FOREIGN_CONFIG)
    }

    private fun write(
        directory: File,
        name: String,
        content: String = "",
    ) {
        val file = File(directory, name)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    private fun directory(): File =
        Files.createTempDirectory("valhalla-pack").toFile().also {
            it.deleteOnExit()
            check(it.isDirectory)
        }

    private companion object {
        /** A config built on another machine, with every path absolute. */
        const val FOREIGN_CONFIG =
            """
            {
              "mjolnir": {
                "tile_extract": "/data/valhalla/tiles.tar",
                "tile_dir": "/data/valhalla/tiles",
                "admin": "/data/valhalla/admins.sqlite",
                "timezone": "/data/valhalla/timezones.sqlite",
                "landmarks": "/data/valhalla/landmarks.sqlite",
                "traffic_extract": "/data/valhalla/traffic.tar",
                "transit_dir": "/data/valhalla/transit",
                "transit_feeds_dir": "/data/valhalla/transit_feeds",
                "max_cache_size": 1000000000
              },
              "additional_data": { "elevation": "/data/valhalla/elevation/" },
              "loki": { "actions": ["trace_route"] }
            }
            """
    }
}
