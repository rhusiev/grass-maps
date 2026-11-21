package nl.r1a.grassmaps.places

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmaps.plugin.api.error.AppError
import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.error.map
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.UUID

/** A rename changes only the name and survives a reload (T-0215). */
class FavouritesRenameTest {
    private val directory = File(System.getProperty("java.io.tmpdir"), "favourites-rename-${UUID.randomUUID()}")
    private val file = File(directory, "favourites/favourites.gpx")

    @After
    fun removeDirectory() {
        directory.deleteRecursively()
    }

    @Test
    fun aRenameChangesTheNameAndKeepsThePointValues() =
        runTest {
            val store = FavouritesStore(file)
            val original = favourite(49.84, 24.03, "Old name", group = "Lviv", icon = "cafe")
            assertEquals(Outcome.Success(original), store.add(original))

            val renamed =
                when (val outcome = store.rename(original.id, "New name")) {
                    is Outcome.Success -> outcome.value
                    is Outcome.Failure -> throw AssertionError("rename failed: ${outcome.error}")
                }

            assertEquals("New name", renamed.name)
            assertEquals(original.latitude, renamed.latitude, 0.0001)
            assertEquals(original.longitude, renamed.longitude, 0.0001)
            assertEquals(original.group, renamed.group)
            assertEquals(original.icon, renamed.icon)
            assertEquals(Outcome.Success("New name"), store.load().map { snapshot -> snapshot.favourites.single().name })
        }

    @Test
    fun aBlankNameAndAMissingPointAreRefused() =
        runTest {
            val store = FavouritesStore(file)
            assertEquals(Outcome.Failure(AppError.InvalidInput), store.rename(FavouriteId("missing"), "  "))
            assertEquals(Outcome.Failure(AppError.NotFound), store.rename(FavouriteId("missing"), "Name"))
        }
}
