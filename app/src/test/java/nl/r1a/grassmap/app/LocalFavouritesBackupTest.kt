package nl.r1a.grassmap.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmap.core.CommandDispatchers
import nl.r1a.grassmap.core.services.FavouritesBackupStore
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.service.Clock
import nl.r1a.grassmap.plugin.api.service.Strings
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class LocalFavouritesBackupTest {
    private val root: File = Files.createTempDirectory("grass-favourites-backup").toFile()
    private val clock = MutableClock(Instant.parse("2026-11-01T10:00:00Z").toEpochMilli())

    @After
    fun removeRoot() {
        root.deleteRecursively()
    }

    @Test
    fun everyMutationTypeLeavesAFreshBackup() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val container = container(dispatcher)
            val backup = requireNotNull(container.favouritesBackup)

            val id = text(container.commandBus.invoke(FavouriteCommandIds.ADD, addParams("First")))
            settle()
            assertNewestMatchesLibrary(container, backup)

            assertTrue(container.commandBus.invoke(FavouriteCommandIds.DELETE, idParams(id)) is Outcome.Success)
            settle()
            assertNewestMatchesLibrary(container, backup)

            assertTrue(container.commandBus.invoke(FavouriteCommandIds.UNDO_DELETE, idParams(id)) is Outcome.Success)
            settle()
            assertNewestMatchesLibrary(container, backup)

            val document = File(root, "import.gpx")
            assertTrue(
                container.commandBus.invoke(FavouriteCommandIds.EXPORT, pathParams(document.absolutePath)) is Outcome.Success,
            )
            assertTrue(
                container.commandBus.invoke(FavouriteCommandIds.IMPORT, pathParams(document.absolutePath)) is Outcome.Success,
            )
            settle()
            assertNewestMatchesLibrary(container, backup)

            assertEquals(
                FavouritesBackupStore.DEFAULT_MAX_COPIES_PER_DAY,
                backup.copies().valueOrFail().size,
            )
        }

    @Test
    fun restoreReplacesTheWholeLibraryInsteadOfMerging() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val container = container(dispatcher)
            val backup = requireNotNull(container.favouritesBackup)

            text(container.commandBus.invoke(FavouriteCommandIds.ADD, addParams("First")))
            settle()
            val firstCopy = backup.copies().valueOrFail().first()

            text(container.commandBus.invoke(FavouriteCommandIds.ADD, addParams("Second")))
            settle()
            assertEquals(listOf("First", "Second"), names(container))

            assertEquals(Outcome.Success(Unit), backup.restore(firstCopy.id))

            assertEquals(listOf("First"), names(container))
            val reread = container(dispatcher).favourites.load().valueOrFail()
            assertEquals(listOf("First"), reread.favourites.map { favourite -> favourite.name })
        }

    @Test
    fun restoreOfAMissingOrCorruptCopyLeavesTheLiveLibrary() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val container = container(dispatcher)
            val backup = requireNotNull(container.favouritesBackup)
            text(container.commandBus.invoke(FavouriteCommandIds.ADD, addParams("Live")))
            settle()
            val before = container.favourites.export().valueOrFail()
            val newest = backup.copies().valueOrFail().first()

            assertEquals(Outcome.Failure(AppError.NotFound), backup.restore("favourites-1-9999"))

            File(root, "backups/favourites/${newest.id}.gpx").writeText("damaged after the copy")
            assertEquals(Outcome.Failure(AppError.CorruptData), backup.restore(newest.id))

            assertArrayEquals(before, container.favourites.export().valueOrFail())
        }

    private fun TestScope.settle() {
        clock.advanceSeconds(60)
        advanceTimeBy(BACKUP_DEBOUNCE_MILLIS + 1)
        runCurrent()
    }

    private suspend fun assertNewestMatchesLibrary(
        container: AppContainer,
        backup: LocalFavouritesBackup,
    ) {
        val newest = backup.copies().valueOrFail().first()
        assertArrayEquals(container.favourites.export().valueOrFail(), backup.read(newest.id).valueOrFail())
    }

    private suspend fun names(container: AppContainer): List<String> =
        container.favourites
            .load()
            .valueOrFail()
            .favourites
            .map { favourite -> favourite.name }

    private fun container(
        dispatcher: CoroutineDispatcher,
        root: File? = this.root,
    ): AppContainer =
        AppContainer(
            strings = KeyOnlyStrings,
            dispatchers = CommandDispatchers(main = dispatcher, default = dispatcher, io = dispatcher),
            favouritesRootProvider = { root },
            favouritesBackupScope = CoroutineScope(dispatcher),
            favouritesBackupClock = clock,
            favouritesBackupDebounceMillis = BACKUP_DEBOUNCE_MILLIS,
        )

    private fun text(outcome: Outcome<JsonElement>): String =
        when (outcome) {
            is Outcome.Success -> (outcome.value as JsonPrimitive).content
            is Outcome.Failure -> throw AssertionError("command failed: ${outcome.error}")
        }

    private fun addParams(name: String): JsonObject =
        buildJsonObject {
            put("lat", 49.8419)
            put("lon", 24.0316)
            put("name", name)
            put("group", "Lviv")
            put("color", "#FF0000")
        }

    private fun idParams(id: String): JsonObject = buildJsonObject { put("id", id) }

    private fun pathParams(path: String): JsonObject = buildJsonObject { put("path", path) }

    private fun <T> Outcome<T>.valueOrFail(): T =
        when (this) {
            is Outcome.Success -> value
            is Outcome.Failure -> throw AssertionError("unexpected failure: $error")
        }

    private class MutableClock(
        private var millis: Long,
    ) : Clock {
        override fun nowMillis(): Long = millis

        fun advanceSeconds(seconds: Long) {
            millis += seconds * MILLIS_PER_SECOND
        }
    }

    private companion object {
        const val BACKUP_DEBOUNCE_MILLIS = 1_000L
        const val MILLIS_PER_SECOND = 1_000L
    }

    private object KeyOnlyStrings : Strings {
        override fun resolve(
            key: String,
            arguments: List<String>,
        ): String = key

        override fun contains(key: String): Boolean = false
    }
}
