package nl.r1a.grassmap.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.r1a.grassmap.core.CommandDispatchers
import nl.r1a.grassmap.plugin.api.contract.CommandId
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.FavouriteListsState
import nl.r1a.grassmap.plugin.api.model.FavouriteSpecial
import nl.r1a.grassmap.plugin.api.service.Strings
import nl.r1a.grassmap.plugins.favourites.ui.FavouritesUiContract
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The T-0213 acceptance path through the real command bus, the plugin, the
 * app adapter and the merged favourites store: create a list, move a point,
 * recolour the list, set Home and Work, undo each, and check the GPX file.
 */
class FavouritesListsCommandTest {
    private val root: File = Files.createTempDirectory("grass-favourites-lists").toFile()

    @After
    fun removeRoot() {
        root.deleteRecursively()
    }

    @Test
    fun createMoveRecolourHomeAndWorkLandInTheFileAndInTheStream() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val container = container(dispatcher)
            add(container, "Rynok Square", "#FF0000")
            add(container, "Office", "#0000FF")

            command(container, FavouritesUiContract.createList, listParams("name" to "Cafes", "color" to "#00FF00", "icon" to "cafe"))
            assertTrue(gpx().contains("<group name=\"Cafes\" color=\"#00FF00\" icon=\"cafe\" background=\"circle\"/>"))
            assertEquals(listOf("Cafes"), container.lists().lists.map { it.name })

            command(
                container,
                FavouritesUiContract.movePoint,
                listParams("pointId" to container.idOf("Rynok Square"), "list" to "Cafes"),
            )
            assertEquals("Cafes", container.point("Rynok Square").list)
            assertTrue(gpx().contains("<type>Cafes</type>"))

            command(
                container,
                FavouritesUiContract.movePoint,
                listParams("pointId" to container.idOf("Office"), "list" to "Cafes"),
            )
            command(container, FavouritesUiContract.recolourList, listParams("list" to "Cafes", "color" to "#1E88E5"))
            assertEquals(
                "#1E88E5",
                container
                    .lists()
                    .lists
                    .single { it.name == "Cafes" }
                    .color,
            )
            val recoloured = gpx()
            assertTrue(recoloured.contains("<group name=\"Cafes\" color=\"#1E88E5\""))
            assertEquals(
                "both points carry the new colour",
                2,
                Regex("<osmand:color>#1E88E5</osmand:color>").findAll(recoloured).count(),
            )

            command(container, FavouritesUiContract.setHome, listParams("pointId" to container.idOf("Rynok Square")))
            command(container, FavouritesUiContract.setWork, listParams("pointId" to container.idOf("Office")))
            assertEquals(FavouriteSpecial.HOME, container.point("Rynok Square").special)
            assertEquals(FavouriteSpecial.WORK, container.point("Office").special)
            val specials = gpx()
            assertTrue(specials.contains("<grassmap:special>home</grassmap:special>"))
            assertTrue(specials.contains("<grassmap:special>work</grassmap:special>"))

