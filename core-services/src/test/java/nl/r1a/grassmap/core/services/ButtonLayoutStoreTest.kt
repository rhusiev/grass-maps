package nl.r1a.grassmaps.core.services

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import nl.r1a.grassmaps.plugin.api.contract.CommandId
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class ButtonLayoutStoreTest {
    private val directory = File(System.getProperty("java.io.tmpdir"), "button-layout-${UUID.randomUUID()}")
    private val file = File(directory, "button-layout.json")

    @After
    fun removeDirectory() {
        directory.setWritable(true)
        directory.deleteRecursively()
    }

    @Test
    fun missingFileLoadsTheDefaultLayout() {
        val store = ButtonLayoutStore(file)

        assertEquals(MapButtonLayout.DEFAULT, store.load(ProfileId("default")))
    }

    @Test
    fun savedLayoutRoundTrips() {
        val store = ButtonLayoutStore(file)
        val layout =
            MapButtonLayout(
                listOf(
                    MapControlCommands.NORTH_UP,
                    MapControlCommands.ZOOM_IN,
                    MapControlCommands.ZOOM_OUT,
                    MapControlCommands.FOLLOW,
                    MapControlCommands.ROUTE_PLANNER,
                    null,
                ),
            )

        assertEquals(Outcome.Success(Unit), store.save(ProfileId("default"), layout))
        assertEquals(layout, store.load(ProfileId("default")))
    }

    @Test
    fun aLayoutWithARemovedButtonKeepsTheRestAndAFreeSlot() {
        val store = ButtonLayoutStore(file)
        val layout = MapButtonLayout.DEFAULT.remove(MapControlCommands.NORTH_UP)

        assertEquals(Outcome.Success(Unit), store.save(ProfileId("default"), layout))
        assertEquals(layout, store.load(ProfileId("default")))
    }

    @Test
    fun theQuickAccessBindingAndTheArcRoundTrip() {
        val store = ButtonLayoutStore(file)
        val parameters = JsonObject(mapOf("point" to JsonPrimitive("home")))
        val layout =
            MapButtonLayout.DEFAULT.copy(
                quickAccess = QuickAccessButton(MapControlCommands.ROUTE_PLANNER, parameters),
                arc = true,
            )

        assertEquals(Outcome.Success(Unit), store.save(ProfileId("default"), layout))
        assertEquals(layout, store.load(ProfileId("default")))
    }

    @Test
    fun anOrderOnlyFileMigratesToTheFirstFiveSlots() {
        directory.mkdirs()
        file.writeText(
            """{"profiles":{"default":["core.map.zoom_in","core.map.zoom_out","core.map.north_up","core.map.follow","route.planner.begin"]}}""",
        )
        val store = ButtonLayoutStore(file)

        assertEquals(MapButtonLayout.DEFAULT, store.load(ProfileId("default")))
    }

    @Test
    fun anOrderOnlyPermutationMigratesInItsOrder() {
        directory.mkdirs()
        file.writeText(
            """{"profiles":{"default":["core.map.follow","core.map.north_up","core.map.zoom_out","core.map.zoom_in","route.planner.begin"]}}""",
        )
        val store = ButtonLayoutStore(file)
        val migrated =
            MapButtonLayout(
                listOf(
                    MapControlCommands.FOLLOW,
                    MapControlCommands.NORTH_UP,
                    MapControlCommands.ZOOM_OUT,
                    MapControlCommands.ZOOM_IN,
                    MapControlCommands.ROUTE_PLANNER,
                    null,
                ),
            )

        assertEquals(migrated, store.load(ProfileId("default")))
    }

    @Test
    fun aThreeButtonStoredLayoutFallsBackToTheDefault() {
        directory.mkdirs()
        file.writeText(
            """{"profiles":{"default":["core.map.zoom_in","core.map.zoom_out","core.map.north_up"]}}""",
        )
        val store = ButtonLayoutStore(file)

        assertEquals(MapButtonLayout.DEFAULT, store.load(ProfileId("default")))
        assertEquals(MapButtonLayout.SLOT_COUNT, MapButtonLayout.DEFAULT.slots.size)
    }

    @Test
    fun corruptFileLoadsTheDefaultLayout() {
        directory.mkdirs()
        file.writeText("{ this is not json")
        val store = ButtonLayoutStore(file)

        assertEquals(MapButtonLayout.DEFAULT, store.load(ProfileId("default")))
    }

    @Test
    fun aNewerSchemaReadsAsTheDefaultAndIsNotOverwrittenByASave() {
        directory.mkdirs()
        val document = """{"version":3,"profiles":{"default":{"slots":[]}}}"""
        file.writeText(document)
        val store = ButtonLayoutStore(file)

        assertEquals(MapButtonLayout.DEFAULT, store.load(ProfileId("default")))
        val result = store.save(ProfileId("default"), MapButtonLayout.DEFAULT)

        assertTrue(result is Outcome.Failure)
        assertEquals(document, file.readText())
    }

    @Test
    fun profilesKeepSeparateLayouts() {
        val store = ButtonLayoutStore(file)
        val walking = MapButtonLayout.DEFAULT
        val cycling = MapButtonLayout.DEFAULT.remove(MapControlCommands.ZOOM_OUT).place(MapControlCommands.SAVED)

        assertEquals(Outcome.Success(Unit), store.save(ProfileId("walking"), walking))
        assertEquals(Outcome.Success(Unit), store.save(ProfileId("cycling"), cycling))

        assertEquals(walking, store.load(ProfileId("walking")))
        assertEquals(cycling, store.load(ProfileId("cycling")))
    }

    @Test
    fun savingOneProfileKeepsTheOthers() {
        val store = ButtonLayoutStore(file)
        val walking = MapButtonLayout.DEFAULT.place(MapControlCommands.TRACKS)
        val cycling = MapButtonLayout.DEFAULT.remove(MapControlCommands.FOLLOW)
        store.save(ProfileId("walking"), walking)

        store.save(ProfileId("cycling"), cycling)

        assertEquals(walking, store.load(ProfileId("walking")))
        assertEquals(cycling, store.load(ProfileId("cycling")))
    }

    @Test
    fun anUnknownProfileLoadsTheDefaultLayout() {
        val store = ButtonLayoutStore(file)
        store.save(ProfileId("walking"), MapButtonLayout.DEFAULT)

        assertEquals(MapButtonLayout.DEFAULT, store.load(ProfileId("cycling")))
    }

    @Test
    fun aStoredLayoutWithAnUnknownIdFallsBackToTheDefault() {
        directory.mkdirs()
        file.writeText(
            """{"version":2,"profiles":{"default":{"slots":["core.map.zoom_in","core.map.unknown",null,null,null,null]}}}""",
        )
        val store = ButtonLayoutStore(file)

        assertEquals(MapButtonLayout.DEFAULT, store.load(ProfileId("default")))
    }

    @Test
    fun aStoredLayoutWithADuplicateFallsBackToTheDefault() {
        directory.mkdirs()
        file.writeText(
            """{"version":2,"profiles":{"default":{"slots":["core.map.zoom_in","core.map.zoom_in",null,null,null,null]}}}""",
        )
        val store = ButtonLayoutStore(file)

        assertEquals(MapButtonLayout.DEFAULT, store.load(ProfileId("default")))
    }

    @Test
    fun aStoredLayoutWithFreeSlotsRoundTrips() {
        directory.mkdirs()
        file.writeText(
            """{"version":2,"profiles":{"default":{"slots":["core.map.zoom_in",null,"core.map.north_up",null,"app.map.tracks",null],"arc":true}}}""",
        )
        val store = ButtonLayoutStore(file)
        val expected =
            MapButtonLayout(
                listOf(
                    MapControlCommands.ZOOM_IN,
                    null,
                    MapControlCommands.NORTH_UP,
                    null,
                    MapControlCommands.TRACKS,
                    null,
                ),
                arc = true,
            )

        assertEquals(expected, store.load(ProfileId("default")))
    }

    @Test
    fun aStoredLayoutWithTheWrongSlotCountFallsBackToTheDefault() {
        directory.mkdirs()
        file.writeText(
            """{"version":2,"profiles":{"default":{"slots":["core.map.zoom_in",null]}}}""",
        )
        val store = ButtonLayoutStore(file)

        assertEquals(MapButtonLayout.DEFAULT, store.load(ProfileId("default")))
    }

    @Test
    fun invalidLayoutsAreRejectedOnSave() {
        val store = ButtonLayoutStore(file)
        store.save(ProfileId("default"), MapButtonLayout.DEFAULT)
        val invalid =
            listOf(
                MapButtonLayout(emptyList()),
                MapButtonLayout(
                    List(MapButtonLayout.SLOT_COUNT) { index ->
                        if (index < 2) MapControlCommands.ZOOM_IN else null
                    },
                ),
                MapButtonLayout(
                    List(MapButtonLayout.SLOT_COUNT) { index ->
                        if (index == 0) CommandId("core.map.other") else null
                    },
                ),
                MapButtonLayout.DEFAULT.copy(quickAccess = QuickAccessButton(CommandId("core.map.other"))),
            )

        for (layout in invalid) {
            assertEquals(Outcome.Failure(AppError.InvalidInput), store.save(ProfileId("default"), layout))
        }
        assertEquals(MapButtonLayout.DEFAULT, store.load(ProfileId("default")))
    }

    @Test
    fun aFailedWriteLeavesThePreviousLayoutReadable() {
        val store = ButtonLayoutStore(file)
        val saved = MapButtonLayout.DEFAULT.remove(MapControlCommands.NORTH_UP)
        assertEquals(Outcome.Success(Unit), store.save(ProfileId("default"), saved))
        val temporary = File(directory, "${file.name}.tmp")
        assertTrue(temporary.mkdirs())

        val result = store.save(ProfileId("default"), MapButtonLayout.DEFAULT)

        assertTrue(result is Outcome.Failure && result.error is AppError.Internal)
        assertEquals(saved, store.load(ProfileId("default")))
    }

    @Test
    fun aTargetThatIsADirectoryFailsWithoutThrowing() {
        assertTrue(file.mkdirs())
        val store = ButtonLayoutStore(file)

        val result = store.save(ProfileId("default"), MapButtonLayout.DEFAULT)

        assertTrue(result is Outcome.Failure)
        assertEquals(MapButtonLayout.DEFAULT, store.load(ProfileId("default")))
    }

    @Test
    fun noTemporaryFileRemainsAfterSave() {
        val store = ButtonLayoutStore(file)

        store.save(ProfileId("default"), MapButtonLayout.DEFAULT)

        assertFalse(File(directory, "button-layout.json.tmp").exists())
        assertEquals(MapButtonLayout.DEFAULT, store.load(ProfileId("default")))
    }
}
