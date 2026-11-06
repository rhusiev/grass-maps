package nl.r1a.grassmap.core.services

import nl.r1a.grassmap.plugin.api.error.Outcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.util.UUID

class ButtonLayoutStoreTest {
    private val directory = File(System.getProperty("java.io.tmpdir"), "button-layout-${UUID.randomUUID()}")
    private val file = File(directory, "button-layout.json")

    @After
    fun removeDirectory() {
        directory.deleteRecursively()
    }

    @Test
    fun missingFileLoadsTheDefaultOrder() {
        val store = ButtonLayoutStore(file)

        assertEquals(MapControlCommands.DEFAULT_ORDER, store.load(ProfileId("default")))
    }

    @Test
    fun savedOrderRoundTrips() {
        val store = ButtonLayoutStore(file)
        val layout = listOf(MapControlCommands.NORTH_UP, MapControlCommands.ZOOM_IN, MapControlCommands.ZOOM_OUT)

        assertEquals(Outcome.Success(Unit), store.save(ProfileId("default"), layout))
        assertEquals(layout, store.load(ProfileId("default")))
    }

    @Test
    fun corruptFileLoadsTheDefaultOrder() {
        directory.mkdirs()
        file.writeText("{ this is not json")
        val store = ButtonLayoutStore(file)

        assertEquals(MapControlCommands.DEFAULT_ORDER, store.load(ProfileId("default")))
    }

    @Test
    fun profilesKeepSeparateOrders() {
        val store = ButtonLayoutStore(file)
        val walking = MapControlCommands.DEFAULT_ORDER
        val cycling = listOf(MapControlCommands.ZOOM_OUT, MapControlCommands.NORTH_UP, MapControlCommands.ZOOM_IN)

        assertEquals(Outcome.Success(Unit), store.save(ProfileId("walking"), walking))
        assertEquals(Outcome.Success(Unit), store.save(ProfileId("cycling"), cycling))

        assertEquals(walking, store.load(ProfileId("walking")))
        assertEquals(cycling, store.load(ProfileId("cycling")))
    }

    @Test
    fun savingOneProfileKeepsTheOthers() {
        val store = ButtonLayoutStore(file)
        val walking = MapControlCommands.DEFAULT_ORDER
        val cycling = listOf(MapControlCommands.NORTH_UP, MapControlCommands.ZOOM_IN, MapControlCommands.ZOOM_OUT)
        store.save(ProfileId("walking"), walking)

        store.save(ProfileId("cycling"), cycling)

        assertEquals(walking, store.load(ProfileId("walking")))
        assertEquals(cycling, store.load(ProfileId("cycling")))
    }

    @Test
    fun anEmptyStoredOrderFallsBackToTheDefault() {
        directory.mkdirs()
        file.writeText("""{"profiles":{"default":[]}}""")
        val store = ButtonLayoutStore(file)

        assertEquals(MapControlCommands.DEFAULT_ORDER, store.load(ProfileId("default")))
    }

    @Test
    fun anUnknownProfileLoadsTheDefaultOrder() {
        val store = ButtonLayoutStore(file)
        store.save(ProfileId("walking"), listOf(MapControlCommands.NORTH_UP))

        assertEquals(MapControlCommands.DEFAULT_ORDER, store.load(ProfileId("cycling")))
    }

    @Test
    fun malformedEntryIsIgnored() {
        directory.mkdirs()
        file.writeText("""{"profiles":{"default":[1,"core.map.north_up"]}}""")
        val store = ButtonLayoutStore(file)

        assertEquals(MapControlCommands.DEFAULT_ORDER, store.load(ProfileId("default")))
    }

    @Test
    fun noTemporaryFileRemainsAfterSave() {
        val store = ButtonLayoutStore(file)

        store.save(ProfileId("default"), listOf(MapControlCommands.NORTH_UP))

        assertFalse(File(directory, "button-layout.json.tmp").exists())
        assertEquals(1, store.load(ProfileId("default")).size)
    }
}