            assertEquals(
                listOf("Rynok Square", "Office"),
                container
                    .lists()
                    .points
                    .filter { it.special != null }
                    .map { it.name },
            )
        }

    @Test
    fun everyActionUndoesToThePreviousFileAndState() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val container = container(dispatcher)
            add(container, "Rynok Square", "#FF0000")

            command(container, FavouritesUiContract.createList, listParams("name" to "Cafes", "color" to "#00FF00", "icon" to "cafe"))
            undo(container)
            assertTrue(container.lists().lists.isEmpty())
            assertTrue(!gpx().contains("name=\"Cafes\""))

            command(container, FavouritesUiContract.createList, listParams("name" to "Cafes", "color" to "#00FF00", "icon" to "cafe"))
            command(
                container,
                FavouritesUiContract.movePoint,
                listParams("pointId" to container.idOf("Rynok Square"), "list" to "Cafes"),
            )
            undo(container)
            assertEquals(null, container.point("Rynok Square").list)
            assertTrue(!gpx().contains("<type>Cafes</type>"))

            command(
                container,
                FavouritesUiContract.movePoint,
                listParams("pointId" to container.idOf("Rynok Square"), "list" to "Cafes"),
            )
            command(container, FavouritesUiContract.recolourList, listParams("list" to "Cafes", "color" to "#1E88E5"))
            undo(container)
            assertEquals(
                "#00FF00",
                container
                    .lists()
                    .lists
                    .single { it.name == "Cafes" }
                    .color,
            )
            assertTrue(gpx().contains("<osmand:color>#FF0000</osmand:color>"))

            command(container, FavouritesUiContract.setHome, listParams("pointId" to container.idOf("Rynok Square")))
            undo(container)
            assertEquals(null, container.point("Rynok Square").special)
            assertTrue(!gpx().contains("<grassmap:special>home</grassmap:special>"))
        }

    @Test
    fun homeAndWorkSurviveANewContainerOverTheSameFile() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val container = container(dispatcher)
            add(container, "Flat", "#FF0000")
            add(container, "Office", "#0000FF")
            command(container, FavouritesUiContract.setHome, listParams("pointId" to container.idOf("Flat")))
            command(container, FavouritesUiContract.setWork, listParams("pointId" to container.idOf("Office")))

            val reloaded = container(dispatcher)

            assertEquals(FavouriteSpecial.HOME, reloaded.point("Flat").special)
            assertEquals(FavouriteSpecial.WORK, reloaded.point("Office").special)
            assertEquals(2, reloaded.lists().points.count { it.special != null })
        }

    @Test
    fun undoingAnActionThatCannotApplyIsATypedNoOp() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val container = container(dispatcher)
            val point = add(container, "Rynok Square", "#FF0000")
            command(container, FavouritesUiContract.createList, listParams("name" to "Cafes", "color" to "#00FF00", "icon" to "cafe"))
            command(container, FavouritesUiContract.movePoint, listParams("pointId" to point, "list" to "Cafes"))
            command(container, FavouriteCommandIds.DELETE, idParams(container.idOf("Rynok Square")))

            val outcome = container.commandBus.invoke(FavouritesUiContract.undo, JsonObject(emptyMap()))

            assertTrue(outcome is Outcome.Success)
            val after = container.lists()
            assertTrue("the list survives an undo that cannot apply", after.lists.any { it.name == "Cafes" })
            assertTrue("the deleted point is not resurrected", after.points.isEmpty())
            assertTrue(gpx().contains("name=\"Cafes\""))
        }

    private fun container(dispatcher: CoroutineDispatcher): AppContainer =
        AppContainer(
            strings = KeyOnlyStrings,
            dispatchers = CommandDispatchers(main = dispatcher, default = dispatcher, io = dispatcher),
            favouritesRootProvider = { root },
        )

    private suspend fun add(
        container: AppContainer,
        name: String,
        color: String,
    ): String {
        val outcome =
            container.commandBus.invoke(
                FavouriteCommandIds.ADD,
                buildJsonObject {
                    put("lat", 49.8419)
                    put("lon", 24.0316)
                    put("name", name)
                    put("color", color)
                },
            )
        return when (outcome) {
            is Outcome.Success -> (outcome.value as JsonPrimitive).content
            is Outcome.Failure -> throw AssertionError("add failed: ${outcome.error}")
        }
    }

    private suspend fun command(
        container: AppContainer,
        id: CommandId,
        params: JsonObject,
    ) {
        when (val outcome = container.commandBus.invoke(id, params)) {
            is Outcome.Success -> Unit
            is Outcome.Failure -> throw AssertionError("$id failed: ${outcome.error}")
        }
    }

    private suspend fun undo(container: AppContainer) {
        command(container, FavouritesUiContract.undo, JsonObject(emptyMap()))
    }

    private suspend fun AppContainer.lists(): FavouriteListsState =
        streamAccess.subscribe<FavouriteListsState>(FavouritesUiContract.listsStream).first()

    private suspend fun AppContainer.point(name: String): nl.r1a.grassmap.plugin.api.model.FavouritePointSummary =
        lists().points.single { it.name == name }

    private suspend fun AppContainer.idOf(name: String): String = point(name).id

    private fun gpx(): String = File(root, "favourites/favourites.gpx").readText()

    private fun listParams(vararg pairs: Pair<String, String>): JsonObject =
        buildJsonObject {
            pairs.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
        }

    private fun idParams(id: String): JsonObject = buildJsonObject { put("id", id) }

    private object KeyOnlyStrings : Strings {
        override fun resolve(
            key: String,
            arguments: List<String>,
        ): String = key

        override fun contains(key: String): Boolean = false
    }
}
