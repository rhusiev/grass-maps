package nl.r1a.grassmap.core.services

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class LocationPermissionStoreTest {
    private val root: Path = Files.createTempDirectory("location-permission")

    @After
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun aMissingFileReadsAsNotAsked() {
        val store = FileLocationPermissionStore(root.resolve("location/permission-asked").toFile())

        assertFalse(store.hasAsked())
    }

    @Test
    fun theFlagSurvivesANewStore() {
        val file = root.resolve("location/permission-asked").toFile()
        val store = FileLocationPermissionStore(file)

        assertEquals(Outcome.Success(Unit), store.markAsked())

        assertTrue(file.isFile)
        assertTrue(FileLocationPermissionStore(file).hasAsked())
    }

    @Test
    fun aFailedWriteIsAnOutcomeAndKeepsTheOldAnswer() {
        val parent = root.resolve("blocked").toFile()
        assertTrue(parent.createNewFile())
        val store = FileLocationPermissionStore(parent.resolve("permission-asked"))

        val result = store.markAsked()

        assertTrue(result is Outcome.Failure)
        assertTrue((result as Outcome.Failure).error is AppError.Internal)
        assertFalse(store.hasAsked())
    }

    @Test
    fun theInMemoryStoreRemembersTheAnswer() {
        val store = InMemoryLocationPermissionStore()

        assertFalse(store.hasAsked())

        assertEquals(Outcome.Success(Unit), store.markAsked())

        assertTrue(store.hasAsked())
    }
}
