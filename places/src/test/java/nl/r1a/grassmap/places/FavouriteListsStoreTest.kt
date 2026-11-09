package nl.r1a.grassmap.places

import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * The lists actions of T-0213: create, move, recolour, Home and Work, each with
 * its scoped undo, and the GPX that every action persists.
 */
class FavouriteListsStoreTest {
    private val directory = File(System.getProperty("java.io.tmpdir"), "favourite-lists-store-${UUID.randomUUID()}")
    private val file = File(directory, "favourites/favourites.gpx")

    @After
    fun removeDirectory() {
        directory.deleteRecursively()
    }

    @Test
    fun creatingAListPersistsItsDefinitionAndUndoRemovesIt() =
        runTest {
            val store = FavouritesStore(file)
            store.save(snapshot())

            val created = value(store.createGroup("Cafes", "#00FF00", "cafe"))
            assertEquals(FavouriteGroup("Cafes", "#00FF00", "cafe"), created.value)
            assertTrue(loaded(store).groups.any { it.name == "Cafes" })
            assertTrue(exportText(store).contains("<group name=\"Cafes\" color=\"#00FF00\" icon=\"cafe\" background=\"circle\"/>"))

            assertEquals(Outcome.Success(FavouriteUndo.APPLIED), store.undoListAction(created.undo))
            assertTrue(loaded(store).groups.none { it.name == "Cafes" })
            assertTrue(exportText(store).contains("<group name=\"Lviv\""))
            assertTrue(exportText(store).contains("<group name=\"Parks\""))
        }

    @Test
    fun movingAPointWritesTheNewTypeAndUndoRestoresTheOldList() =
        runTest {
            val store = FavouritesStore(file)
            store.save(snapshot())
            val rynok = favouriteNamed(store, "Rynok Square")

            val moved = value(store.moveFavourite(rynok.id, "Parks"))
            assertEquals("Parks", moved.value.group)
            assertEquals("Parks", favouriteNamed(store, "Rynok Square").group)
            assertTrue(exportText(store).contains("<type>Parks</type>"))

            assertEquals(Outcome.Success(FavouriteUndo.APPLIED), store.undoListAction(moved.undo))
            assertEquals("Lviv", favouriteNamed(store, "Rynok Square").group)
            assertEquals("Rynok Square", favouriteNamed(store, "Rynok Square").name)
            assertTrue(exportText(store).contains("<type>Lviv</type>"))
        }

    @Test
    fun movingAPointThatInheritedItsListAppearanceAdoptsTheDestinationLook() =
        runTest {
            val store = FavouritesStore(file)
            store.save(snapshot())
            val rynok = favouriteNamed(store, "Rynok Square")

            val moved = value(store.moveFavourite(rynok.id, "Parks"))

            val parks = loaded(store).groups.single { it.name == "Parks" }
            assertEquals(parks.color, moved.value.color)
            assertEquals(parks.shape, moved.value.shape)
            assertNull(parks.icon)
            assertNull(moved.value.icon)
        }

    @Test
    fun recolouringAListChangesEveryPointIncludingOneThatInheritedTheLook() =
        runTest {
            val store = FavouritesStore(file)
            store.save(snapshot())
            val before = loaded(store)
            val rynok = before.favourites.single { it.name == "Rynok Square" }
            val vyso = before.favourites.single { it.name == "Vysoky Zamok" }
            assertEquals("Rynok inherited the list colour", before.groups.single { it.name == "Lviv" }.color, rynok.color)
            assertEquals("Vysoky carried its own colour", "#ff4e4eff", vyso.color)

            val recoloured = value(store.recolourGroup("Lviv", "#00FF00"))
            assertEquals("#00FF00", recoloured.value.color)

            val after = loaded(store)
            assertEquals("#00FF00", after.groups.single { it.name == "Lviv" }.color)
            assertEquals("#00FF00", after.favourites.single { it.name == "Rynok Square" }.color)
            assertEquals("#00FF00", after.favourites.single { it.name == "Vysoky Zamok" }.color)
            assertEquals(
                "the other list is untouched",
                "#0000FF",
                after.favourites.single { it.name == "Aqueduct" }.color,
            )
            val exported = exportText(store)
            assertTrue(exported.contains("<group name=\"Lviv\" color=\"#00FF00\""))
            assertTrue(exported.contains("<osmand:color>#00FF00</osmand:color>"))

            assertEquals(Outcome.Success(FavouriteUndo.APPLIED), store.undoListAction(recoloured.undo))
            val restored = loaded(store)
            assertEquals("#FF0000", restored.groups.single { it.name == "Lviv" }.color)
            assertEquals("#FF0000", restored.favourites.single { it.name == "Rynok Square" }.color)
            assertEquals("#ff4e4eff", restored.favourites.single { it.name == "Vysoky Zamok" }.color)
        }

