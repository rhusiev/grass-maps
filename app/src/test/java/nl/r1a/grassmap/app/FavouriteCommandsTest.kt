package nl.r1a.grassmap.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmap.core.CommandDispatchers
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.service.Strings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FavouriteCommandsTest {
    private val root: File = Files.createTempDirectory("grass-favourites-command").toFile()

    @After
    fun removeRoot() {
        root.deleteRecursively()
    }

    @Test
    fun everyFavouriteCommandIsRegisteredAndNoneIsUndoable() =
        runTest {
            val container = container(StandardTestDispatcher(testScheduler))

            val entries = container.controlCommands.filter { it.id in FavouriteCommandIds.ALL }

            assertEquals(FavouriteCommandIds.ALL.toSet(), entries.map { it.id }.toSet())
            assertTrue(
                "favourites commands must not use the T-0304 undo stack",
                entries.none { it.undoable },
            )
        }

    @Test
    fun addDeleteAndUndoWorkThroughTheBus() =
        runTest {
            val container = container(StandardTestDispatcher(testScheduler))

            val id = text(container.commandBus.invoke(FavouriteCommandIds.ADD, addParams("Rynok Square")))
            assertTrue(id.isNotEmpty())

            assertEquals(
                id,
                text(container.commandBus.invoke(FavouriteCommandIds.DELETE, idParams(id))),
            )
            assertEquals(
                id,
                text(container.commandBus.invoke(FavouriteCommandIds.UNDO_DELETE, idParams(id))),
            )
        }

    @Test
    fun undoOfNothingIsATypedNoOpOnTheBus() =
        runTest {
            val container = container(StandardTestDispatcher(testScheduler))

            assertEquals(
                Outcome.Failure(AppError.NotFound),
                container.commandBus.invoke(FavouriteCommandIds.UNDO_DELETE, idParams("no-such-deletion")),
            )
            assertEquals(
                Outcome.Failure(AppError.NotFound),
                container.commandBus.invoke(FavouriteCommandIds.DELETE, idParams("no-such-favourite")),
            )
        }

    @Test
    fun anExportCanBeImportedBackThroughTheBus() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val container = container(dispatcher)
            container.commandBus.invoke(FavouriteCommandIds.ADD, addParams("Rynok Square"))
            val directory = File(root, "export")
            assertTrue(directory.mkdirs())
            val exported = File(directory, "favourites.gpx")

            assertTrue(
                text(container.commandBus.invoke(FavouriteCommandIds.EXPORT, pathParams(exported.absolutePath)))
                    .startsWith("exported="),
            )
            assertTrue(exported.readText().contains("<gpx"))

            val secondRoot = File(root, "second")
            val second = container(dispatcher, secondRoot)
            assertEquals(
                Outcome.Success(JsonPrimitive("imported=1 skipped=0")),
                second.commandBus.invoke(FavouriteCommandIds.IMPORT, pathParams(exported.absolutePath)),
            )
            val reExported = File(directory, "favourites-second.gpx")
            second.commandBus.invoke(FavouriteCommandIds.EXPORT, pathParams(reExported.absolutePath))

            assertEquals(exported.readText(), reExported.readText())
        }

    @Test
    fun malformedParametersAreInvalidInput() =
        runTest {
            val container = container(StandardTestDispatcher(testScheduler))

            assertEquals(
                Outcome.Failure(AppError.InvalidInput),
                container.commandBus.invoke(FavouriteCommandIds.ADD, JsonObject(emptyMap())),
            )
            assertEquals(
                Outcome.Failure(AppError.InvalidInput),
                container.commandBus.invoke(FavouriteCommandIds.ADD, addParams("X", shape = "triangle")),
            )
            assertEquals(
                Outcome.Failure(AppError.InvalidInput),
                container.commandBus.invoke(FavouriteCommandIds.DELETE, JsonObject(emptyMap())),
            )
            assertEquals(
                Outcome.Failure(AppError.InvalidInput),
                container.commandBus.invoke(FavouriteCommandIds.IMPORT, JsonObject(emptyMap())),
            )
            assertEquals(
                Outcome.Failure(AppError.InvalidInput),
                container.commandBus.invoke(FavouriteCommandIds.EXPORT, JsonPrimitive("relative/path.gpx")),
            )
        }

    @Test
    fun aBuildWithoutAFilesRootIsNotReady() =
        runTest {
            val container = container(StandardTestDispatcher(testScheduler), root = null)

            assertEquals(
                Outcome.Failure(AppError.NotReady),
                container.commandBus.invoke(FavouriteCommandIds.ADD, addParams("Nowhere")),
            )
        }

    private fun container(
        dispatcher: CoroutineDispatcher,
        root: File? = this.root,
    ): AppContainer =
        AppContainer(
            strings = KeyOnlyStrings,
            dispatchers = CommandDispatchers(main = dispatcher, default = dispatcher, io = dispatcher),
            favouritesRootProvider = { root },
        )

    private fun text(outcome: Outcome<JsonElement>): String =
        when (outcome) {
            is Outcome.Success -> (outcome.value as JsonPrimitive).content
            is Outcome.Failure -> throw AssertionError("command failed: ${outcome.error}")
        }

    private fun addParams(
        name: String,
        shape: String? = null,
    ): JsonObject =
        buildJsonObject {
            put("lat", 49.8419)
            put("lon", 24.0316)
            put("name", name)
            put("group", "Lviv")
            put("color", "#FF0000")
            put("icon", "special_star")
            if (shape != null) {
                put("shape", shape)
            }
        }

    private fun idParams(id: String): JsonObject = buildJsonObject { put("id", id) }

    private fun pathParams(path: String): JsonObject = buildJsonObject { put("path", path) }

    private object KeyOnlyStrings : Strings {
        override fun resolve(
            key: String,
            arguments: List<String>,
        ): String = key

        override fun contains(key: String): Boolean = false
    }
}