    @Test
    fun homeAndWorkAreOneEachAndSurviveAReload() =
        runTest {
            val store = FavouritesStore(file)
            store.save(snapshot())
            val rynok = favouriteNamed(store, "Rynok Square")
            val vyso = favouriteNamed(store, "Vysoky Zamok")
            val aqueduct = favouriteNamed(store, "Aqueduct")

            store.setSpecial(rynok.id, FavouriteSpecial.Home)
            store.setSpecial(vyso.id, FavouriteSpecial.Work)
            val secondHome = value(store.setSpecial(aqueduct.id, FavouriteSpecial.Home))

            val after = loaded(store)
            assertEquals(FavouriteSpecial.Home, favouriteNamed(store, "Aqueduct").special)
            assertEquals(FavouriteSpecial.Work, favouriteNamed(store, "Vysoky Zamok").special)
            assertNull(favouriteNamed(store, "Rynok Square").special)
            assertEquals(
                "one Home and one Work",
                setOf("Aqueduct", "Vysoky Zamok"),
                after.favourites
                    .filter { it.special != null }
                    .map { it.name }
                    .toSet(),
            )

            val reloaded = loaded(FavouritesStore(file))
            assertEquals(FavouriteSpecial.Home, favouriteNamed(reloaded, "Aqueduct").special)
            val exported = exportText(store)
            assertTrue(exported.contains("<grassmap:special>home</grassmap:special>"))
            assertTrue(exported.contains("<grassmap:special>work</grassmap:special>"))

            assertEquals(Outcome.Success(FavouriteUndo.APPLIED), store.undoListAction(secondHome.undo))
            val restored = loaded(store)
            assertEquals(FavouriteSpecial.Home, favouriteNamed(restored, "Rynok Square").special)
            assertNull(favouriteNamed(restored, "Aqueduct").special)
            assertEquals(FavouriteSpecial.Work, favouriteNamed(restored, "Vysoky Zamok").special)
        }

    @Test
    fun undoingACreateThatGainedMembersIsATypedNoOp() =
        runTest {
            val store = FavouritesStore(file)
            store.save(snapshot())
            val created = value(store.createGroup("Cafes", "#00FF00", null))
            val rynok = favouriteNamed(store, "Rynok Square")
            store.moveFavourite(rynok.id, "Cafes")

            assertEquals(Outcome.Success(FavouriteUndo.NOT_APPLICABLE), store.undoListAction(created.undo))
            assertTrue(loaded(store).groups.any { it.name == "Cafes" })
            assertEquals("Cafes", favouriteNamed(store, "Rynok Square").group)
        }

    @Test
    fun undoingASpecialAfterItsPointWasDeletedIsATypedNoOp() =
        runTest {
            val store = FavouritesStore(file)
            store.save(snapshot())
            val rynok = favouriteNamed(store, "Rynok Square")
            val receipt = value(store.setSpecial(rynok.id, FavouriteSpecial.Home))
            store.delete(rynok.id)

            assertEquals(Outcome.Success(FavouriteUndo.NOT_APPLICABLE), store.undoListAction(receipt.undo))
            assertTrue(loaded(store).favourites.none { it.name == "Rynok Square" })
        }

    @Test
    fun anUnknownUndoTokenIsATypedNoOp() =
        runTest {
            val store = FavouritesStore(file)
            store.save(snapshot())
            val before = loaded(store)

            assertEquals(
                Outcome.Success(FavouriteUndo.NOT_APPLICABLE),
                store.undoListAction(FavouriteUndoToken("no-such-action")),
            )
            assertEquals(before, loaded(store))
        }

    @Test
    fun aBlankListOrAnUnknownPointIsATypedError() =
        runTest {
            val store = FavouritesStore(file)
            store.save(snapshot())

            assertEquals(Outcome.Failure(AppError.InvalidInput), store.createGroup(" ", "#FF0000", null))
            assertEquals(Outcome.Failure(AppError.InvalidInput), store.createGroup("Cafes", " ", null))
            assertEquals(
                Outcome.Failure(AppError.InvalidInput),
                store.createGroup("Lviv", "#00FF00", null),
            )
            assertEquals(
                Outcome.Failure(AppError.NotFound),
                store.moveFavourite(FavouriteId("missing"), "Parks"),
            )
            assertEquals(
                Outcome.Failure(AppError.NotFound),
                store.moveFavourite(favouriteNamed(store, "Rynok Square").id, "Missing"),
            )
            assertEquals(
                Outcome.Failure(AppError.NotFound),
                store.setSpecial(FavouriteId("missing"), FavouriteSpecial.Home),
            )
        }

    private suspend fun favouriteNamed(
        store: FavouritesStore,
        name: String,
    ): Favourite = loaded(store).favourites.single { it.name == name }

    private fun favouriteNamed(
        snapshot: FavouritesSnapshot,
        name: String,
    ): Favourite = snapshot.favourites.single { it.name == name }

    private suspend fun loaded(store: FavouritesStore): FavouritesSnapshot =
        when (val outcome = store.load()) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("load failed: ${outcome.error}")
        }

    private suspend fun exportText(store: FavouritesStore): String =
        when (val outcome = store.export()) {
            is Outcome.Success -> String(outcome.value, Charsets.UTF_8)
            is Outcome.Failure -> throw AssertionError("export failed: ${outcome.error}")
        }

    private fun <T> value(outcome: Outcome<T>): T =
        when (outcome) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("action failed: ${outcome.error}")
        }

    private fun snapshot(): FavouritesSnapshot =
        FavouritesSnapshot(
            groups =
                listOf(
                    FavouriteGroup("Lviv", "#FF0000", "historic_castle", FavouriteShape.Circle),
                    FavouriteGroup("Parks", "#0000FF", null, FavouriteShape.Square),
                ),
            favourites =
                deriveIds(
                    listOf(
                        favourite(
                            49.8419,
                            24.0316,
                            "Rynok Square",
                            "Town hall corner",
                            "Lviv",
                            "#FF0000",
                            "historic_castle",
                        ),
                        favourite(
                            49.8383,
                            24.0329,
                            "Vysoky Zamok",
                            group = "Lviv",
                            color = "#ff4e4eff",
                            icon = "historic_castle",
                            shape = FavouriteShape.Square,
                        ),
                        favourite(
                            49.8356,
                            24.035,
                            "Aqueduct",
                            group = "Parks",
                            color = "#0000FF",
                            shape = FavouriteShape.Octagon,
                        ),
                        favourite(49.844, 24.029, "No group"),
                    ),
                ),
        )
}
